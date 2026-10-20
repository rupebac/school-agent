#!/usr/bin/env node
/** Real backend + built SPA + disposable PostgreSQL. Never points at an existing installation. */
import http from 'node:http';
import net from 'node:net';
import { spawn, execFileSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { mkdtemp, readFile, writeFile, rm, readdir } from 'node:fs/promises';
import { createWriteStream } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve, dirname, sep, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const work = await mkdtemp(resolve(tmpdir(), 'school-e2e-'));
const credentials = { username: 'synthetic-admin', password: randomBytes(24).toString('base64url') };
const childEnv = Object.fromEntries(Object.entries(process.env).filter(([key]) => !key.startsWith('SCHOOLAGENT_') && !key.startsWith('SPRING_')));
const dockerEnv = { ...childEnv, POSTGRES_PASSWORD: randomBytes(24).toString('base64url') };
let container, api, browser;
const logs = createWriteStream(resolve(work, 'backend.log'), { mode: 0o600 });
const apiPort = await new Promise((done) => { const s=net.createServer(); s.listen(0,'127.0.0.1',()=>{const p=s.address().port; s.close(()=>done(p));}); });
const staticRoot = resolve(root, 'frontend/dist/frontend/browser');
const server = http.createServer(async (req, res) => {
  if (req.url === '/__test/credentials') { res.setHeader('Content-Type','application/json'); res.end(JSON.stringify(credentials)); return; }
  if (req.url.startsWith('/api/') || req.url.startsWith('/actuator/')) {
    const upstream = http.request({ hostname:'127.0.0.1', port:apiPort, path:req.url, method:req.method, headers:req.headers }, response => {
      res.writeHead(response.statusCode, response.headers); response.pipe(res);
    });
    upstream.on('error',()=>{res.writeHead(502);res.end();}); req.pipe(upstream); return;
  }
  try {
    let path=resolve(staticRoot, '.'+decodeURIComponent(new URL(req.url,'http://example.test').pathname));
    if (!path.startsWith(staticRoot+sep)) path=resolve(staticRoot,'index.html');
    let bytes; try { bytes=await readFile(path); } catch { path=resolve(staticRoot,'index.html'); bytes=await readFile(path); }
    res.setHeader('Content-Type',({'.html':'text/html','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml','.json':'application/json'})[extname(path)]||'application/octet-stream');
    res.end(bytes);
  } catch { res.writeHead(400); res.end(); }
});
await new Promise(done=>server.listen(0,'127.0.0.1',done));
const origin='http://127.0.0.1:'+server.address().port;
async function cleanup() {
  await browser?.close().catch(()=>{});
  if (api) { api.kill('SIGTERM'); await new Promise(resolve=>{if(api.exitCode!==null)return resolve(); const t=setTimeout(()=>{api.kill('SIGKILL');resolve();},5000); api.once('exit',()=>{clearTimeout(t);resolve();});}); }
  server.closeAllConnections(); server.close(); logs.end();
  if (container) { try { execFileSync('docker',['rm','-f','-v',container],{stdio:'ignore'}); } catch {} }
  await rm(work,{recursive:true,force:true});
}
process.once('SIGINT',()=>cleanup().then(()=>process.exit()));
process.once('SIGTERM',()=>cleanup().then(()=>process.exit()));
try {
  container=execFileSync('docker',['run','-d','--rm','-e','POSTGRES_PASSWORD','-e','POSTGRES_USER=schoolagent','-e','POSTGRES_DB=schoolagent','-p','127.0.0.1::5432','postgres:17'],{env:dockerEnv,encoding:'utf8'}).trim();
  const dbPort=execFileSync('docker',['port',container,'5432/tcp'],{encoding:'utf8'}).trim().split(':').at(-1);
  const jar=(await readdir(resolve(root,'backend/target'))).find(name=>name.endsWith('.jar')&&!name.endsWith('-sources.jar'));
  if (!jar) throw new Error('Build the backend jar before running E2E');
  api=spawn('java',['-jar',resolve(root,'backend/target',jar)],{cwd:work,env:{...childEnv,
    SERVER_PORT:String(apiPort),SCHOOLAGENT_DB_HOST:'127.0.0.1',SCHOOLAGENT_DB_PORT:dbPort,
    SCHOOLAGENT_DB_PASSWORD:dockerEnv.POSTGRES_PASSWORD,SCHOOLAGENT_MASTER_KEY:randomBytes(32).toString('base64'),
    SCHOOLAGENT_INITIAL_USERNAME:credentials.username,SCHOOLAGENT_INITIAL_PASSWORD:credentials.password,
    SCHOOLAGENT_PUBLIC_URL:origin,SCHOOLAGENT_CALENDAR_AUTO_IMPORT:'false',SCHOOLAGENT_GMAIL_POLLING:'false',
    SCHOOLAGENT_DOCUMENTS_DIR:resolve(work,'documents')},stdio:['ignore','pipe','pipe']});
  api.stdout.pipe(logs); api.stderr.pipe(logs);
  let healthy=false;
  for(let i=0;i<120;i++) {
    if(api.exitCode!==null) throw new Error('Synthetic backend exited during startup');
    try { healthy=(await (await fetch(origin+'/actuator/health')).json()).status==='UP'; } catch {}
    if(healthy) break; await new Promise(resolve=>setTimeout(resolve,500));
  }
  if(!healthy) throw new Error('Synthetic backend did not become healthy');
  if(process.argv.includes('--serve')) {
    await writeFile('/tmp/school-e2e-origin',origin,{mode:0o600});
    console.log('Synthetic full-stack fixture ready:',origin);
    await new Promise(()=>{});
  }
  const require=createRequire(resolve(root,'playwright-worker/package.json'));
  const {chromium}=require('playwright'); browser=await chromium.launch();
  const page=await browser.newPage(); const errors=[]; page.on('pageerror',e=>errors.push(e.name));
  await page.goto(origin);
  await page.locator('input[name=user]').fill(credentials.username);
  await page.locator('input[name=password]').fill(credentials.password);
  await page.locator('button[type=submit]').click();
  await page.getByRole('heading',{name:'Inbox',exact:true}).waitFor();
  await page.goto(origin+'/settings?area=family');
  await page.getByRole('button',{name:'Add a child',exact:true}).click();
  await page.getByLabel('Name',{exact:true}).fill('Synthetic Child');
  await page.getByRole('button',{name:'Add child',exact:true}).click();
  await page.getByText('Synthetic Child',{exact:true}).first().waitFor();
  await page.reload(); await page.getByText('Synthetic Child',{exact:true}).first().waitFor();
  await page.goto(origin+'/calendar'); await page.getByRole('heading',{name:'Calendar',exact:true}).waitFor();
  await page.setViewportSize({width:390,height:844});
  if(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth)) throw new Error('Mobile overflow');
  await page.goto(origin+'/settings?area=family');
  await page.getByText('Synthetic Child',{exact:true}).first().waitFor();
  await page.evaluate(() => {
    const select=Array.from(document.querySelectorAll('select')).find(s=>s.options.length===2&&s.options[0].value==='en'&&s.options[1].value==='es');
    select.value='es'; select.dispatchEvent(new Event('change',{bubbles:true}));
  });
  await page.getByRole('button',{name:'Añadir un hijo',exact:true}).waitFor();
  if(errors.length) throw new Error('Browser runtime errors');
  console.log('PASS: real-backend login, persistent child creation, Calendar, Spanish and mobile layout');
} finally { await cleanup(); }

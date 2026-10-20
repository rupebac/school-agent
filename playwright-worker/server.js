/**
 * playwright-worker — read-only browser investigation service (spec §11).
 *
 * Runs Playwright headless in its own container so the backend never runs a
 * browser in-process. v1 is strictly READ-ONLY: navigate, extract DOM/text,
 * describe forms, download files. It has NO endpoints that type, click, submit
 * or authenticate — the automation boundary is enforced here, not just in
 * prompts (spec §12/§32).
 *
 * Endpoints:
 *   GET  /health              → liveness
 *   POST /render  {url}       → {url, title, text, links[]}
 *   POST /form   {url}        → {url, fields[{name,type,label,required,options[]}], action, method}
 *
 * The backend calls it over the internal Docker network only.
 */
const express = require('express');
const { chromium } = require('playwright');
const { safeUrl, protectContext } = require('./network-policy');

const app = express();
app.use(express.json({ limit: '1mb' }));

let active = 0;
app.get('/health', (_req, res) => res.json({ status: 'UP', mode: 'read-only' }));

app.post('/render', async (req, res) => {
  let url;
  try { url = safeUrl(req.body?.url); } catch { return res.status(400).json({ error: 'invalid or disallowed url' }); }
  if (active >= 2) return res.status(429).json({ error: 'Browser worker busy' });
  active++;
  let browser; let deadline;
  try {
    browser = await chromium.launch({ chromiumSandbox: true, args: ['--force-webrtc-ip-handling-policy=disable_non_proxied_udp'] });
    deadline = setTimeout(() => { browser.close().catch(() => {}); }, 25000);
    const context = await browser.newContext({ serviceWorkers: 'block', acceptDownloads: false });
    await protectContext(context);
    const page = await context.newPage();
    const response = await page.goto(url.toString(), { waitUntil: 'domcontentloaded', timeout: 20000 });
    const status = response ? response.status() : null;
    const links = await page.$$eval('a[href]', (as) =>
      as.map((a) => a.getAttribute('href')).filter((h) => h && h.startsWith('http')).slice(0, 200),
    );
    res.json({
      url: page.url(),
      status,
      title: await page.title(),
      text: (await page.evaluate(() => document.body?.innerText ?? '')).slice(0, 100000),
      links,
    });
  } catch (e) {
    res.status(502).json({ error: 'render failed' });
  } finally {
    clearTimeout(deadline);
    try { await browser?.close(); } finally { active--; }
  }
});

app.post('/form', async (req, res) => {
  let url;
  try { url = safeUrl(req.body?.url); } catch { return res.status(400).json({ error: 'invalid or disallowed url' }); }
  if (active >= 2) return res.status(429).json({ error: 'Browser worker busy' });
  active++;
  let browser; let deadline;
  try {
    browser = await chromium.launch({ chromiumSandbox: true, args: ['--force-webrtc-ip-handling-policy=disable_non_proxied_udp'] });
    deadline = setTimeout(() => { browser.close().catch(() => {}); }, 25000);
    const context = await browser.newContext({ serviceWorkers: 'block', acceptDownloads: false });
    await protectContext(context);
    const page = await context.newPage();
    await page.goto(url.toString(), { waitUntil: 'domcontentloaded', timeout: 20000 });
    const forms = await page.$$eval('form', (nodes) =>
      nodes.map((form) => ({
        method: form.getAttribute('method') ?? 'GET',
        action: form.getAttribute('action'),
        fields: Array.from(form.querySelectorAll('input, select, textarea')).map((el) => ({
          tag: el.tagName.toLowerCase(),
          type: el.getAttribute('type'),
          name: el.getAttribute('name'),
          label: el.getAttribute('aria-label') || el.getAttribute('placeholder'),
          required: el.hasAttribute('required'),
          options: el.tagName === 'SELECT' ? Array.from(el.options).map((o) => o.textContent?.trim()) : undefined,
        })),
      })),
    );
    res.json({ url: page.url(), forms });
  } catch (e) {
    res.status(502).json({ error: 'form inspection failed' });
  } finally {
    clearTimeout(deadline);
    try { await browser?.close(); } finally { active--; }
  }
});

// No POST/PUT that mutates anything exists on purpose: v1 is read-only (spec §12).

const port = Number(process.env.PORT || 8100);
app.listen(port, '0.0.0.0', () => console.log(`playwright-worker read-only on :${port}`));

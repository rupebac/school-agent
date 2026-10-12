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

const app = express();
app.use(express.json({ limit: '1mb' }));

const MAX_BYTES = 5 * 1024 * 1024;
const ALLOWED = new Set(['http:', 'https:']);

function safeUrl(raw) {
  let url;
  try { url = new URL(String(raw)); } catch { return null; }
  if (!ALLOWED.has(url.protocol)) return null;
  return url;
}

app.get('/health', (_req, res) => res.json({ status: 'UP', mode: 'read-only' }));

app.post('/render', async (req, res) => {
  const url = safeUrl(req.body?.url);
  if (!url) return res.status(400).json({ error: 'invalid or disallowed url' });
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage({ userAgent: 'SchoolAgent/0.1 (family private agent)' });
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
    res.status(502).json({ error: `render failed: ${e.message}` });
  } finally {
    await browser.close();
  }
});

app.post('/form', async (req, res) => {
  const url = safeUrl(req.body?.url);
  if (!url) return res.status(400).json({ error: 'invalid or disallowed url' });
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage();
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
    res.status(502).json({ error: `form inspection failed: ${e.message}` });
  } finally {
    await browser.close();
  }
});

// No POST/PUT that mutates anything exists on purpose: v1 is read-only (spec §12).

const port = Number(process.env.PORT || 8100);
app.listen(port, '0.0.0.0', () => console.log(`playwright-worker read-only on :${port}`));

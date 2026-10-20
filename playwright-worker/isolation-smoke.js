// Run inside the configured worker container. All page responses are synthetic.
const assert = require('node:assert/strict');
const { chromium } = require('playwright');
const { protectContext } = require('./network-policy');
(async () => {
  const browser = await chromium.launch({ chromiumSandbox: true });
  try {
    const context = await browser.newContext({ serviceWorkers: 'block' });
    const transported = [];
    await protectContext(context, async (url, method) => {
      transported.push({url,method});
      return {status:200,headers:{'content-type':'text/html'},body:Buffer.from(`<html><body>Fixture<script>
        Promise.allSettled([
          fetch('http://127.0.0.1/private'),
          fetch('https://fixture.example.test/write', {method:'POST',body:'synthetic'})
        ]).then(results => { window.blocked = results.map(result => result.status); });
      </script></body></html>`)};
    });
    const page = await context.newPage();
    await page.goto('https://fixture.example.test/');
    await page.waitForFunction(() => window.blocked?.length === 2);
    assert.deepEqual(await page.evaluate(() => window.blocked), ['rejected','rejected']);
    assert.deepEqual(transported, [{url:'https://fixture.example.test/',method:'GET'}]);
    console.log('PASS: sandboxed Chromium blocks private subrequests and page-initiated writes');
  } finally { await browser.close(); }
})().catch(() => { console.error('Browser isolation smoke failed'); process.exitCode = 1; });

const { test } = require('node:test');
const assert = require('node:assert/strict');
const { isPublic, safeUrl, fetchPublic, protectContext } = require('./network-policy');

test('blocks local, private, metadata, encoded and transition addresses', () => {
  for (const address of ['127.0.0.1', '10.0.0.1', '169.254.169.254', '100.64.0.1', '172.16.0.1', '192.168.0.1',
    '0.0.0.0', '224.0.0.1', '::1', '::ffff:127.0.0.1', 'fc00::1', '2002:7f00:1::']) assert.equal(isPublic(address), false, address);
  for (const url of ['http://2130706433/form', 'http://0x7f000001/form', 'http://[::1]/form',
    'file:///etc/hosts', 'http://user:pass@example.org/', 'http://example.org:5432/']) assert.throws(() => safeUrl(url));
  assert.equal(isPublic('8.8.8.8'), true);
  assert.equal(isPublic('2606:4700:4700::1111'), true);
});

test('DNS resolution cannot reach loopback', async () => {
  await assert.rejects(fetchPublic('http://localhost/form'), /Destination not allowed/);
});

test('routes redirects and subresources through the same policy and blocks write requests', async () => {
  let handler; let socketHandler; let fetched = 0; let aborted = 0; let fulfilled = 0;
  await protectContext({ route: async (_, fn) => { handler = fn; }, routeWebSocket: async (_, fn) => { socketHandler = fn; } },
    async () => { fetched++; return { status: 200, headers: {}, body: Buffer.from('ok') }; });
  function route(url, method = 'GET') { return { request: () => ({ url: () => url, method: () => method }),
    abort: async () => { aborted++; }, fulfill: async () => { fulfilled++; } }; }
  await handler(route('https://school.example/form'));
  await handler(route('http://127.0.0.1/private'));
  await handler(route('https://school.example/form', 'POST'));
  let closed = false; socketHandler({ close: () => { closed = true; } });
  assert.equal(fetched, 1); assert.equal(fulfilled, 1); assert.equal(aborted, 2); assert.equal(closed, true);
});

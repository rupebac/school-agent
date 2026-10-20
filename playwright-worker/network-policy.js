const dns = require('node:dns');
const net = require('node:net');
const http = require('node:http');
const https = require('node:https');

const blocked = new net.BlockList();
for (const [ip, prefix] of [
  ['0.0.0.0', 8], ['10.0.0.0', 8], ['100.64.0.0', 10], ['127.0.0.0', 8],
  ['169.254.0.0', 16], ['172.16.0.0', 12], ['192.0.0.0', 24], ['192.0.2.0', 24],
  ['192.168.0.0', 16], ['198.18.0.0', 15], ['198.51.100.0', 24], ['203.0.113.0', 24],
  ['224.0.0.0', 3],
]) blocked.addSubnet(ip, prefix, 'ipv4');
for (const [ip, prefix] of [['2001::', 23], ['2001:db8::', 32], ['2002::', 16]])
  blocked.addSubnet(ip, prefix, 'ipv6');
const globalV6 = new net.BlockList();
globalV6.addSubnet('2000::', 3, 'ipv6');

function isPublic(address) {
  const family = net.isIP(address);
  return family === 4 ? !blocked.check(address, 'ipv4') :
    family === 6 && globalV6.check(address, 'ipv6') && !blocked.check(address, 'ipv6');
}
function safeUrl(raw) {
  let url;
  try { url = new URL(raw); } catch { throw new Error('Invalid destination'); }
  const host = url.hostname.replace(/^\[|\]$/g, '');
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password ||
      (url.port && !['80', '443'].includes(url.port)) || (net.isIP(host) && !isPublic(host)))
    throw new Error('Destination not allowed');
  return url;
}

// Validate the actual addresses given to the socket, not a separate DNS preflight.
function publicLookup(hostname, options, callback) {
  dns.lookup(hostname, { all: true }, (error, records) => {
    if (error) return callback(new Error('Destination lookup failed'));
    if (!records.length || records.some(record => !isPublic(record.address)))
      return callback(new Error('Destination not allowed'));
    if (options?.all) callback(null, records);
    else callback(null, records[0].address, records[0].family);
  });
}

function fetchPublic(raw, method = 'GET', maxBytes = 5 * 1024 * 1024) {
  const url = safeUrl(raw);
  if (!['GET', 'HEAD'].includes(method)) throw new Error('Read-only requests only');
  return new Promise((resolve, reject) => {
    const request = (url.protocol === 'https:' ? https : http).request(url, {
      method, lookup: publicLookup, agent: false,
      headers: { 'User-Agent': 'SchoolAgent/0.1', 'Accept-Encoding': 'identity' },
    }, response => {
      const chunks = []; let size = 0;
      response.on('data', chunk => {
        size += chunk.length;
        if (size > maxBytes) request.destroy(new Error('Response exceeds size limit'));
        else chunks.push(chunk);
      });
      response.on('error', reject);
      response.on('end', () => resolve({ status: response.statusCode, headers: response.headers, body: Buffer.concat(chunks) }));
    });
    const deadline = setTimeout(() => request.destroy(new Error('Request timed out')), 15000);
    request.on('close', () => clearTimeout(deadline));
    request.on('error', reject);
    request.end();
  });
}

async function protectContext(context, fetcher = fetchPublic) {
  let requests = 0; let inflight = 0; let bytes = 0;
  await context.route('**/*', async route => {
    if (++requests > 50 || inflight >= 4 || bytes >= 20 * 1024 * 1024) return await route.abort();
    inflight++;
    try {
      const request = route.request();
      safeUrl(request.url());
      if (!['GET', 'HEAD'].includes(request.method())) return await route.abort();
      const result = await fetcher(request.url(), request.method());
      bytes += result.body.length;
      const headers = {};
      for (const [key, value] of Object.entries(result.headers))
        if (value != null && !['transfer-encoding', 'connection', 'content-length', 'set-cookie'].includes(key))
          headers[key] = Array.isArray(value) ? value.join(', ') : value;
      await route.fulfill({ status: result.status, headers, body: result.body });
    } catch { await route.abort(); } finally { inflight--; }
  });
  await context.routeWebSocket('**/*', socket => socket.close());
}

module.exports = { isPublic, safeUrl, publicLookup, fetchPublic, protectContext };

/** 在无网络测试容器内验证真实Vite代理；网关仅为验签回显替身，不声明业务验收。 */
import assert from 'node:assert/strict';
import { generateKeyPairSync, verify } from 'node:crypto';
import { once } from 'node:events';
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawn } from 'node:child_process';

const origin = 'http://127.0.0.1:28086';
const directory = mkdtempSync(join(tmpdir(), 'agentic-proxy-'));
const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
mkdirSync(join(directory, '.local'));
writeFileSync(join(directory, '.local/iam-test-private.pem'),
  privateKey.export({ type: 'pkcs8', format: 'pem' }), { mode: 0o600 });
let forwarded = 0;
const gateway = createServer((request, response) => {
  try {
    assert.equal(request.headers['x-internal-token'], undefined);
    assert.equal(request.headers['x-tenant-id'], undefined);
    const [header, payload, signature] = request.headers.authorization.slice(7).split('.');
    assert(verify('RSA-SHA256', Buffer.from(`${header}.${payload}`), publicKey,
      Buffer.from(signature, 'base64url')));
    const identity = JSON.parse(Buffer.from(payload, 'base64url').toString());
    assert.equal(identity.tenant_id, 'local-dev');
    assert.equal(identity.sub, 'workbench-user');
    forwarded++;
    response.writeHead(202, { 'Content-Type': 'application/json' });
    response.end(JSON.stringify({ path: request.url, method: request.method }));
  } catch {
    response.writeHead(500).end('proxy identity assertion failed');
  }
});
gateway.listen(28080, '127.0.0.1');
await once(gateway, 'listening');
const web = spawn(process.execPath, ['node_modules/vite/bin/vite.js', 'preview',
  '--config', 'docker.config.ts', '--configLoader', 'runner'], {
  cwd: '/app', env: { ...process.env, AGENTIC_BACKEND_DIR: directory }, stdio: 'inherit',
});
const exited = once(web, 'exit');
try {
  const deadline = Date.now() + 15000;
  let html;
  while (Date.now() < deadline) {
    assert.equal(web.exitCode, null, 'Vite must remain alive');
    try {
      const response = await fetch(origin, { signal: AbortSignal.timeout(1000) });
      if (response.ok) { html = await response.text(); break; }
    } catch { /* 启动尚未监听时在总期限内等待。 */ }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  assert(html?.includes('<div id="root">'), 'production HTML is reachable');
  const asset = html.match(/src="(\/assets\/[^\"]+\.js)"/)[1];
  assert.equal((await fetch(origin + asset, { signal: AbortSignal.timeout(3000) })).status, 200);
  const headers = { Origin: origin, 'X-Workbench-Request': '1',
    'Content-Type': 'application/json', Authorization: 'Bearer attacker',
    'X-Internal-Token': 'attacker', 'X-Tenant-Id': 'attacker' };
  const routes = [
    ['GET', '/api/v2/knowledge/retrieval-capabilities'],
    ['POST', '/api/v2/knowledge/search'],
    ['POST', '/api/v2/knowledge/citations/resolve'],
    ['POST', '/api/v2/knowledge/evaluations/runs'],
    ['POST', '/api/v2/tasks'],
    ['POST', '/api/v2/conversations/11111111-1111-4111-8111-111111111111/turns'],
    ['GET', '/api/v1/tasks'],
  ];
  for (const [method, path] of routes) {
    const response = await fetch(origin + path, { method, headers,
      ...(method === 'POST' ? { body: '{}' } : {}), signal: AbortSignal.timeout(3000) });
    assert.equal(response.status, 202, path);
    assert.deepEqual(await response.json(), { path, method });
  }
  assert.equal(forwarded, routes.length);
  for (const [path, extra] of [['/api/v2/tasks/private', {}],
    ['/api/v2/tasks', { Origin: 'https://untrusted.example' }]]) {
    const response = await fetch(origin + path, { method: 'POST',
      headers: { ...headers, ...extra }, body: '{}', signal: AbortSignal.timeout(3000) });
    assert.equal(response.status, 403);
  }
  assert.equal(forwarded, routes.length, 'rejected routes must not reach gateway');
  console.log(JSON.stringify({ status: 'PASS', routes: routes.length,
    identity: 'ephemeral test RSA key; no host credentials', externalNetwork: false }));
} finally {
  web.kill('SIGTERM');
  await exited;
  gateway.close();
  await once(gateway, 'close');
}

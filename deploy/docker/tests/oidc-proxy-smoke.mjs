/** 无网络容器内核对正式模式代理，禁止恢复本地签名身份；不声明真实IAM验收。 */
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
const origin = 'http://127.0.0.1:28086';
let forwarded = 0;
const gateway = createServer((request, response) => {
  assert.equal(request.headers.authorization, undefined);
  assert.equal(request.headers.cookie, 'SESSION=opaque-test');
  assert.equal(request.headers['x-csrf-token'], 'csrf-test');
  forwarded++;
  response.writeHead(401, { 'Content-Type': 'application/json' });
  response.end(JSON.stringify({ path: request.url }));
});
gateway.listen(28080, '127.0.0.1');
await once(gateway, 'listening');
const web = spawn(process.execPath, ['node_modules/vite/bin/vite.js', 'preview',
  '--config', 'docker.config.ts', '--configLoader', 'runner'], {
  cwd: '/app', env: { ...process.env, VITE_IDENTITY_MODE: 'oidc', AGENTIC_BACKEND_DIR: '/missing-no-private-key' }, stdio: 'inherit',
});
const exited = once(web, 'exit');
try {
  const deadline = Date.now() + 15000;
  let ready = false;
  while (Date.now() < deadline) {
    assert.equal(web.exitCode, null);
    try { ready = (await fetch(origin, { signal: AbortSignal.timeout(1000) })).ok; } catch { /* 有界等待监听 */ }
    if (ready) break;
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  assert(ready);
  for (const path of ['/api/v1/session', '/api/local-session', '/api/v2/knowledge/documents',
    '/api/v4/tasks/capabilities', '/api/v3/conversations', '/oauth2/authorization/workbench', '/login/oauth2/code/workbench']) {
    const response = await fetch(origin + path, { headers: { Cookie: 'SESSION=opaque-test', 'X-CSRF-TOKEN': 'csrf-test' }, signal: AbortSignal.timeout(3000) });
    assert.equal(response.status, 401);
    assert.equal((await response.json()).path, path);
  }
  assert.equal(forwarded, 7);
  console.log('OIDC_PROXY_PASS: 7 routes, cookie/CSRF preserved, no local signing key');
} finally {
  web.kill('SIGTERM'); await exited; gateway.close(); await once(gateway, 'close');
}

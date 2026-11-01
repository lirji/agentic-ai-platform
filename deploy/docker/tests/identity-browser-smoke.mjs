/** 真实本机Docker身份只读验收；凭据仅从既有0600文件读取，不录trace。 */
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
const root = process.cwd();
const require = createRequire(resolve(root, '../agentic-ai-platform-web/package.json'));
const { chromium } = require('@playwright/test');
const origin = 'http://localhost:28086';
const identity = JSON.parse(readFileSync('.local/identity/state.json', 'utf8'));
const qa = 'docs/deployment/docker-identity-20261101';
mkdirSync(qa, { recursive: true });
const legacy = JSON.parse(readFileSync('.local/docker-identity-20261101/legacy-document.json', 'utf8'));
const checks = {};
const browser = await chromium.launch({ headless: true });
async function login(page, name) {
  await page.goto(origin + '/knowledge');
  await page.getByRole('button', { name: '登录个人空间' }).click();
  await page.waitForURL('http://localhost:18090/**');
  await page.locator('#username').fill(name);
  await page.locator('#password').fill(identity.users[name].password);
  await page.getByRole('button', { name: 'Sign In', exact: true }).click();
  await page.waitForURL(origin + '/**');
}
try {
  const anonymous = await browser.newContext({ baseURL: origin });
  for (const path of ['/api/v1/session', '/api/local-session', '/api/v1/knowledge/documents?limit=1']) {
    assert.equal((await anonymous.request.get(path, { headers: { Authorization: 'Bearer forged', 'X-User-Id': 'victim', 'X-Tenant-Id': 'local-dev' } })).status(), 401);
  }
  checks.anonymousAndLegacyIdentityRejected = true;
  const page = await anonymous.newPage();
  await page.goto('/knowledge');
  await page.getByRole('button', { name: '登录个人空间' }).waitFor();
  await page.screenshot({ path: qa + '/login.png' });
  await anonymous.close();
  for (const name of ['agentic-user', 'agentic-peer']) {
    const context = await browser.newContext({ baseURL: origin, viewport: { width: 1440, height: 1000 } });
    const page = await context.newPage();
    await login(page, name);
    await page.getByRole('button', { name: '退出工作台' }).waitFor();
    const session = await context.request.get('/api/v1/session');
    assert.equal(session.status(), 200);
    const current = await session.json();
    assert.equal(current.userId, identity.users[name].id);
    assert.equal(current.tenantId, 'local-agentic');
    assert(session.headers()['cache-control'].includes('no-store'));
    const cookies = await context.cookies(origin);
    const cookie = cookies.find(c => c.name === 'AGENTIC_SESSION');
    assert(cookie.httpOnly && cookie.sameSite === 'Lax');
    for (const path of ['/api/v2/knowledge/documents?limit=1', '/api/v1/conversations?limit=1',
      '/api/v1/tasks?kind=RESEARCH&limit=1', '/api/v1/tasks?kind=READONLY_ASSISTANT&limit=1',
      '/api/v1/knowledge/evaluations/datasets?limit=1', '/api/v2/knowledge/retrieval-capabilities',
      '/api/v4/tasks/capabilities', '/api/v2/conversations/capabilities']) {
      assert.equal((await context.request.get(path)).status(), 200, path);
    }
    assert.equal((await context.request.post('/api/v1/auth/logout')).status(), 403);
    assert.equal((await context.request.get('/api/v1/knowledge/documents/' + legacy.id, { headers: { 'X-Tenant-Id': 'local-dev', 'X-User-Id': 'workbench-user' } })).status(), 404);
    if (name === 'agentic-user') {
      for (const path of ['/knowledge', '/chat', '/research', '/assistants', '/quality']) {
        await page.goto(path);
        await page.getByRole('button', { name: '退出工作台' }).waitFor();
      }
      await page.goto('/knowledge');
      await page.getByRole('heading', { name: '知识资料', exact: true }).waitFor();
      await page.screenshot({ path: qa + '/workspace-desktop.png' });
      await page.setViewportSize({ width: 414, height: 896 });
      await page.waitForTimeout(300);
      assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
      await page.screenshot({ path: qa + '/workspace-mobile.png' });
    }
    await page.getByRole('button', { name: '退出工作台' }).click();
    await page.getByRole('button', { name: '登录个人空间' }).waitFor();
    assert.equal((await context.request.get('/api/v1/session', { headers: { Cookie: 'AGENTIC_SESSION=' + cookie.value } })).status(), 401);
    checks[name] = { login: true, session: true, readonlyApis: 8, csrf: true, logoutRevokesSession: true, legacyTenantIsolated: true };
    await context.close();
  }
  const denied = await browser.newContext({ baseURL: origin });
  await login(await denied.newPage(), 'agentic-unassigned');
  assert.equal((await denied.request.get('/api/v1/session')).status(), 401);
  checks.unassignedMemberRejected = true;
  await denied.close();
  writeFileSync(qa + '/browser-result.json', JSON.stringify({ status: 'PASS', origin, checks, paidCalls: 0, writes: 'login/logout only; no business writes' }, null, 2) + '\n');
  console.log('DOCKER_IDENTITY_BROWSER_PASS');
} finally { await browser.close(); }

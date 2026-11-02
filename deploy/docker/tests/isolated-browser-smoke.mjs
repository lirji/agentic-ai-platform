/** 真实隔离Docker页面和只读API；不上传资料、不发送问题或启用模型调用。 */
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";

const runId = process.argv[2];
assert.match(runId ?? "", /^[a-f0-9]{8}$/);
const root = process.cwd();
const state = JSON.parse(
  readFileSync(`.local/release-validation/${runId}/state.json`, "utf8"),
);
assert.equal(state.phase, "RUNNING");
const require = createRequire(
  resolve(root, "../agentic-ai-platform-web/package.json"),
);
const { chromium } = require("@playwright/test");
const origin = "http://127.0.0.1:28096";
const evidence = "docs/qa/isolated-release-validation-20261102";
mkdirSync(evidence, { recursive: true });
const browser = await chromium.launch({ headless: true });
const checks = {};
try {
  const context = await browser.newContext({
    baseURL: origin,
    viewport: { width: 1440, height: 1000 },
  });
  const page = await context.newPage();
  const errors = [];
  page.on('response', (response) => {
    if (response.status() >= 400 && response.url().startsWith(origin))
      errors.push(`${response.status()} ${new URL(response.url()).pathname}`);
  });
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("console", (message) => {
    if (message.type() === "error") errors.push(message.text());
  });
  await page.goto("/knowledge");
  await page.getByRole("heading", { name: "知识资料", exact: true }).waitFor();
  await page.getByText("还没有上传资料", { exact: true }).waitFor();
  const api = await page.evaluate(async () => {
    const response = await fetch("/api/v2/knowledge/documents?limit=1", {
      headers: { "X-Workbench-Request": "1" },
    });
    return { status: response.status, body: await response.json() };
  });
  assert.equal(api.status, 200);
  assert.equal(api.body.items.length, 0);
  await page.screenshot({ path: `${evidence}/knowledge.png`, fullPage: true });
  checks.knowledgePageAndRealApi = true;
  await page.goto("/chat");
  // 链接同时展示更新时间，按可访问名称匹配标题部分。
  const synthetic = page
    .getByRole("link", { name: /S17 隔离重启验证/ })
    .first();
  await synthetic.waitFor();
  await synthetic.click();
  await page.waitForURL(/\/chat\/[a-f0-9-]+$/);
  const conversationUrl = page.url();
  await page.reload();
  await page
    .getByRole("link", { name: /S17 隔离重启验证/ })
    .first()
    .waitFor();
  assert.equal(page.url(), conversationUrl);
  await page.screenshot({
    path: `${evidence}/conversation.png`,
    fullPage: true,
  });
  checks.persistedConversationAndReload = true;
  assert.deepEqual(errors, []);
  checks.noBrowserErrors = true;
  await context.close();
  writeFileSync(
    `${evidence}/browser-result.json`,
    JSON.stringify({ runId, status: "PASS", checks }, null, 2) + "\n",
  );
} finally {
  await browser.close();
}

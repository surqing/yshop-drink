/** Registered real Vue/Chromium acceptance. Owned loopback fixture only; no vendor or production API. */
const fs = require("fs"),
  path = require("path"),
  cp = require("child_process"),
  net = require("net"),
  crypto = require("crypto");
const repo = path.resolve(__dirname, "../.."),
  root = path.resolve(process.argv[2]),
  runId =
    process.env.YSHOP_QUALITY_RUN_ID || crypto.randomUUID().replaceAll("-", ""),
  owner = crypto.randomUUID().replaceAll("-", "");
let port, baseUrl;
if (
  root.startsWith(repo + path.sep) &&
  !root.startsWith(path.join(repo, ".quality") + path.sep)
)
  throw Error("PRIVATE_OUTPUT_REQUIRED");
fs.mkdirSync(root, { recursive: false, mode: 0o700 });
if (!process.env.PLAYWRIGHT_BROWSERS_PATH) {
  for (
    let folder = repo;
    folder !== path.dirname(folder);
    folder = path.dirname(folder)
  ) {
    const cache = path.join(folder, ".dev-tools/cache/playwright");
    if (fs.existsSync(cache)) {
      process.env.PLAYWRIGHT_BROWSERS_PATH = cache;
      break;
    }
  }
}
const { chromium, expect } = require(
  path.join(repo, "yshop-drink-vue3/node_modules/@playwright/test"),
);
const identity = () =>
  JSON.parse(
    cp.execFileSync(
      "python3",
      [
        "-c",
        'import sys,json;sys.path.insert(0,"tests/quality");from evidence import source_identity;print(json.dumps(source_identity()))',
      ],
      { cwd: repo, encoding: "utf8" },
    ),
  );
const source = identity(),
  startedAt = Date.now() / 1000,
  checks = [],
  requests = [],
  apiEvents = [];
let blockedExternalRequestCount = 0,
  pageErrorCount = 0,
  consoleErrorCount = 0,
  currentStage = "runner.startup",
  currentStageStartedAt = Date.now(),
  injectedTimeoutUsed = false;
const injectedTimeoutStage =
  process.env.YSHOP_PRINTER_BROWSER_TEST_INJECT_TIMEOUT === "test-only"
    ? process.env.YSHOP_PRINTER_BROWSER_INJECT_TIMEOUT_STAGE
    : null;
const diagnosticSentinel =
  process.env.YSHOP_PRINTER_BROWSER_DIAGNOSTIC_SENTINEL ||
  "r4-diagnostic-marker";
const report = {
  runId,
  owner,
  ...source,
  startedAt,
  result: "FAIL",
  cleanup: "NOT_RUN",
  scope:
    "Real Vue printer SFC in Chromium with synthetic loopback HTTP fixture; not full backend/router/browser acceptance",
  checks,
  startupOwnerCheck: { status: "NOT_RUN" },
  finalFixtureOwnerCheck: { status: "NOT_RUN" },
  realProviderRequests: 0,
  blockedExternalRequestCount: 0,
};
let server, browser, context, page, log;
let interrupted = false;
const interrupt = () => {
  interrupted = true;
  if (page) page.close().catch(() => {});
};
process.once("SIGINT", interrupt);
process.once("SIGTERM", interrupt);
const listening = () =>
  new Promise((resolve) => {
    const c = net.connect(port, "127.0.0.1");
    c.once("connect", () => {
      c.destroy();
      resolve(true);
    });
    c.once("error", () => resolve(false));
  });
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const check = async (name, fn) => {
  if (interrupted) throw Error("CONTROLLED_INTERRUPTED");
  await fn();
  checks.push(name);
};
const stage = async (name) => {
  currentStage = name;
  currentStageStartedAt = Date.now();
  if (injectedTimeoutStage === name && !injectedTimeoutUsed) {
    injectedTimeoutUsed = true;
    if (!page) throw Error("CONTROLLED_TIMEOUT_PAGE_NOT_READY");
    await page
      .getByTestId(diagnosticSentinel)
      .waitFor({ state: "visible", timeout: 250 });
  }
};
const failureUiState = async () => {
  const empty = {
    visibleDialogs: 0,
    visibleSelectDropdowns: 0,
    visibleLoadingMasks: 0,
    disabledButtons: 0,
    ariaBusyCount: 0,
    bindingSelectCount: 0,
    bindingOptionCount: 0,
    bindingButtonCount: 0,
    bindingButtonDisabled: null,
  };
  if (!page || page.isClosed()) return empty;
  const count = async (selector) => page.locator(selector).count().catch(() => 0);
  const bindingButton = page
    .locator(".el-dialog:visible")
    .getByRole("button", { name: "绑定", exact: true });
  const bindingButtonCount = await bindingButton.count().catch(() => 0);
  const bindingButtonDisabled = bindingButtonCount
    ? await bindingButton.first().isDisabled().catch(() => null)
    : null;
  return {
    visibleDialogs: await count(".el-dialog:visible"),
    visibleSelectDropdowns: await count(".el-select-dropdown:visible"),
    visibleLoadingMasks: await count(".el-loading-mask:visible"),
    disabledButtons: await count("button:disabled"),
    ariaBusyCount: await count('[aria-busy="true"]'),
    bindingSelectCount: await count(".el-dialog:visible .el-select"),
    bindingOptionCount: await page.getByRole("option").count().catch(() => 0),
    bindingButtonCount,
    bindingButtonDisabled,
  };
};
const safeApiPath = (pathname) =>
  /^\/admin-api\/store\/printer\/(providers|shops|page|get|create|update|binding|authorize|reconnect|deauthorize|status|status\/refresh|test-print|test-print\/get|test-print\/page|delete)$/.test(
    pathname,
  );
const recordApiEvent = (request, status, failed) => {
  const url = new URL(request.url());
  if (url.hostname !== "127.0.0.1" || !safeApiPath(url.pathname)) return;
  apiEvents.push({
    method: request.method(),
    path: url.pathname,
    status,
    failed,
  });
};
async function fixture() {
  return (await fetch(baseUrl + "/__printer-fixture")).json();
}
async function confirm() {
  const box = page.locator(".el-message-box");
  await box.waitFor();
  await box.getByRole("button", { name: /^(确定|确认)$/ }).click();
  await box.waitFor({ state: "hidden" });
}
async function main() {
  try {
    if (process.env.YSHOP_PRINTER_BROWSER_PORT) {
      port = Number(process.env.YSHOP_PRINTER_BROWSER_PORT);
      if (!Number.isInteger(port) || port < 1 || port > 65535)
        throw Error("INVALID_LOOPBACK_PORT");
    } else
      port = await new Promise((resolve, reject) => {
        const probe = net.createServer();
        probe.once("error", reject);
        probe.listen(0, "127.0.0.1", () => {
          const chosen = probe.address().port;
          probe.close((error) => (error ? reject(error) : resolve(chosen)));
        });
      });
    baseUrl = "http://127.0.0.1:" + port;
    if (
      (process.env.YSHOP_QUALITY_SOURCE_SHA &&
        process.env.YSHOP_QUALITY_SOURCE_SHA !== source.sourceSha) ||
      (process.env.YSHOP_QUALITY_SOURCE_DIGEST &&
        process.env.YSHOP_QUALITY_SOURCE_DIGEST !== source.sourceDigest)
    )
      throw Error("SOURCE_IDENTITY_MISMATCH");
    if (await listening()) throw Error("PREVIEW_PORT_ALREADY_OWNED");
    log = fs.openSync(path.join(root, "preview-private.log"), "w", 0o600);
    server = cp.spawn("node", ["tests/printer/preview.mjs"], {
      cwd: path.join(repo, "yshop-drink-vue3"),
      env: {
        ...process.env,
        YSHOP_PRINTER_BROWSER_PORT: String(port),
        YSHOP_PRINTER_BROWSER_OWNER: owner,
      },
      stdio: ["ignore", log, log],
      detached: true,
    });
    for (let i = 0; i < 120; i++) {
      if (interrupted) throw Error("CONTROLLED_INTERRUPTED");
      if (server.exitCode !== null) throw Error("PREVIEW_EXITED");
      try {
        const r = await fetch(baseUrl + "/__printer-fixture");
        if (r.ok) {
          const body = await r.json();
          const ownerMatched = body.owner === owner;
          report.startupOwnerCheck = {
            status: ownerMatched ? "PASS" : "FAIL",
            ownerMatched,
          };
          if (!ownerMatched) throw Error("PREVIEW_OWNER_MISMATCH");
          break;
        }
      } catch (error) {
        if (error.message === "PREVIEW_OWNER_MISMATCH") throw error;
      }
      if (i === 119) throw Error("PREVIEW_START_TIMEOUT");
      await sleep(500);
    }
    browser = await chromium.launch({ headless: true });
    context = await browser.newContext({
      viewport: { width: 1600, height: 1100 },
    });
    page = await context.newPage();
    await page.route("**/*", async (route) => {
      const url = new URL(route.request().url());
      if (
        ["http:", "https:"].includes(url.protocol) &&
        !["127.0.0.1", "localhost"].includes(url.hostname)
      ) {
        blockedExternalRequestCount += 1;
        await route.abort();
        return;
      }
      await route.continue();
    });
    page.on("response", (response) => {
      recordApiEvent(response.request(), response.status(), false);
    });
    page.on("requestfailed", (request) => {
      recordApiEvent(request, null, true);
    });
    page.on("pageerror", () => {
      pageErrorCount += 1;
    });
    page.on("console", (message) => {
      if (message.type() === "error") consoleErrorCount += 1;
    });
    page.on("request", (r) => {
      if (r.url().includes("/admin-api/store/printer/"))
        requests.push({ method: r.method(), path: new URL(r.url()).pathname });
    });
    await stage("page.load");
    await page.goto(baseUrl + "/__printer-preview", {
      waitUntil: "networkidle",
    });
    await stage("check.unconfigured-service-local-management");
    await check("unconfigured-service-local-management", () =>
      expect(
        page.getByText(
          "打印服务尚未配置，可先维护设备和门店绑定。连接设备、状态查询和测试打印需配置易联云应用。",
        ),
      ).toBeVisible(),
    );
    await stage("printer.create.open-dialog");
    await page.getByRole("button", { name: "新增打印机", exact: true }).click();
    let dialog = page.locator(".el-dialog:visible");
    await stage("printer.create.fill-form");
    await dialog
      .locator(".el-form-item")
      .filter({ hasText: "设备名称" })
      .locator("input")
      .fill("owned-browser-device");
    await dialog
      .locator(".el-form-item")
      .filter({ hasText: "设备编号" })
      .locator("input")
      .fill("owned-browser-code");
    await stage("printer.create.submit");
    await dialog.getByRole("button", { name: "保存", exact: true }).click();
    let row = page
      .locator(".el-table__body-wrapper tr")
      .filter({ hasText: "owned-browser-device" });
    await stage("check.create-real-form");
    await check("create-real-form", () => expect(row).toBeVisible());
    await stage("check.unconfigured-connect-disabled");
    await check("unconfigured-connect-disabled", () =>
      expect(
        row.getByRole("button", { name: "连接设备", exact: true }),
      ).toBeDisabled(),
    );
    await stage("printer.edit.open-dialog");
    await row.getByRole("button", { name: "编辑", exact: true }).click();
    dialog = page.locator(".el-dialog:visible");
    await stage("printer.edit.fill-form");
    await dialog
      .locator(".el-form-item")
      .filter({ hasText: "设备名称" })
      .locator("input")
      .fill("owned-browser-edited");
    await stage("printer.edit.submit");
    await dialog.getByRole("button", { name: "保存", exact: true }).click();
    row = page
      .locator(".el-table__body-wrapper tr")
      .filter({ hasText: "owned-browser-edited" });
    await stage("check.edit-real-form");
    await check("edit-real-form", () => expect(row).toBeVisible());
    await stage("printer.binding.open-dialog");
    await row.getByRole("button", { name: "绑定门店", exact: true }).click();
    dialog = page.locator(".el-dialog:visible");
    await stage("printer.binding.open-shop-dropdown");
    await dialog.locator(".el-select").click();
    await stage("printer.binding.select-shop");
    await page.getByRole("option", { name: "测试门店一", exact: true }).click();
    await stage("printer.binding.submit");
    await dialog.getByRole("button", { name: "绑定", exact: true }).click();
    await stage("check.binding-while-service-disabled");
    await check("binding-while-service-disabled", () =>
      expect(row).toContainText("测试门店一"),
    );
    await stage("fixture.enable-provider");
    await fetch(baseUrl + "/__printer-fixture?configured=1");
    await stage("page.reload-after-provider-config");
    await page.reload({ waitUntil: "networkidle" });
    row = page
      .locator(".el-table__body-wrapper tr")
      .filter({ hasText: "owned-browser-edited" });
    await stage("printer.authorize");
    await row.getByRole("button", { name: "连接设备", exact: true }).click();
    await stage("check.explicit-authorization");
    await check("explicit-authorization", () =>
      expect(row).toContainText("已连接"),
    );
    await stage("printer.reconnect");
    await row.getByRole("button", { name: "重新连接", exact: true }).click();
    await stage("printer.reconnect.confirm");
    await confirm();
    await stage("check.reconnect-keeps-binding");
    await check("reconnect-keeps-binding", () =>
      expect(row).toContainText("测试门店一"),
    );
    await stage("printer.status.refresh");
    await row.getByRole("button", { name: "刷新状态", exact: true }).click();
    await stage("check.status-paper-out");
    await check("status-paper-out", async () => {
      await expect(row).toContainText("缺纸");
      expect(
        requests.filter(
          (r) => r.method === "POST" && r.path.endsWith("/status/refresh"),
        ).length,
      ).toBe(1);
      expect(requests.filter((r) => r.path.endsWith("/status")).length).toBe(0);
    });
    await stage("printer.test-print.submit");
    await row.getByRole("button", { name: "测试打印", exact: true }).click();
    await stage("printer.test-print.confirm");
    await confirm();
    await stage("check.unknown-retains-request");
    await check("unknown-retains-request", () =>
      expect(
        row.getByRole("button", { name: "查询测试结果", exact: true }),
      ).toBeVisible(),
    );
    await stage("printer.test-print.lookup");
    await row
      .getByRole("button", { name: "查询测试结果", exact: true })
      .click();
    await stage("printer.test-print.lookup-confirm");
    await confirm();
    await stage("check.unknown-lookup-readonly");
    await check("unknown-lookup-readonly", async () => {
      const f = await fixture();
      expect(f.submitted).toBe(1);
      expect(f.jobCount).toBe(1);
      expect(
        requests.filter(
          (r) => r.method === "POST" && r.path.endsWith("/test-print"),
        ).length,
      ).toBe(1);
      expect(
        requests.filter(
          (r) => r.method === "GET" && r.path.endsWith("/test-print/get"),
        ).length,
      ).toBe(1);
    });
    await stage("printer.history.open");
    await row.getByRole("button", { name: "测试记录", exact: true }).click();
    dialog = page.locator(".el-dialog:visible");
    await stage("check.unknown-history-and-no-paper-claim");
    await check("unknown-history-and-no-paper-claim", async () => {
      await expect(dialog).toContainText("待确认");
      await expect(dialog).toContainText(
        "“已提交”表示打印服务已接受请求，请查看设备确认实际出纸。",
      );
      expect(await dialog.textContent()).not.toContain("已出纸");
    });
    await stage("printer.history.close");
    await dialog.locator(".el-dialog__header .is-hover").last().click();
    await stage("printer.binding.unbind");
    await row.getByRole("button", { name: "解绑门店", exact: true }).click();
    await stage("printer.binding.unbind-confirm");
    await confirm();
    await expect(row).toContainText("未绑定");
    await stage("printer.deauthorize");
    await row.getByRole("button", { name: "取消授权", exact: true }).click();
    await stage("printer.deauthorize.confirm");
    await confirm();
    await expect(
      row.getByRole("button", { name: "连接设备", exact: true }),
    ).toBeVisible();
    await stage("printer.delete");
    await row.getByRole("button", { name: "删除", exact: true }).click();
    await stage("printer.delete.confirm");
    await confirm();
    await expect(row).toHaveCount(0);
    await stage("printer.deleted-filter.open");
    await page
      .locator(".el-form-item")
      .filter({ hasText: "设备范围" })
      .locator(".el-select")
      .click();
    await stage("printer.deleted-filter.select");
    await page.getByRole("option", { name: "已删除", exact: true }).click();
    row = page
      .locator(".el-table__body-wrapper tr")
      .filter({ hasText: "owned-browser-edited" });
    await stage("check.deleted-device-has-history-only");
    await check("deleted-device-has-history-only", async () => {
      await expect(row).toBeVisible();
      await expect(
        row.getByRole("button", { name: "测试记录", exact: true }),
      ).toBeVisible();
      expect(await row.getByRole("button").count()).toBe(1);
    });
    await stage("printer.deleted-history.open");
    await row.getByRole("button", { name: "测试记录", exact: true }).click();
    await stage("check.deleted-device-history-remains-readable");
    await check("deleted-device-history-remains-readable", () =>
      expect(page.locator(".el-dialog:visible")).toContainText("待确认"),
    );
    await stage("artifact.history-screenshot");
    await page.screenshot({
      path: path.join(root, "printer-history.png"),
      fullPage: true,
    });
    await stage("fixture.final-read");
    const finalFixture = await fixture();
    const finalOwnerMatched = finalFixture.owner === owner;
    report.finalFixtureOwnerCheck = {
      status: finalOwnerMatched ? "PASS" : "FAIL",
      ownerMatched: finalOwnerMatched,
    };
    if (!finalOwnerMatched) throw Error("FINAL_FIXTURE_OWNER_MISMATCH");
    report.fixture = finalFixture;
    report.requests = requests;
    report.apiEvents = apiEvents;
    report.blockedExternalRequestCount = blockedExternalRequestCount;
    report.pageErrorCount = pageErrorCount;
    report.consoleErrorCount = consoleErrorCount;
    report.result = "PASS";
  } catch (error) {
    report.reasonType = ["Error", "AssertionError", "TimeoutError"].includes(
      error.name,
    )
      ? error.name
      : "Error";
    report.reasonCode = interrupted
      ? "CONTROLLED_INTERRUPTED"
      : "PRINTER_BROWSER_CHECK_FAILED";
    report.failedStage = currentStage;
    report.stageDurationMs = Math.max(0, Date.now() - currentStageStartedAt);
    report.lastPassedCheck = checks.length ? checks[checks.length - 1] : null;
    report.failureUiState = await failureUiState();
    report.requests = requests;
    report.apiEvents = apiEvents;
    report.blockedExternalRequestCount = blockedExternalRequestCount;
    report.pageErrorCount = pageErrorCount;
    report.consoleErrorCount = consoleErrorCount;
    fs.writeFileSync(
      path.join(root, "failure-private.log"),
      String(error.stack),
      { mode: 0o600 },
    );
    if (page)
      await page
        .screenshot({ path: path.join(root, "failure.png"), fullPage: true })
        .catch(() => {});
  } finally {
    const cleanupErrors = [];
    const closed = { contextClosed: !context, browserClosed: !browser };
    const serverExited = () =>
      !server || server.exitCode !== null || server.signalCode !== null;
    let portReleased = !server;
    for (const [name, resource] of [
      ["contextClosed", context],
      ["browserClosed", browser],
    ]) {
      if (resource) {
        try {
          await resource.close();
          closed[name] = true;
        } catch (error) {
          cleanupErrors.push(String(error.stack));
        }
      }
    }
    try {
      if (!serverExited()) {
        process.kill(-server.pid, "SIGTERM");
        for (let i = 0; i < 30 && !serverExited(); i++) await sleep(100);
        if (!serverExited()) {
          process.kill(-server.pid, "SIGKILL");
          await sleep(300);
        }
      }
      if (!serverExited()) throw Error("OWNED_SERVER_NOT_TERMINATED");
      if (server && (await listening())) throw Error("OWNED_PORT_NOT_RELEASED");
      portReleased = true;
    } catch (error) {
      cleanupErrors.push(String(error.stack));
    }
    if (cleanupErrors.length) {
      report.cleanup = "FAIL";
      report.result = "FAIL";
      report.cleanupReasonCode = "OWNED_BROWSER_CLEANUP_FAILED";
      fs.writeFileSync(
        path.join(root, "cleanup-private.log"),
        cleanupErrors.join("\n"),
        { mode: 0o600 },
      );
    } else report.cleanup = "PASS";
    if (log !== undefined) fs.closeSync(log);
    report.loopbackPort = port;
    report.resources = {
      owner,
      ...closed,
      previewPid: server?.pid || null,
      serverExited: serverExited(),
      portReleased,
      ownedLoopbackOnly: true,
      ownershipHandshakeChecked: report.finalFixtureOwnerCheck.status === "PASS",
    };
    report.sourceUnchanged =
      JSON.stringify(identity()) === JSON.stringify(source);
    if (!report.sourceUnchanged) report.result = "FAIL";
    report.scriptSha256 = crypto
      .createHash("sha256")
      .update(fs.readFileSync(__filename))
      .digest("hex");
    report.artifacts = fs
      .readdirSync(root)
      .filter((n) => n.endsWith(".png"))
      .map((n) => ({
        path: n,
        sha256: crypto
          .createHash("sha256")
          .update(fs.readFileSync(path.join(root, n)))
          .digest("hex"),
      }));
    report.endedAt = Date.now() / 1000;
    report.complete = true;
    process.removeListener("SIGINT", interrupt);
    process.removeListener("SIGTERM", interrupt);
    fs.writeFileSync(
      path.join(root, "report.json"),
      JSON.stringify(report, null, 2),
      { mode: 0o600 },
    );
    console.log(
      JSON.stringify({
        runId,
        result: report.result,
        checks: checks.length,
        cleanup: report.cleanup,
        sourceUnchanged: report.sourceUnchanged,
        reasonCode: report.reasonCode,
      }),
    );
    if (report.result !== "PASS") process.exitCode = 1;
  }
}
main();

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
  blocked = [];
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
  realProviderRequests: 0,
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
          if (body.owner !== owner) throw Error("PREVIEW_OWNER_MISMATCH");
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
        blocked.push(url.origin);
        await route.abort();
        return;
      }
      await route.continue();
    });
    page.on("request", (r) => {
      if (r.url().includes("/admin-api/store/printer/"))
        requests.push({ method: r.method(), path: new URL(r.url()).pathname });
    });
    await page.goto(baseUrl + "/__printer-preview", {
      waitUntil: "networkidle",
    });
    await check("unconfigured-service-local-management", () =>
      expect(
        page.getByText(
          "打印服务尚未配置，可先维护设备和门店绑定。连接设备、状态查询和测试打印需配置易联云应用。",
        ),
      ).toBeVisible(),
    );
    await page.getByRole("button", { name: "新增打印机", exact: true }).click();
    let dialog = page.locator(".el-dialog:visible");
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
    await dialog.getByRole("button", { name: "保存", exact: true }).click();
    let row = page
      .locator(".el-table__body-wrapper tr")
      .filter({ hasText: "owned-browser-device" });
    await check("create-real-form", () => expect(row).toBeVisible());
    await check("unconfigured-connect-disabled", () =>
      expect(
        row.getByRole("button", { name: "连接设备", exact: true }),
      ).toBeDisabled(),
    );
    await row.getByRole("button", { name: "编辑", exact: true }).click();
    dialog = page.locator(".el-dialog:visible");
    await dialog
      .locator(".el-form-item")
      .filter({ hasText: "设备名称" })
      .locator("input")
      .fill("owned-browser-edited");
    await dialog.getByRole("button", { name: "保存", exact: true }).click();
    row = page
      .locator(".el-table__body-wrapper tr")
      .filter({ hasText: "owned-browser-edited" });
    await check("edit-real-form", () => expect(row).toBeVisible());
    await row.getByRole("button", { name: "绑定门店", exact: true }).click();
    dialog = page.locator(".el-dialog:visible");
    await dialog.locator(".el-select").click();
    await page.getByRole("option", { name: "测试门店一", exact: true }).click();
    await dialog.getByRole("button", { name: "绑定", exact: true }).click();
    await check("binding-while-service-disabled", () =>
      expect(row).toContainText("测试门店一"),
    );
    await fetch(baseUrl + "/__printer-fixture?configured=1");
    await page.reload({ waitUntil: "networkidle" });
    row = page
      .locator(".el-table__body-wrapper tr")
      .filter({ hasText: "owned-browser-edited" });
    await row.getByRole("button", { name: "连接设备", exact: true }).click();
    await check("explicit-authorization", () =>
      expect(row).toContainText("已连接"),
    );
    await row.getByRole("button", { name: "重新连接", exact: true }).click();
    await confirm();
    await check("reconnect-keeps-binding", () =>
      expect(row).toContainText("测试门店一"),
    );
    await row.getByRole("button", { name: "刷新状态", exact: true }).click();
    await check("status-paper-out", async () => {
      await expect(row).toContainText("缺纸");
      expect(
        requests.filter(
          (r) => r.method === "POST" && r.path.endsWith("/status/refresh"),
        ).length,
      ).toBe(1);
      expect(requests.filter((r) => r.path.endsWith("/status")).length).toBe(0);
    });
    await row.getByRole("button", { name: "测试打印", exact: true }).click();
    await confirm();
    await check("unknown-retains-request", () =>
      expect(
        row.getByRole("button", { name: "查询测试结果", exact: true }),
      ).toBeVisible(),
    );
    await row
      .getByRole("button", { name: "查询测试结果", exact: true })
      .click();
    await confirm();
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
    await row.getByRole("button", { name: "测试记录", exact: true }).click();
    dialog = page.locator(".el-dialog:visible");
    await check("unknown-history-and-no-paper-claim", async () => {
      await expect(dialog).toContainText("待确认");
      await expect(dialog).toContainText(
        "“已提交”表示打印服务已接受请求，请查看设备确认实际出纸。",
      );
      expect(await dialog.textContent()).not.toContain("已出纸");
    });
    await dialog.locator(".el-dialog__header .is-hover").last().click();
    await row.getByRole("button", { name: "解绑门店", exact: true }).click();
    await confirm();
    await expect(row).toContainText("未绑定");
    await row.getByRole("button", { name: "取消授权", exact: true }).click();
    await confirm();
    await expect(
      row.getByRole("button", { name: "连接设备", exact: true }),
    ).toBeVisible();
    await row.getByRole("button", { name: "删除", exact: true }).click();
    await confirm();
    await expect(row).toHaveCount(0);
    await page
      .locator(".el-form-item")
      .filter({ hasText: "设备范围" })
      .locator(".el-select")
      .click();
    await page.getByRole("option", { name: "已删除", exact: true }).click();
    row = page
      .locator(".el-table__body-wrapper tr")
      .filter({ hasText: "owned-browser-edited" });
    await check("deleted-device-has-history-only", async () => {
      await expect(row).toBeVisible();
      await expect(
        row.getByRole("button", { name: "测试记录", exact: true }),
      ).toBeVisible();
      expect(await row.getByRole("button").count()).toBe(1);
    });
    await row.getByRole("button", { name: "测试记录", exact: true }).click();
    await check("deleted-device-history-remains-readable", () =>
      expect(page.locator(".el-dialog:visible")).toContainText("待确认"),
    );
    await page.screenshot({
      path: path.join(root, "printer-history.png"),
      fullPage: true,
    });
    report.fixture = await fixture();
    report.requests = requests;
    report.blockedExternalOrigins = [...new Set(blocked)];
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
      ownershipHandshakeChecked:
        !!report.fixture && report.fixture.owner === owner,
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

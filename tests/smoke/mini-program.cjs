/* Real local runtime smoke; no login mocks, consent changes or payment calls. */
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const workspace = path.resolve(__dirname, '../../..');
const privateDev = path.join(workspace, '.uniapp-dev');
const automator = require(path.join(privateDev, 'automation/node_modules/miniprogram-automator'));
const reportPath = path.join(privateDev, 'logs/baseline-smoke.json');
const checkpointPath = path.join(privateDev, 'logs/baseline-smoke-order.json');
const attemptedPath = path.join(privateDev, 'logs/baseline-smoke-order-attempt.json');
const report = { started: new Date().toISOString(), checks: [], uncaughtExceptions: 0, paymentRequests: 0 };
function save() { fs.writeFileSync(reportPath, JSON.stringify(report, null, 2), { mode: 0o600 }); }
async function waitFor(page, selector) {
  const deadline = Date.now() + 15000;
  while (Date.now() < deadline) {
    if (await page.$(selector)) return;
    await page.waitFor(200);
  }
  report.failureSelector = selector;
  throw new Error('Expected page element did not appear');
}
async function check(name, action) {
  report.stage = name;
  save();
  const result = await action();
  const ok = result?.ok !== false;
  report.checks.push({ name, ok, result }); save();
  if (!ok) throw new Error('Smoke check failed');
  return result;
}
function serverCheck(input) {
  const child = spawnSync('/usr/bin/python3', [path.join(__dirname, 'server-check.py')], {
    input: JSON.stringify(input), encoding: 'utf8', timeout: 15000
  });
  if (child.status !== 0) throw new Error('Private server verification failed');
  return JSON.parse(child.stdout);
}
async function run() {
  if (process.argv.includes('--new-order') && fs.existsSync(checkpointPath)) {
    throw new Error('An existing order checkpoint requires review before another order is allowed');
  }
  if (fs.existsSync(attemptedPath) && !fs.existsSync(checkpointPath)) {
    throw new Error('An earlier order attempt requires read-only server reconciliation before retrying');
  }
  report.stage = 'server audit marker';
  const marker = serverCheck({ action: 'marker' }).marker;
  report.stage = 'automation connection';
  // The official CLI returns before its WebSocket listener becomes available.
  let mini;
  const connectDeadline = Date.now() + 15000;
  while (!mini) {
    try { mini = await automator.connect({ wsEndpoint: 'ws://127.0.0.1:9420' }); }
    catch (error) {
      if (Date.now() >= connectDeadline) throw error;
      await new Promise(resolve => setTimeout(resolve, 500));
    }
  }
  mini.on('exception', () => { report.uncaughtExceptions++; save(); });
  mini.on('console', event => {
    // Events remain in memory: they can contain SDK/user values from legacy code.
    if (/\[api\].*POST.*\/order\/pay/.test(JSON.stringify(event))) report.paymentRequests++;
  });
  try {
    // CLI auto can return before the initial App launch is rendered.
    const readyDeadline = Date.now() + 15000;
    report.stage = 'initial App readiness';
    let initialPage;
    while (Date.now() < readyDeadline) {
      try { initialPage = await mini.currentPage(); } catch { /* Tool is still launching. */ }
      if (initialPage?.path) break;
      await new Promise(resolve => setTimeout(resolve, 200));
    }
    if (!initialPage?.path) throw new Error('Mini-program launch timed out');
    await new Promise(resolve => setTimeout(resolve, 500));
    await check('persisted real login', () => mini.evaluate(() => ({
      ok: !!wx.getStorageSync('accessToken') && !!wx.getStorageSync('userinfo'),
      tokenPresent: !!wx.getStorageSync('accessToken'), memberPresent: !!wx.getStorageSync('userinfo')
    })));
    let page = await mini.reLaunch('/pages/index/index');
    await waitFor(page, '.index-page');
    await check('homepage', async () => ({ ok: !!await page.$('.index-page'), path: page.path }));
    await check('current member', () => mini.evaluate(() => new Promise(resolve => wx.request({
      url: 'http://127.0.0.1:48081/app-api/member/user/get-info',
      header: { Authorization: 'Bearer ' + wx.getStorageSync('accessToken') },
      success: r => resolve({ ok: r.data?.code === 0 && !!r.data?.data?.id, code: r.data?.code }),
      fail: () => resolve({ ok: false, networkError: true })
    }))));
    page = await mini.navigateTo('/pages-checkout/shop/shop');
    await waitFor(page, '.shop-page__item');
    // Match the actual API store name to its rendered card. Vue script-setup
    // bindings are not exposed on $vm, so do not depend on private VM internals.
    const targetName = await mini.evaluate(() => new Promise(resolve => wx.request({
      url: 'http://127.0.0.1:48081/app-api/store/list',
      header: { Authorization: 'Bearer ' + wx.getStorageSync('accessToken') },
      data: { lat: 0, lng: 0, kw: '', shop_id: 0 },
      success: r => resolve(r.data?.code === 0 ? r.data.data?.find(x => Number(x.id) === 2)?.name : null),
      fail: () => resolve(null)
    })));
    const stores = await page.$$('.shop-page__item');
    let target;
    for (const store of stores) {
      if (targetName && (await store.text()).includes(targetName)) { target = store; break; }
    }
    await check('seed store 2 exists', async () => ({ ok: !!target }));
    await (await target.$('.shop-page__body')).tap(); await page.waitFor(700);
    page = await mini.currentPage();
    await waitFor(page, '.good');
    await check('product list', async () => ({ ok: (await page.$$('.good')).length > 0,
      count: (await page.$$('.good')).length, path: page.path }));
    await (await page.$('.property_btn')).tap(); await page.waitFor(350);
    await check('product detail/specification', async () => ({ ok: !!await page.$('.add-to-cart-btn') }));
    const before = await mini.evaluate(() => (wx.getStorageSync('cart') || []).reduce((sum, row) => sum + row.number, 0));
    await (await page.$('.add-to-cart-btn')).tap(); await page.waitFor(350);
    await check('add to cart through page', () => mini.evaluate(previous => {
      const count = (wx.getStorageSync('cart') || []).reduce((sum, row) => sum + row.number, 0);
      return { ok: count > previous, count };
    }, before));
    page = await mini.navigateTo('/pages-checkout/cart/cart');
    await waitFor(page, '.cart-item');
    await check('cart page', async () => ({ ok: (await page.$$('.cart-item')).length > 0, path: page.path }));
    let created;
    if (fs.existsSync(checkpointPath)) {
      created = JSON.parse(fs.readFileSync(checkpointPath, 'utf8'));
      const state = serverCheck({ action: 'checkpoint', orderId: created.orderId });
      report.checkpointState = state;
      save();
      if (!state.found || state.paid !== 0) throw new Error('Checkpoint requires read-only reconciliation');
      if (state.deleted) {
        if (!process.argv.includes('--replace-deleted-order')) throw new Error('Deleted checkpoint requires explicit replacement flag');
        // Only a verified unpaid, logically deleted order may be replaced.
        // Preserve both records, never undelete the database order or silently
        // retry an ambiguous create request. At most one replacement is sent.
        const archived = path.join(privateDev, 'logs', 'baseline-smoke-order-archived-' + created.orderId + '.json');
        if (fs.existsSync(archived)) throw new Error('Checkpoint archive already exists');
        fs.renameSync(checkpointPath, archived);
        if (fs.existsSync(attemptedPath)) {
          fs.renameSync(attemptedPath, archived.replace('.json', '-attempt.json'));
        }
        report.replacedDeletedCheckpoint = true;
        created = null;
      } else {
        report.reusedOrderCheckpoint = true;
      }
    }
    if (!created) {
      // Record before sending: an ambiguous timeout must not create a duplicate
      // order when this script is run again. Reconcile using the audit/database.
      fs.writeFileSync(attemptedPath, JSON.stringify({ started: new Date().toISOString(), marker }), { mode: 0o600 });
      created = await check('create exactly one unpaid order', () => mini.evaluate(() => new Promise(resolve => {
        const cart = wx.getStorageSync('cart'), member = wx.getStorageSync('userinfo');
        if (!Array.isArray(cart) || !cart.length || !member) return resolve({ ok: false });
        wx.request({ url: 'http://127.0.0.1:48081/app-api/order/create', method: 'POST',
          header: { Authorization: 'Bearer ' + wx.getStorageSync('accessToken') },
          data: { orderType: 'takein', addressId: '0', shopId: '2', mobile: member.mobile || '',
            gettime: 5, payType: 'weixin', remark: 'baseline smoke: unpaid local test',
            productId: cart.map(row => String(row.id)), spec: cart.map(row => row.valueStr.replace(/,/g, '|')),
            number: cart.map(() => '1'), couponId: '0' },
          success: r => resolve({ ok: r.data?.code === 0 && !!r.data?.data?.orderId, code: r.data?.code,
            orderId: r.data?.data?.orderId }), fail: () => resolve({ ok: false, networkError: true })
        });
      })));
      fs.writeFileSync(checkpointPath, JSON.stringify(created), { mode: 0o600 });
    }
    await check('unpaid order detail API', () => mini.evaluate(orderId => new Promise(resolve => wx.request({
      url: 'http://127.0.0.1:48081/app-api/order/detail/' + orderId,
      header: { Authorization: 'Bearer ' + wx.getStorageSync('accessToken') },
      success: r => resolve({ ok: r.data?.code === 0 && r.data?.data?.paid === 0, code: r.data?.code, paid: r.data?.data?.paid }),
      fail: () => resolve({ ok: false, networkError: true })
    })), created.orderId));
    page = await mini.switchTab('/pages/order/order');
    await waitFor(page, '.order-item');
    await check('order list page', async () => ({ ok: (await page.$$('.order-item')).length > 0, path: page.path }));
    page = await mini.navigateTo('/pages-order/orders/detail?id=' + created.orderId);
    await waitFor(page, '.order-detail-page');
    await check('order detail page', async () => ({ ok: !!await page.$('.order-detail-page'), path: page.path }));
    await page.waitFor(1500); // Access audit is persisted asynchronously.
    await check('database unpaid and zero payment requests', async () => {
      const state = serverCheck({ action: 'verify', marker, orderId: created.orderId });
      return { ...state, ok: state.unpaidOrderRows === 1 && state.paymentRequests === 0 && report.paymentRequests === 0 };
    });
    await check('no uncaught runtime exceptions', async () => ({ ok: report.uncaughtExceptions === 0, count: report.uncaughtExceptions }));
    report.ok = true;
    report.stage = 'complete';
  } finally { save(); mini.disconnect(); }
}
run().then(() => console.log('PASS: real login, store, products, cart, unpaid order; payment requests=0.'))
  .catch(error => {
    report.ok = false;
    const message = String(error?.message || error);
    report.failureHints = ['ECONNREFUSED', 'ECONNRESET', 'WebSocket', 'timeout', 'closed', 'connect', 'connection', 'evaluate', 'log', 'SDK']
      .filter(hint => message.includes(hint));
    save();
    console.error('FAIL: inspect .uniapp-dev/logs/baseline-smoke.json (sanitized).');
    process.exitCode = 1;
  });

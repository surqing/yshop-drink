# 失败用例执行方式

按用户最新说明，后续逐项解决之前，本轮只保留测试步骤与原始结果。测试准备、执行、隔离和清理见 `tdd-red-tests.md`。

## D001 资料编辑可改为他人已绑定手机号并破坏其登录

当前 PR14 生产源码；专属隔离测试数据；支付/对账开关 false。真实 HTTP + MySQL，合成登录身份。

1. 隔离环境存在手机号不同的会员 A、B；正常登录 A。
2. A 调用 update-nickname，mobile 填 B 的号码。
3. 读取两会员手机号，并尝试 B 的正常密码登录。

预期：普通资料修改不改变已验证手机号；绑定仍唯一；B 仍可登录。

实际：接口 code=0；两个账号占用相同手机号；B 密码登录 code=500。

证据：`exploration-http-1/report.json`, `independent-http-2/report.json`

## D002 积分兑换多件商品只扣单件积分

当前 PR14 生产源码；专属隔离测试数据；支付/对账开关 false。真实 HTTP + MySQL，合成登录身份。

1. 合成会员有 1000 积分，商品单价 100 积分、库存 10。
2. 通过实际兑换接口提交数量 2。
3. 核对订单数量、总积分、会员积分与库存。

预期：总积分 200、会员剩余 800、库存 8。

实际：数量 2、库存 8，但总积分 100、会员剩余 900。

证据：`exploration-http-1/report.json`, `independent-http-2/report.json`

## D003 兑换库存不足仍生成已付积分订单

当前 PR14 生产源码；专属隔离测试数据；支付/对账开关 false。真实 HTTP + MySQL，合成登录身份。

1. 合成会员 1000 积分；兑换商品库存 1。
2. 提交兑换数量 2。
3. 核对返回、兑换订单、库存及积分。

预期：拒绝兑换，库存 1、积分 1000、订单 0。

实际：code=0、订单 1、积分 900；条件库存更新未成功，库存仍为 1。

证据：`exploration-http-1/report.json`, `independent-http-2/report.json`

## D004 积分兑换可复制并读取他人的收货地址

当前 PR14 生产源码；专属隔离测试数据；支付/对账开关 false。真实 HTTP + MySQL，合成登录身份。

1. 两个合成会员各有地址；A 不拥有 B 的地址。
2. A 提交兑换并填 B 的 addressId。
3. 用 A 的令牌读取生成的积分订单详情。

预期：在写入前拒绝非本人地址，订单及积分无副作用。

实际：code=0；生成的 A 订单含 B 的合成电话，A 的详情接口可读。

证据：`exploration-http-1/report.json`, `independent-http-2/report.json`

## D005 下架积分商品仍可兑换

当前 PR14 生产源码；专属隔离测试数据；支付/对账开关 false。真实 HTTP + MySQL，合成登录身份。

1. 合成积分商品 is_switch=0、库存充足、会员积分充足。
2. 直接提交该商品兑换。
3. 核对订单、积分和库存。

预期：拒绝下架商品，所有业务状态不变。

实际：code=0；订单 1、库存从 10 到 9、积分从 1000 到 900。

证据：`exploration-http-1/report.json`, `independent-http-2/report.json`

## D006 零数量兑换也会扣积分并创建订单

当前 PR14 生产源码；专属隔离测试数据；支付/对账开关 false。真实 HTTP + MySQL，合成登录身份。

1. 合成会员积分 1000、商品单价 100、库存 10。
2. 兑换 num=0。
3. 核对订单、积分、库存。

预期：正整数数量校验拒绝 0，所有状态不变。

实际：code=0、订单 1、库存仍 10、积分减少 100。

证据：`exploration-http-1/report.json`, `independent-http-2/report.json`

## D007 积分兑换流水余额记录为扣减前余额

当前 PR14 生产源码；专属隔离测试数据；支付/对账开关 false。真实 HTTP + MySQL，合成登录身份。

1. 合成会员 1000 积分；单件兑换需 100。
2. 完成单件兑换。
3. 比较最新积分支出流水余额与会员积分。

预期：会员与流水剩余余额均为 900，支出 100。

实际：会员积分 900、支出 100，但流水 balance=1000。

证据：`exploration-http-1/report.json`, `independent-http-2/report.json`

## D008 自取订单丢失提交的联系电话

当前 PR14 生产源码；专属隔离测试数据；支付/对账开关 false。真实 HTTP + MySQL，合成登录身份。

1. 已登录合成会员，正常门店与商品。
2. 提交 takein 订单，并携带合法 mobile。
3. 查询订单 user_phone；随后取消并核对清理。

预期：订单保存提交并校验后的联系电话。

实际：下单成功但 user_phone 为空。

证据：`exploration-http-1/report.json`, `independent-http-2/report.json`

## D009 优惠券详情读取失败后保存误改上一张券

当前 PR14 生产源码；专属隔离测试数据；支付/对账开关 false。实际 Vue/Element Plus + Chromium，显式合成接口。

1. 真实 Chromium 打开实际优惠券页面，合成 API 有 A、B 两张券。
2. 打开 A 后取消，再打开 B；B 详情响应业务 code=500。
3. 修改仍可编辑的名称并保存，检查请求目标 ID。

预期：B 加载失败时不能把修改提交给 A；关闭、清空或禁止保存均可。

实际：向 coupon/update 发出 id=A 的请求，名称为用户在 B 操作中输入的内容。

证据：`browser-red/result-attempt-3.json`

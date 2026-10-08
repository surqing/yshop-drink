# Phase 6C 优惠券与营销

单商家多门店共享会员；真实支付继续冻结。验收证据见 phase6c-verification.md；实际测试结果独立统计。

## 审计矩阵（实施前）
| 模块 | 状态 | 代码证据及处理 |
|---|---|---|
| Coupon 模板 CRUD | BROKEN / UNSAFE | shop_id CSV/0 被当单店查询；缺资源权限/金额时间校验；receive可被请求修改。保留实体和路径，统一验证/门店授权。 |
| AppCoupon receive | UNSAFE | 无锁读发行量/用户记录，插入后计数覆盖；忽略limit/启用/时间/积分。数据库模板锁+条件计数+实例/请求幂等同事务。 |
| CouponUser 管理 | UNSAFE | list/page注解被注释；任意CRUD可篡改用户/面额/状态。恢复注解，资源范围约束，关闭原始写入口。 |
| 领取与使用时间 | PARTIAL | 现有start/end为使用期；新增可空claim_start/end，旧模板回落原使用期。 |
| 发行权益 | REUSABLE | CouponUser复制面额/门槛/范围/时间/type；扩充版本证据，禁止普通CRUD更改发行权益。 |
| 门店 CSV | PARTIAL | 6A订单/我的券支持0/CSV，但领取中心精确shop_id查询不一致。统一严格scope解析。 |
| 订单预占/取消 | IMPLEMENTED | order→attempt安全取消，创建产品/SKU→券实例条件更新；同事务返库存/券，保留原防护。 |
| 核销展示 | UNSAFE | status=1无证明时被称已使用。新核销必须沿成功付款事务；旧歧义REVIEW_REQUIRED。 |
| 新人券 | MISSING | 不猜首单；服务端注册时间在活动领取期内+商家级一次新人权益，历史不可靠资格拒绝。 |
| 前端 | PARTIAL / BROKEN | 时间date精度、店CSV、领券/选券状态/重试key、使用记录以type误判status。结构化表单及一致派生状态。 |

## 保守业务默认

- 免费满减券仅抵商品/定制加料小计，不抵配送费；抵扣上限商品小计；总应付≤0继续拒绝。
- 已发行权益快照不追溯修改。普通停用模板仅停止新领取；已发行券继续按权益快照。显式作废仅允许未预占/未核销券，并要求权限、原因及审计。
- 新人指“注册时间可验证且在本新人活动领取期内的新注册会员”，不是首次付费；全商家最多一次新人权益。不补发历史会员，不修改pay_count/余额/积分。
- 普通领取每人历史实例（包括已用/过期/删除）均计入限额；更换请求key不增加额度。
- 公共兑换码不是一次性码；新码保存SHA-256摘要，不返回App或日志；一次性码本轮不实现。积分券score>0不可新领，等待6D安全台账。
- 业务状态AVAILABLE/RESERVED/USED/EXPIRED/NOT_YET_VALID/INVALID/REVIEW_REQUIRED由权益及证据派生；旧status=1且无可证明订单/核销证据不猜测。

## 事务与锁设计

领取/模板编辑：模板行→领取幂等记录→新人唯一权益（需要时）→券实例及领取计数/审计；无资金/会员行写锁。模板锁串行化总量与每人限领，READ COMMITTED读取历史实例，条件UPDATE兜底；失败全回滚。

订单仍按既有order/context→product/SKU→coupon instance，取消保持order→全部attempt→product/SKU→coupon instance，支付保持event→order→attempt→现有会员效果→coupon instance。领取/编辑不等待订单锁；券状态查询/统计只读订单证据，不反向加锁，避免环路。生命周期审计与业务修改同事务；核销仅嵌入PaymentEffects成功事务，失败付款状态和核销一起回滚。

## 数据模型及兼容

保留 yshop_coupon / yshop_coupon_user；新增 claim_start_time/end_time、template_version、coupon_kind、claim_mode、redemption_code_hash，以及 redeemed_order_id/at、invalid_reason。旧版本默认0，领取时间为空时回落原使用时间；不更新旧行权益和旧状态。新领取复制模板面额、门槛、门店、消费方式、时间、说明、图片与版本，不复制公共兑换码。

三个新增 InnoDB 表均非金融台账：yshop_coupon_claim 以(user_id,request_key)唯一并绑定请求摘要和实例；yshop_coupon_newcomer 以user_id唯一记录商家级一次注册资格证据；yshop_coupon_operation 以event_key唯一记录领取/预占/释放/核销/显式作废及模板操作。历史实例一律计入发行和会员限额，删除或使用不恢复额度。计数不一致时领取fail closed，进入历史审核。

同一领取key相同输入返回原实例；变更活动或兑换码拒绝冲突。失败全回滚；前端仅在成功后删除该动作key，网络失败保留。公共兑换码匹配SHA-256摘要；旧plaintext只在服务端兼容读取，不返回列表。一次性码和人工任意发券未开放。

## 权限矩阵

| 入口 | RBAC | 门店资源校验 |
|---|---|---|
| 模板读取/统计/操作 | coupon::query | HQ全部；其他账号必须拥有活动范围全部门店 |
| 创建/编辑/停用 | coupon::create/update | 资源范围全授权；global仅HQ；版本冲突拒绝 |
| 删除 | coupon::delete | 仅未发行模板，保留审计 |
| 会员领券读取/导出 | coupon:user:query/export | CSV全部门店授权，不能只匹配任一店；global仅HQ |
| 显式作废 | coupon:user:delete | 未预占、未核销原始权益；必填原因和审计 |
| 原始会员券create/update/delete | 原权限仍校验 | 服务端直接拒绝，不能修改owner/status/面额 |
| 历史只读审计 | coupon::query + HQ | 聚合结果，无会员身份或兑换码 |
| App领取/查看 | 当前服务端登录会员 | 无代领uid参数；自己权益+当前范围 |

普通店员仅在已有角色授予查询权限及门店授权时查看；本PR没有新增管理员角色或扩大权限。员工门店范围复用6A StoreAccess，服务端实时读取授权。

## 状态与证据

AVAILABLE：权益有效、status0、无预占/核销/作废。NOT_YET_VALID/EXPIRED：使用起止时间决定。RESERVED：status1及匹配本人/券的存在、未付、未删除订单。USED：匹配订单paid1且PaymentFinalization SUCCESS证据；新订单同事务写redeemed_order_id/at及REDEEM审计。INVALID：明确作废/删除或非法权益。REVIEW_REQUIRED：status1缺证明或关系冲突。

模板claim窗口控制领取，券实例use窗口控制抵扣；模板停用不改变已领取状态。取消仅在PaymentCancellationGuard通过后，匹配预占订单解除关联；到期券解除预占后派生EXPIRED，不延长有效期。支付成功不增加receive，不核销其他券；冲突/失败/未处理收件不核销。退款不自动返券。

## 新人定义

会员create_time必须可验证，且不早于活动create_time与claim_start，早于claim_end，不能在未来。不是首次消费，不推断历史paid。跨活动、跨门店使用商家级唯一资格，失败事务不消耗资格。已有会员无法因切换门店得到新人权益；若商家需要“首单券”另行设计证据和人工授权，不在本阶段实现。

## Migration / 运维

文件 sql/migrations/2026-10-08-coupon-marketing.sql；随后执行2026-10-08-coupon-permissions.sql登记缺失的会员券query/export/delete菜单权限描述，不自动赋予普通角色。现有super_admin保留既有全权限语义；前端识别既有总部角色展示入口，后端仍独立执行RBAC和门店校验。角色和用户授权表指纹不变，权限描述重跑不会重复。MySQL8/InnoDB，顺序在6A多门店订单、5B付款事件、5C钱包、5D attempt及6B catalog迁移之后。上线前备份两个旧表及订单只读快照，核对表引擎、旧status/预占与receive实例差异，先在隔离库验证。新增列采用存在性检测、表/索引CREATE IF NOT EXISTS；完成后的重跑不改旧数据。部分DDL失败必须检查完整schema后补齐，不可因一个列存在就宣称整体已完成。

本轮只执行开发实例及自动清理的隔离合成数据库迁移，未执行生产迁移。MySQL DDL不是事务回滚；应用回滚优先保留新增表/列和所有领取/操作证据，停止新领取并恢复上一版应用经审核后实施。若已发行新券，禁止删除新增表、清marker或回退status；仅在确认无新写入的隔离库可还原备份。没有自动权益/资金回填脚本。

## 前端与验收边界

后台结构化表单区分领取/使用期、金额、额度、每人限领、店范围、启用及公共码；列表不回显码，统计区别领取/预占/真实核销，记录页显式作废必须确认及填写原因。小程序中心、我的券、规则、网络重试key、选券门槛、切店刷新和延迟响应防护统一；服务端始终重新计算商品及定制加料小计。实际示例小计40减5应付35，配送费另加。

不支持积分实际扣除、付费券、一次性兑换码、首单消费推断、任意人工改券、退款返券、真实营销支付。score>0模板不能免费领取，界面显示未开放。后续6D不在本PR开发。

版权与素材风险保持6B清单：MIT与部分源码版权声明/第三方素材商用授权须人工确认；不删版权、不新增来历不明素材。金融安全及历史审计见相邻文档。

# Phase 6C 历史优惠券只读审计

2026-10-08，本机开发库只读范围为变更前记录：模板id≤3、实例id≤7；这是旧数据快照边界，不代表连续存在。原字段逐行摘要与变更前一致，新增列不计入旧字段摘要。详细证据仅存 Git 忽略的 .local-dev/private/phase6c/legacy-audit.json 和 legacy-coupons-before.json，不含本PR公开会员身份、公共码或订单详情。

| 结果 | 数量 | 判定 |
|---|---:|---|
| 历史模板 | 2 | 保留 |
| 历史实例 | 3 | 全部保留 |
| pristine但过期 | 2 | EXPIRED，不恢复可用 |
| status1且无订单预占证明 | 1 | REVIEW_REQUIRED，不认定已付款核销 |
| 历史预占实例 | 0 | 未发现 |
| 关联缺失/删除模板 | 0 | 未发现 |
| receive与实际实例数不符的模板 | 1 | NEEDS_REVIEW，领取fail closed |
| 历史已删除实例 | 0 | 未发现 |
| 原字段指纹 | 全部一致 | 未修复/重写历史数据 |

计数异常不自动纠正，status1无证据不清零，不清reserved_order_id，不重算历史折扣/paid或授予旧券领取资格。过期属于明确权益结论，歧义须商家人工核对原发放及付款证据。本次未找到需要自动修复且必须修复的旧权益问题。

服务端 HQ 只读接口 /admin-api/coupon/legacy-audit 聚合发现 AVAILABLE/RESERVED/USED/EXPIRED/INVALID/REVIEW_REQUIRED、缺模板、缺订单、折扣不符与计数不符；运行时包含后来新增合成活动，因此不应把当前全量计数与上表旧快照混淆。统计中 USED 需要可信成功支付证据；不能把原始status1都计为消费。

本地新增合成活动和待付订单仅用于GUI/Mini验收；合成付款核销仅在自动清理隔离库测试，未改变开发库金融记录。没有批量纠正脚本，没有真实provider调用。

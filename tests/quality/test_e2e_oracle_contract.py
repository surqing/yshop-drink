"""Named SQL oracle queries stay read-only and validate every scope input."""
import unittest

from e2e.oracle import query_for


class E2eOracleContractTest(unittest.TestCase):
    def test_store_summary_uses_shanghai_half_open_day_bounds_and_shop_scope(self):
        sql = query_for('store-summary', {'from': '2026-10-22', 'to': '2026-10-23', 'shopId': 101})
        self.assertIn("pay_time>='2026-10-22 00:00:00'", sql)
        self.assertIn("pay_time<'2026-10-23 00:00:00'", sql)
        self.assertIn('shop_id=101', sql)
        self.assertIn('paid=1', sql)
        self.assertIn("fulfillment_status='WAIT_ACCEPT'", sql)
        self.assertTrue(sql.lstrip().startswith('SELECT JSON_OBJECT('))

    def test_store_summary_details_are_ordered_and_use_same_window(self):
        sql = query_for('store-summary-details', {'from': '2026-10-22', 'to': '2026-10-23', 'shopId': 102})
        self.assertIn("pay_time>='2026-10-22 00:00:00'", sql)
        self.assertIn("pay_time<'2026-10-23 00:00:00'", sql)
        self.assertIn('shop_id=102', sql)
        self.assertIn('ORDER BY pay_time DESC,id DESC', sql)

    def test_statistics_reject_invalid_ranges_and_sql_fragments(self):
        for ids in (
            {'from': '2026-10-23', 'to': '2026-10-23'},
            {'from': '2026-10-22', 'to': '2027-11-01'},
            {'from': "2026-10-22' OR 1=1 --", 'to': '2026-10-23'},
            {'from': '2026-02-30', 'to': '2026-03-01'},
        ):
            with self.subTest(ids=ids), self.assertRaises(ValueError):
                query_for('store-summary', ids)

    def test_statistics_shop_scope_uses_numeric_entity_validation(self):
        for name in ('store-summary', 'store-summary-details'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                query_for(name, {'from': '2026-10-22', 'to': '2026-10-23', 'shopId': '101 OR 1=1'})

    def test_fulfillment_oracle_includes_reward_and_transition_counts(self):
        sql = query_for('fulfillment-state', {'orderId': 'E2E-order-1'})
        self.assertIn('yshop_order_fulfillment_log', sql)
        self.assertIn("action='COLLECT'", sql)
        self.assertIn("title='购买商品赠送积分'", sql)
        self.assertIn('rewardBillPoints', sql)

    def test_print_oracle_includes_waiting_events_and_audited_reprints(self):
        sql = query_for('print-jobs', {'orderId': 'E2E-order-1'})
        self.assertIn('yshop_order_print_job', sql)
        self.assertIn('yshop_order_fulfillment_print_event', sql)
        self.assertIn('parent_job_id', sql)
        self.assertIn('reprint_reason', sql)
        self.assertIn('created_by', sql)
        self.assertIn('payload_snapshot', sql)
        self.assertIn('AS sort_id', sql)
        self.assertIn('ORDER BY sort_id', sql)
        self.assertNotIn('ORDER BY id', sql)

    def test_print_recovery_oracle_reads_claim_and_operator_audit(self):
        sql = query_for('print-recovery', {'jobId': 123})
        self.assertTrue(sql.lstrip().startswith('SELECT JSON_OBJECT('))
        self.assertIn('yshop_order_print_device_claim', sql)
        self.assertIn('yshop_order_print_device_claim_resolution', sql)
        self.assertIn('claimCount', sql)
        self.assertIn('resolutionOutcome', sql)
        self.assertIn('claimWasPresent', sql)
        self.assertIn('resolutionReason', sql)
        self.assertIn('prior_error', sql)
        for value in (0, -1, '123 OR 1=1'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                query_for('print-recovery', {'jobId': value})

    def test_printer_operation_resolution_oracle_matches_token_and_request_without_echoing_token(self):
        token = '12345678-1234-1234-1234-123456789012'
        sql = query_for('printer-operation-resolution', {
            'printerId': 123, 'operationToken': token, 'requestId': 'test-request-1'
        })
        self.assertIn('r.operation_token='+"'"+token+"'", sql)
        self.assertIn("r.request_id='test-request-1'", sql)
        for field in ('operation_type', 'request_id', 'outcome', 'sender_stopped', 'provider_work_settled', 'actor_id', 'reason'):
            self.assertIn(field, sql)
        self.assertNotIn("'operationToken',r.operation_token", sql)
        for ids in (
            {'printerId': 123, 'operationToken': 'not-a-token', 'requestId': 'test-request-1'},
            {'printerId': 123, 'operationToken': token, 'requestId': "x' OR 1=1 --"},
        ):
            with self.subTest(ids=ids), self.assertRaises(ValueError):
                query_for('printer-operation-resolution', ids)

    def test_product_oracle_reports_order_reward_snapshot(self):
        sql = query_for('product', {'productId': 101})
        self.assertIn("'giveIntegral',give_integral", sql)

    def test_batch_stock_oracles_are_read_only_and_join_authoritative_rows(self):
        product = query_for('batch-stock-product', {'productId': 101, 'skuId': 202})
        self.assertTrue(product.lstrip().startswith('SELECT JSON_OBJECT('))
        self.assertIn('yshop_product_operation', product)
        self.assertIn('batchOperationCount', product)
        state = query_for('batch-stock-state', {
            'batchId': '0123456789abcdef0123456789abcdef', 'productId': 101, 'skuId': 202
        })
        self.assertTrue(state.lstrip().startswith('SELECT JSON_OBJECT('))
        for field in ('yshop_product_stock_batch','yshop_product_operation','beforeStock','afterStock','operationDelta'):
            self.assertIn(field, state)
        self.assertNotIn('INSERT ', product.upper())
        self.assertNotIn('UPDATE ', state.upper())
        financial = query_for('batch-stock-financial', {})
        self.assertIn('yshop_order_payment_attempt', financial)
        self.assertIn('yshop_member_wallet_transaction', financial)

    def test_batch_stock_oracles_validate_ids_before_building_sql(self):
        for name, ids in (
            ('batch-stock-product', {'productId': '101 OR 1=1', 'skuId': 202}),
            ('batch-stock-product', {'productId': 101, 'skuId': -1}),
            ('batch-stock-state', {'batchId': "x' OR 1=1 --", 'productId': 101, 'skuId': 202}),
        ):
            with self.subTest(name=name, ids=ids), self.assertRaises(ValueError):
                query_for(name, ids)

    def test_entity_oracles_reject_order_id_sql_fragments(self):
        for name in ('fulfillment-state', 'print-jobs'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                query_for(name, {'orderId': "x' OR 1=1 --"})


if __name__ == '__main__':
    unittest.main()

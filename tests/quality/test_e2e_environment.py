"""Environment/oracle boundaries prevent shared-data access and result fabrication."""
import json
from pathlib import Path
import re
import tempfile
import unittest
from unittest.mock import patch

class OwnedEnvironmentTest(unittest.TestCase):
    def test_owned_e2e_schema_includes_current_fulfillment_and_print_migrations(self):
        from e2e.environment import MIGRATIONS
        required = [
            '2026-10-22-order-fulfillment.sql',
            '2026-10-22-order-print-event.sql',
            '2026-10-22-order-print-job.sql',
            '2026-10-05-printer-management.sql',
            '2026-10-22-order-print-device-claim.sql',
        ]
        self.assertEqual(required, [name for name in MIGRATIONS if name in required])
        self.assertLess(MIGRATIONS.index('2026-10-05-printer-management.sql'),
                        MIGRATIONS.index('2026-10-22-order-print-device-claim.sql'))

    def test_member_identity_mysql_schema_applies_fulfillment_migration(self):
        source = (Path(__file__).resolve().parent / 'member-score-mysql.py').read_text()
        self.assertIn('2026-10-22-order-fulfillment.sql', source)

    def test_payment_mysql_store_order_fixture_contains_fulfillment_columns(self):
        fixture = (Path(__file__).resolve().parents[2]
                   / 'yshop-drink-boot3/yshop-module-mall/yshop-module-order-biz'
                   / 'src/test/resources/mysql/payment-entities.sql').read_text()
        match = re.search(r'CREATE TABLE `yshop_store_order` \((.*?)\) ENGINE=', fixture, re.S)
        self.assertIsNotNone(match)
        for column in ('fulfillment_status', 'fulfillment_version'):
            with self.subTest(column=column):
                self.assertIn(f'`{column}`', match.group(1))

    def test_lightweight_import_does_not_require_optional_yaml(self):
        import subprocess,sys
        result=subprocess.run([sys.executable,'-S','-c',
            'import sys;sys.path.insert(0,"tests/quality");from e2e.environment import OwnedEnvironment'],
            cwd=Path(__file__).resolve().parents[2],capture_output=True,text=True)
        self.assertEqual(0,result.returncode,result.stderr)

    def setUp(self):
        from e2e.environment import OwnedEnvironment
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.repo=Path(self.temp.name)/'repo';self.repo.mkdir()
        self.root=Path(self.temp.name)/'run'
        self.type=OwnedEnvironment
        self.identity={'sourceSha':'sha','sourceDigest':'digest'}

    def env(self, **kwargs):
        return self.type(self.repo,self.root,identity=self.identity,**kwargs)

    def test_rejects_output_in_application_source(self):
        with self.assertRaisesRegex(ValueError,'PRIVATE_OUTPUT_REQUIRED'):
            self.type(self.repo,self.repo/'tracked-output',identity=self.identity)

    def test_rejects_invalid_owner_before_provisioning(self):
        with self.assertRaisesRegex(ValueError,'OWNER_INVALID'):
            self.env(owner='shared;DROP DATABASE')
        self.assertFalse(self.root.exists())

    def test_two_instances_have_disjoint_owned_resource_names(self):
        first=self.env();second=self.type(self.repo,Path(self.temp.name)/'second',identity=self.identity)
        self.assertNotEqual(first.owner,second.owner)
        self.assertTrue(set(first.names).isdisjoint(second.names))

    def test_context_does_not_claim_ready_before_start(self):
        env=self.env()
        with self.assertRaisesRegex(RuntimeError,'ENVIRONMENT_NOT_READY'):
            env.context()

    def test_database_ownership_rechecked_before_sql(self):
        env=self.env()
        with patch.object(env,'docker',return_value=json.dumps([{'Config':{'Labels':{'yshop.quality.owner':'another'}}}])) as call:
            with self.assertRaisesRegex(RuntimeError,'OWNERSHIP'):
                env.sql('SELECT 1')
            self.assertEqual(1,call.call_count)

    def test_close_before_start_does_not_delete_other_resources(self):
        env=self.env()
        with patch.object(env,'docker',return_value='') as call:
            receipt=env.close()
            self.assertEqual('PASSED',receipt['status'])
            self.assertEqual(0,receipt['containersRemoved'])
            self.assertTrue(all(args.args[0] in ('ps','volume') for args in call.call_args_list))

class ReadOracleTest(unittest.TestCase):
    def test_unknown_oracle_cannot_accept_arbitrary_sql(self):
        from e2e.oracle import query_for
        with self.assertRaisesRegex(ValueError,'ORACLE_UNKNOWN'):
            query_for('DELETE FROM yshop_store_order',{})

    def test_order_id_injection_rejected(self):
        from e2e.oracle import query_for
        with self.assertRaisesRegex(ValueError,'ENTITY_ID_INVALID'):
            query_for('order',{'orderId':"x'; DROP TABLE yshop_store_order;--"})

    def test_negative_and_boolean_numeric_ids_rejected(self):
        from e2e.oracle import query_for
        for value in [-1,True,'1 OR 1=1',None]:
            with self.assertRaisesRegex(ValueError,'ENTITY_ID_INVALID'):
                query_for('inventory',{'skuId':value})

    def test_order_query_is_read_only_and_uses_exact_entity(self):
        from e2e.oracle import query_for
        query=query_for('order',{'orderId':'owned-123'})
        self.assertTrue(query.startswith('SELECT JSON_OBJECT('))
        self.assertIn("WHERE order_id='owned-123'",query)
        self.assertNotIn('UPDATE ',query)

    def test_line_oracle_reads_only_exact_orders_historical_snapshot(self):
        import sqlite3
        from e2e.oracle import query_for
        db=sqlite3.connect(':memory:')
        db.execute('CREATE TABLE yshop_store_order_cart_info(id INTEGER,order_id TEXT,product_id INTEGER,title TEXT,number INTEGER,spec TEXT,price NUMERIC,cart_info TEXT)')
        db.executemany('INSERT INTO yshop_store_order_cart_info VALUES(?,?,?,?,?,?,?,?)',[(1,'owned',77,'Synthetic',2,'默认',21,'{"unitPrice":21}'),(2,'foreign',88,'Other',1,'Other',30,'{}')])
        rows=[json.loads(row[0]) for row in db.execute(query_for('order-lines',{'orderId':'owned'}))]
        self.assertEqual([{'productId':77,'title':'Synthetic','number':2,'spec':'默认','price':21,'cartInfo':'{"unitPrice":21}'}],rows)
        db.close()

    def test_financial_details_read_exact_amount_state_and_links_without_other_order(self):
        import sqlite3
        from e2e.oracle import query_for
        db=sqlite3.connect(':memory:')
        db.executescript("CREATE TABLE yshop_order_payment(id TEXT,order_id TEXT,attempt_id TEXT,amount_cents INTEGER,status TEXT,success_order_id TEXT);CREATE TABLE yshop_order_payment_attempt(attempt_id TEXT,order_id TEXT,uid INTEGER,amount_cents INTEGER,currency TEXT,status TEXT,payment_event_id TEXT);CREATE TABLE yshop_user_bill(id INTEGER,extend_field TEXT,uid INTEGER,number NUMERIC,category TEXT,type TEXT,pm INTEGER);")
        db.executescript("INSERT INTO yshop_order_payment VALUES('event','owned','attempt',1400,'SUCCESS','owned');INSERT INTO yshop_order_payment_attempt VALUES('attempt','owned',101,1400,'CNY','PAID','event');INSERT INTO yshop_user_bill VALUES(9,'owned',101,14,'now_money','pay_product',0),(10,'foreign',102,99,'now_money','pay_product',0);")
        rows=[json.loads(row[0]) for row in db.execute(query_for('financial-details',{'orderId':'owned'}))]
        self.assertEqual(3,len(rows));self.assertEqual(['payment','attempt','bill'],[r['kind'] for r in rows])
        self.assertEqual(1400,rows[0]['amountCents']);self.assertEqual('SUCCESS',rows[0]['status']);self.assertEqual('attempt',rows[0]['attemptId'])
        self.assertEqual('event',rows[1]['eventId']);self.assertEqual('PAID',rows[1]['status']);self.assertEqual(101,rows[1]['memberId'])
        self.assertEqual(14,rows[2]['amount']);self.assertEqual('owned',rows[2]['orderId']);db.close()

    def test_nested_json_mysql_output_is_read_without_batch_escape_corruption(self):
        import subprocess
        from e2e.oracle import read_oracle
        owner='a'*32
        context={'owner':owner,'mysql':'yshop-quality-business-mysql-'+owner,'schema':'yshop_quality_business_'+owner[:16],'oracleConfig':'/run/oracle.cnf'}
        row={'cartInfo':'{"title":"Synthetic","unitPrice":21}'}
        raw=json.dumps(row)
        def boundary(args,**kwargs):
            if args[1]=='inspect':return subprocess.CompletedProcess(args,0,json.dumps([{'Config':{'Labels':{'yshop.quality.owner':owner}}}]),'')
            # mysql batch mode doubles backslashes unless --raw is selected.
            stdout=raw if '--raw' in args else raw.replace('\\','\\\\')
            return subprocess.CompletedProcess(args,0,stdout+'\n','')
        with patch('e2e.oracle.subprocess.run',side_effect=boundary):
            self.assertEqual([row],read_oracle(context,'order-lines',{'orderId':'owned'}))

    def test_oracle_context_rejects_unowned_container(self):
        from e2e.oracle import read_oracle
        with self.assertRaisesRegex(ValueError,'ORACLE_CONTEXT_INVALID'):
            read_oracle({'owner':'a'*32,'mysql':'developer-mysql','schema':'shared'},'order',{'orderId':'123'})

if __name__=='__main__':unittest.main()

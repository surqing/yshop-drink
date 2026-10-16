"""Environment/oracle boundaries prevent shared-data access and result fabrication."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

class OwnedEnvironmentTest(unittest.TestCase):
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

    def test_oracle_context_rejects_unowned_container(self):
        from e2e.oracle import read_oracle
        with self.assertRaisesRegex(ValueError,'ORACLE_CONTEXT_INVALID'):
            read_oracle({'owner':'a'*32,'mysql':'developer-mysql','schema':'shared'},'order',{'orderId':'123'})

if __name__=='__main__':unittest.main()

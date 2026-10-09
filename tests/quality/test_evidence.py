import json
import os
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
sys.path.insert(0, str(Path(__file__).parent))
from evidence import Evidence, execute

class EvidenceFaultInjection(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.e = Evidence(self.temp.name)
        self.e.arguments({'synthetic.Suite': {'checksInvariant': 1}})
        self.file = self.e.directory / 'TEST-synthetic.Suite.xml'
        self.file.parent.mkdir()

    def report(self, **changes):
        attrs = {'name':'synthetic.Suite', 'tests':'1','errors':'0','failures':'0','skipped':'0'}
        attrs.update(changes)
        root = ET.Element('testsuite', attrs)
        props = ET.SubElement(root, 'properties')
        ET.SubElement(props,'property',name='quality.runId',value=self.e.id)
        ET.SubElement(root,'testcase',name='checksInvariant',classname='synthetic.Suite')
        ET.ElementTree(root).write(self.file)
        return root

    def test_valid_fresh_exact_report(self):
        self.report()
        self.assertEqual(1,self.e.validate()['tests'])
        self.assertTrue(self.e.summary['exactNamesChecked'])

    def test_missing_report(self):
        with self.assertRaisesRegex(RuntimeError,'MISSING'):self.e.validate()

    def test_stale_report(self):
        self.report();os.utime(self.file,(1,1))
        with self.assertRaisesRegex(RuntimeError,'STALE'):self.e.validate()

    def test_foreign_run(self):
        root=self.report();root.find('properties/property').set('value','previous-run');ET.ElementTree(root).write(self.file)
        with self.assertRaisesRegex(RuntimeError,'FOREIGN'):self.e.validate()

    def test_failed_skipped_error(self):
        for key in ['failures','errors','skipped']:
            with self.subTest(key=key):
                self.report(**{key:'1'})
                with self.assertRaisesRegex(RuntimeError,'FAILED_OR_SKIPPED'):self.e.validate()

    def test_hidden_case_failure(self):
        root=self.report();ET.SubElement(root.find('testcase'),'failure');ET.ElementTree(root).write(self.file)
        with self.assertRaisesRegex(RuntimeError,'FAILED_OR_SKIPPED'):self.e.validate()

    def test_undiscovered_empty(self):
        self.report(tests='0')
        with self.assertRaisesRegex(RuntimeError,'INCONSISTENT'):self.e.validate()

    def test_test_name_substitution(self):
        root=self.report();root.find('testcase').set('name','doesNotCheckInvariant');ET.ElementTree(root).write(self.file)
        with self.assertRaisesRegex(RuntimeError,'NAMES'):self.e.validate()

    def test_unexpected_suite(self):
        self.report(name='unexpected.Suite')
        with self.assertRaisesRegex(RuntimeError,'UNEXPECTED'):self.e.validate()

    def test_duplicate_suite(self):
        self.report();(self.file.parent/'TEST-duplicate.xml').write_bytes(self.file.read_bytes())
        with self.assertRaisesRegex(RuntimeError,'DUPLICATE'):self.e.validate()

    def test_broken_xml(self):
        self.file.write_text('<broken>')
        with self.assertRaises(ET.ParseError):self.e.validate()

    def test_subprocess_exit_propagates(self):
        self.assertEqual(7,execute([sys.executable,'-c','raise SystemExit(7)'],self.temp.name,os.environ.copy(),Path(self.temp.name)/'process.log'))

    def test_timeout_is_failure(self):
        with self.assertRaisesRegex(RuntimeError,'TIMEOUT'):
            execute([sys.executable,'-c','import time;time.sleep(30)'],self.temp.name,os.environ.copy(),Path(self.temp.name)/'process.log',timeout=.1)

    def test_connect_failure_is_not_success(self):
        self.assertNotEqual(0,execute([sys.executable,'-c',"import socket;socket.create_connection(('127.0.0.1',1),timeout=.1)"],self.temp.name,os.environ.copy(),Path(self.temp.name)/'process.log'))

    def test_shared_build_outputs_are_serialized(self):
        from concurrent.futures import ThreadPoolExecutor
        folder=Path(self.temp.name)
        fake=folder/'mvn-fake'
        fake.write_text('#!/bin/sh\necho start >> sequence\nsleep .15\necho end >> sequence\n')
        fake.chmod(0o700)
        with ThreadPoolExecutor(max_workers=2) as pool:
            results=list(pool.map(lambda i:execute([str(fake)],folder,os.environ.copy(),folder/('worker'+str(i)+'.log')),range(2)))
        self.assertEqual([0,0],results)
        self.assertEqual(['start','end','start','end'],(folder/'sequence').read_text().splitlines())

    def test_node_assertion_failure_is_not_pass(self):
        import shutil
        node=shutil.which('node')
        self.assertIsNotNone(node, 'QUICK requires Node')
        self.assertNotEqual(0,execute([node,'-e',"require('assert').strictEqual(1,2)"],self.temp.name,os.environ.copy(),Path(self.temp.name)/'node.log'))

    def test_bash_error_propagates(self):
        self.assertNotEqual(0,execute(['/bin/bash','-e','-c','false; exit 0'],self.temp.name,os.environ.copy(),Path(self.temp.name)/'bash.log'))

    def test_partial_dispatch_failure_cannot_be_pass(self):
        from run import Runner
        runner=Runner(Path(self.temp.name)/'dispatch')
        self.assertTrue(runner.step('good',[sys.executable,'-c','pass']))
        self.assertFalse(runner.step('bad',[sys.executable,'-c','raise SystemExit(9)']))
        self.assertEqual('NOT_READY',runner.save()['result'])

    def test_cleanup_failure_prevents_success(self):
        # Both acceptance entry points emit success only after finally's scoped DROP.
        for folder in ['business','payment']:
            source=(Path(__file__).parents[1]/folder/'mysql-acceptance.py').read_text()
            self.assertGreater(source.index('print(json.dumps(summary))'),source.index('DROP DATABASE'))
            self.assertNotIn('failIfNoSpecifiedTests=false',source)

class AcceptanceLifecycleFaultInjection(unittest.TestCase):
    def runner(self, folder):
        import importlib.util
        file=Path(__file__).parents[1]/folder/'mysql-acceptance.py'
        spec=importlib.util.spec_from_file_location('isolated_acceptance_'+folder,file)
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        return module

    def exercise(self, folder, cleanup_failure=False, exit_code=0, provision_failure=None):
        import contextlib,io
        from types import SimpleNamespace
        from unittest.mock import Mock
        module=self.runner(folder)
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);seen=[]
            def mysql(sql):
                seen.append(sql.split(' ',1)[0])
                if provision_failure and sql.startswith(provision_failure):raise RuntimeError('SYNTHETIC_PROVISION_FAILURE')
                if sql.startswith('SELECT VERSION'):return '8.0.46'
                if sql.startswith('SELECT COUNT(*)'):return '17\t17' if folder=='business' else '12\t12'
                if sql.startswith('DROP') and cleanup_failure:raise RuntimeError('SYNTHETIC_CLEANUP_FAILURE')
                return ''
            fake=SimpleNamespace(mysql=mysql)
            spec=SimpleNamespace(loader=SimpleNamespace(exec_module=lambda value:None))
            ev=Mock();ev.id='synthetic-run';ev.arguments.return_value=[];ev.validate.return_value={'tests':80}
            expected={'synthetic.OrderingDatabaseTest':{'invariant':1}} if folder=='business' else {'synthetic.PaymentDatabaseTest':{'invariant':1}}
            stream=io.StringIO()
            with patch.object(module,'PRIVATE',root),patch.object(module,'WORKSPACE',root),patch.object(module.importlib.util,'spec_from_file_location',return_value=spec),patch.object(module.importlib.util,'module_from_spec',return_value=fake),patch.object(module,'Evidence',return_value=ev),patch.object(module,'manifest',return_value=expected),patch.object(module,'execute',return_value=exit_code),patch.object(sys,'argv',['runner']),contextlib.redirect_stdout(stream):
                if provision_failure:
                    with self.assertRaisesRegex(RuntimeError,'PROVISION_FAILURE'):module.main()
                elif cleanup_failure:
                    with self.assertRaisesRegex(RuntimeError,'CLEANUP_FAILURE'):module.main()
                elif exit_code:
                    with self.assertRaisesRegex(RuntimeError,'ACCEPTANCE_FAILED'):module.main()
                else:module.main()
            self.assertIn('DROP',seen)
            self.assertFalse(list(root.glob('*.properties')),'private JDBC config must be removed even on cleanup failure')
            if cleanup_failure or exit_code or provision_failure:self.assertNotIn('"result": "PASS"',stream.getvalue())
            else:self.assertIn('"cleanup": "PASS"',stream.getvalue())

    def test_real_entrypoints_cleanup_failure_cannot_emit_pass(self):
        for folder in ['business','payment']:
            with self.subTest(folder=folder):self.exercise(folder,cleanup_failure=True)

    def test_real_entrypoints_subprocess_failure_propagates_and_cleans(self):
        for folder in ['business','payment']:
            with self.subTest(folder=folder):self.exercise(folder,exit_code=9)

    def test_partial_user_creation_failure_cleans_owned_schema(self):
        for folder in ['business','payment']:
            with self.subTest(folder=folder):self.exercise(folder,provision_failure='CREATE USER')

    def test_partial_grant_failure_cleans_owned_schema_and_account(self):
        for folder in ['business','payment']:
            with self.subTest(folder=folder):self.exercise(folder,provision_failure='GRANT')

    def test_real_entrypoints_emit_pass_only_after_cleanup(self):
        for folder in ['business','payment']:
            with self.subTest(folder=folder):self.exercise(folder)

if __name__=='__main__':unittest.main()

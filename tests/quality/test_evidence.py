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

    def test_mutant_runtime_errors_are_not_assertion_kills(self):
        from mutate import classify_mutation
        self.assertEqual('ASSERTION_FAILURE',classify_mutation(2,0))
        for failures,errors in [(0,0),(0,1),(21,22)]:
            self.assertEqual('INCONCLUSIVE',classify_mutation(failures,errors))

    def test_runner_freezes_sha_even_when_called_directly(self):
        from run import Runner
        runner=Runner(Path(self.temp.name)/'dispatch')
        runner.steps=[{'name':'synthetic','result':'PASS'}]
        with patch('run.source_identity',return_value={'sourceSha':'different','sourceDigest':runner.digest}):
            self.assertEqual('NOT_READY',runner.save()['result'])

    def test_inventory_requires_matching_certificate_not_old_xml(self):
        from inventory import certified_suites
        registry={'synthetic.Suite':{'checksInvariant':1}}
        self.report();self.assertEqual({},certified_suites(self.e.root,registry))
        self.e.validate();self.assertEqual(1,certified_suites(self.e.root,registry)['synthetic.Suite']['tests'])
        self.assertEqual({},certified_suites(self.e.root,{'synthetic.Suite':{'changed':1}}))
        root=self.report();root.find('properties/property').set('value','old');ET.ElementTree(root).write(self.file)
        self.assertEqual({},certified_suites(self.e.root,registry))

    def test_missing_or_unregistered_quick_suite_cannot_pass(self):
        from run import require_suites
        require_suites(['a','b'],['a','b'],['a','b'])
        for planned,registered,discovered in [(['a'],['a','b'],['a','b']),(['a'],['a'],['a','new']),(['a','b'],['a'],['a','b'])]:
            with self.assertRaisesRegex(RuntimeError,'INVENTORY_CHANGED'):require_suites(planned,registered,discovered)

    def test_missing_report(self):
        with self.assertRaisesRegex(RuntimeError,'MISSING'):self.e.validate()

    def test_source_change_cannot_relabel_old_execution(self):
        self.report()
        with patch('evidence.source_identity',return_value={'sourceSha':'changed','sourceDigest':'changed'}):
            with self.assertRaisesRegex(RuntimeError,'SOURCE_CHANGED'):self.e.validate()
        self.assertEqual(self.e.source['sourceSha'],self.e.diagnostics()['sourceSha'])

    def test_quick_count_change_is_not_implicitly_accepted(self):
        from run import require_count
        require_count(29,29)
        for count in [0,28,30]:
            with self.assertRaisesRegex(RuntimeError,'INVOCATIONS_CHANGED'):require_count(count,29)

    def test_changed_invocation_count_cannot_pass(self):
        root=self.report(tests='2')
        ET.SubElement(root,'testcase',name='checksInvariant',classname='synthetic.Suite')
        ET.ElementTree(root).write(self.file)
        with self.assertRaisesRegex(RuntimeError,'INVOCATIONS_CHANGED'):self.e.validate()

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

    def test_failure_diagnostics_have_locations_not_payloads(self):
        root=self.report(failures='1')
        case=root.find('testcase');case.set('name','checksInvariant(secret-token-private)')
        failure=ET.SubElement(case,'failure',type='org.opentest4j.AssertionFailedError',message='private-request secret-token-private')
        failure.text='private-request secret-token-private\n at co.yixiang.synthetic.Suite.checksInvariant(Suite.java:42)\nCaused by: java.lang.IllegalStateException: database-password-private'
        ET.SubElement(root,'system-out').text='private-request secret-token-private'
        ET.ElementTree(root).write(self.file)
        d=self.e.diagnostics();text=json.dumps(d)
        self.assertEqual('checksInvariant',d['failures'][0]['method'])
        self.assertIn('Suite.java:42',text);self.assertIn('java.lang.IllegalStateException',text)
        for value in ['secret-token-private','private-request','database-password-private']:self.assertNotIn(value,text)

    def test_failed_child_still_collects_diagnostics_and_next_step(self):
        from run import Runner
        r=Runner(Path(self.temp.name)/'dispatch')
        self.assertFalse(r.step('failed',[sys.executable,'-c','raise SystemExit(7)'],diagnostic=self.e.diagnostics))
        self.assertIn('synthetic.Suite',r.steps[0]['diagnostics']['missingSuites'])
        self.assertTrue(r.step('remaining',[sys.executable,'-c','pass']))
        self.assertEqual('NOT_READY',r.save()['result'])

    def test_untrusted_reports_cannot_be_failure_evidence(self):
        self.report();os.utime(self.file,(1,1))
        d=self.e.diagnostics();self.assertEqual(['synthetic.Suite'],d['missingSuites'])
        self.assertEqual([],d['failures']);self.assertEqual('UNTRUSTED_REPORT',d['reports'][0]['result'])

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

    def exercise(self, folder, cleanup_failure=False, exit_code=0, provision_failure=None, residual=False):
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
                if sql.startswith('SELECT COUNT(*) FROM information_schema.SCHEMATA') or sql.startswith('SELECT COUNT(*) FROM mysql.user'):return '1' if residual else '0'
                if sql.startswith('SELECT COUNT(*)'):return '17\t17' if folder=='business' else '12\t12'
                if sql.startswith('DROP') and cleanup_failure:raise RuntimeError('SYNTHETIC_CLEANUP_FAILURE')
                return ''
            fake=SimpleNamespace(mysql=mysql)
            spec=SimpleNamespace(loader=SimpleNamespace(exec_module=lambda value:None))
            ev=Mock();ev.id='synthetic-run';ev.arguments.return_value=[];ev.validate.return_value={'tests':80,'sourceSha':'synthetic','sourceDigest':'synthetic'}
            expected={'synthetic.OrderingDatabaseTest':{'invariant':1}} if folder=='business' else {'synthetic.PaymentDatabaseTest':{'invariant':1}}
            stream=io.StringIO()
            with patch.object(module,'PRIVATE',root),patch.object(module,'WORKSPACE',root),patch.object(module.importlib.util,'spec_from_file_location',return_value=spec),patch.object(module.importlib.util,'module_from_spec',return_value=fake),patch.object(module,'Evidence',return_value=ev),patch.object(module,'manifest',return_value=expected),patch.object(module,'execute',return_value=exit_code),patch.object(sys,'argv',['runner']),contextlib.redirect_stdout(stream):
                if provision_failure:
                    with self.assertRaisesRegex(RuntimeError,'PROVISION_FAILURE'):module.main()
                elif cleanup_failure:
                    with self.assertRaisesRegex(RuntimeError,'CLEANUP_FAILURE'):module.main()
                elif residual:
                    with self.assertRaisesRegex(RuntimeError,'NOT_DESTROYED'):module.main()
                elif exit_code:
                    with self.assertRaisesRegex(RuntimeError,'ACCEPTANCE_FAILED'):module.main()
                else:module.main()
            self.assertIn('DROP',seen)
            self.assertFalse(list(root.glob('*.properties')),'private JDBC config must be removed even on cleanup failure')
            if cleanup_failure or exit_code or provision_failure or residual:self.assertNotIn('"result": "PASS"',stream.getvalue())
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

    def test_drop_exit_zero_with_residual_database_cannot_pass(self):
        for folder in ['business','payment']:
            with self.subTest(folder=folder):self.exercise(folder,residual=True)

class ControlledEvidenceFaults(unittest.TestCase):
    def test_backend_exit_zero_needs_every_successful_reactor_module(self):
        from run import backend_build_evidence
        good='[INFO] one ... SUCCESS [ 1 s]\n[INFO] two ... SUCCESS [ 1 s]\n[INFO] BUILD SUCCESS\n'
        self.assertEqual(2,backend_build_evidence(good,['one','two'])['modules'])
        for text in ['',good.replace('two','one'),good.replace('two ... SUCCESS','two ... SKIPPED'),good.replace('[INFO] two ... SUCCESS [ 1 s]\n','')]:
            with self.assertRaisesRegex(RuntimeError,'INCOMPLETE'):backend_build_evidence(text,['one','two'])
    def test_exit_zero_without_assertion_report_is_blocked(self):
        from run import Runner
        with tempfile.TemporaryDirectory() as temp:
            r=Runner(temp);r.env['QA_ARGV']=json.dumps([sys.executable,'-c','pass'])
            r.configured('gui','QA_ARGV')
            self.assertEqual('BLOCKED',r.steps[-1]['result'])
            self.assertEqual('NOT_READY',r.save()['result'])

    def test_receipt_rejects_stale_foreign_partial_skipped_and_cleanup_failure(self):
        from run import controlled_receipt
        import copy,time
        with tempfile.TemporaryDirectory() as temp:
            p=Path(temp)/'receipt.json';start=time.time()-1
            good={'runId':'run','sourceSha':'sha','sourceDigest':'digest','result':'PASS',
                  'cleanup':'PASS','sourceUnchanged':True,
                  'checks':[{'name':'create','executed':True,'result':'PASS'}]}
            identity={'sourceSha':'sha','sourceDigest':'digest'}
            p.write_text(json.dumps(good));self.assertEqual(1,controlled_receipt(p,start,identity,'run',['create'])['tests'])
            variants=[]
            for key,value in [('runId','old'),('sourceDigest','changed'),('result','FAIL'),('cleanup','FAIL'),('sourceUnchanged',False),('checks',[])]:
                v=copy.deepcopy(good);v[key]=value;variants.append(v)
            for key,value in [('executed',False),('result','SKIPPED'),('name','other')]:
                v=copy.deepcopy(good);v['checks'][0][key]=value;variants.append(v)
            v=copy.deepcopy(good);v['checks'].append(v['checks'][0]);variants.append(v)
            for v in variants:
                p.write_text(json.dumps(v))
                with self.assertRaises(RuntimeError):controlled_receipt(p,start,identity,'run',['create'])
            p.write_text(json.dumps(good));os.utime(p,(1,1))
            with self.assertRaisesRegex(RuntimeError,'STALE'):controlled_receipt(p,start,identity,'run',['create'])

    def test_validation_exception_cannot_publish_secret(self):
        from run import Runner
        with tempfile.TemporaryDirectory() as temp:
            r=Runner(temp)
            def fail(log):raise RuntimeError('UPPERCASE_SECRET_TOKEN')
            self.assertFalse(r.step('guard',[sys.executable,'-c','pass'],validator=fail))
            self.assertNotIn('UPPERCASE_SECRET_TOKEN',(r.root/'report.json').read_text())
            self.assertEqual('STEP_FAILED',r.steps[-1]['reason'])

    def test_mutation_method_scope_is_exact_and_missing_method_fails(self):
        from mutate import scoped_cases
        registry={'qa.Suite':{'a':1,'b(String)[1]':1,'b(String)[2]':1}}
        self.assertEqual({'qa.Suite':{'b(String)[1]':1,'b(String)[2]':1}},scoped_cases(registry,['Suite#b']))
        with self.assertRaisesRegex(RuntimeError,'NOT_REGISTERED'):scoped_cases(registry,['Suite#missing'])

    def test_private_mini_receipt_cannot_certify_tracked_asset(self):
        from inventory import collect
        temporary=tempfile.TemporaryDirectory();self.addCleanup(temporary.cleanup)
        receipt=Path(temporary.name)/'mini.json'
        receipt.write_text(json.dumps({'result':'PASS','checks':[{'ok':True}],'scope':'different private harness'}))
        asset=next(a for a in collect(mini=receipt) if a['path']=='tests/quality/mini-readonly.cjs')
        self.assertEqual('NOT_EXECUTED',asset['recentExecution'])
        self.assertIsNone(asset['executionIdentity'])
    def test_inventory_helpers_are_never_standalone_tests(self):
        from inventory import collect
        assets=collect()
        helper=next(a for a in assets if a['path']=='tests/quality/evidence.py')
        self.assertEqual('ACTIVE_HELPER',helper['status'])
        self.assertTrue(helper['callerEvidence'])
        self.assertEqual(64,len(helper['assetHash']))
        allowed={'ACTIVE_TEST','ACTIVE_HELPER','CONDITIONAL','BLOCKED_EXTERNAL','REDUNDANT','OBSOLETE','BROKEN','UNKNOWN'}
        self.assertTrue(all(a['status'] in allowed for a in assets))
    def test_python_failure_diagnostics_exclude_payload_and_unknown_names(self):
        from run import python_diagnostics
        text='ERROR: test_python_failure_diagnostics_exclude_payload_and_unknown_names (__main__.ControlledEvidenceFaults)\nAttributeError: private-token-member-body\nERROR: test_attacker_name (__main__.InjectedClass)'
        d=python_diagnostics(text,Path(__file__));encoded=json.dumps(d)
        self.assertEqual('test_python_failure_diagnostics_exclude_payload_and_unknown_names',d['failures'][0]['method'])
        self.assertEqual('UNKNOWN_CASE',d['failures'][1]['method'])
        self.assertEqual(['AttributeError'],d['exceptionTypes'])
        for secret in ['private-token-member-body','test_attacker_name','InjectedClass']:self.assertNotIn(secret,encoded)
if __name__=='__main__':unittest.main()

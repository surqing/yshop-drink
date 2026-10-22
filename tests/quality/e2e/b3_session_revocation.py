"""Real owned HTTP/Redis acceptance for headquarters role revocation.

This deliberately stays outside the shared E2 page registry: it is a focused
backend authorization acceptance using the same owned MySQL/Redis environment
and test-classpath provider boundary as the core E2E runner.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import sys
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
import uuid

from evidence import source_identity
from .bridge import invoke
from .environment import OwnedEnvironment
from .support import TestSupport


PRINTER_TABLES = (
    'yshop_printer', 'yshop_printer_binding', 'yshop_printer_test_job',
    'yshop_order_print_job', 'yshop_order_fulfillment_print_event',
    'yshop_printer_provider_token', 'yshop_order_print_device_claim',
    'yshop_order_print_device_claim_resolution',
)
PRINTER_FIXTURE_COUNTS = {
    'yshop_printer': 1,
    'yshop_printer_binding': 0,
    'yshop_printer_test_job': 0,
    'yshop_order_print_job': 0,
    'yshop_order_fulfillment_print_event': 0,
    'yshop_printer_provider_token': 0,
    'yshop_order_print_device_claim': 0,
    'yshop_order_print_device_claim_resolution': 0,
}
HQ_USER_ID = 910104
HQ_ROLE_ID = 910104
ALLOWED_ROUTES = {
    ('POST', '/admin-api/system/auth/login'),
    ('GET', '/admin-api/system/auth/get-permission-info'),
    ('GET', '/admin-api/store/printer/page'),
    ('GET', '/admin-api/store/printer/get'),
    ('POST', '/admin-api/store/printer/test-print'),
    ('POST', '/admin-api/system/permission/assign-role-menu'),
    ('POST', '/admin-api/system/permission/assign-user-role'),
}


def fixture(environment, config, support):
    support.configure(environment, config)
    config['info']['e2eTestSupport'] = 'b3-tenant-context'
    environment.sql(
        f"INSERT INTO system_role(id,name,code,sort,status,type) "
        f"VALUES({HQ_ROLE_ID},'QA headquarters revocation','business_headquarters',4,0,2);"
        f"INSERT INTO system_users(id,username,password,nickname,status,tenant_id) "
        f"SELECT {HQ_USER_ID},'qualityhq',password,'synthetic-hq',0,901 FROM system_users WHERE id=101;"
        f"INSERT INTO system_user_role(user_id,role_id) VALUES({HQ_USER_ID},{HQ_ROLE_ID});"
        "INSERT INTO yshop_printer(id,tenant_id,name,provider_code,device_code,model,connection_type,enabled,creator,updater) "
        "VALUES(9001,0,'QA tenant zero printer','YILIAN_YUN','qa-tenant-zero-device','synthetic','YILIAN_YUN',1,'quality','quality');"
    )


def redis_keys(environment, pattern):
    config = (environment.root / 'redis.conf').read_text()
    match = re.search(r'^requirepass\s+(\S+)\s*$', config, re.M)
    if not match:
        raise RuntimeError('OWNED_REDIS_PASSWORD_NOT_FOUND')
    # The password is per-run synthetic material and is never written to a log.
    output = environment.docker(
        'exec', environment.redis, 'redis-cli', '--no-auth-warning', '-a', match.group(1),
        '--scan', '--pattern', pattern,
    )
    return [line for line in output.splitlines() if line]


def printer_snapshot(environment):
    values = {}
    for table in PRINTER_TABLES:
        values[table] = int(environment.sql('SELECT COUNT(*) FROM ' + table + ';').strip())
    return values


def printer_rows_snapshot(environment):
    # Capture mutable authorization, binding, job and claim fields without
    # exposing encrypted credentials, provider tokens or claim lease tokens.
    queries = {
        'yshop_printer': "SELECT CONCAT_WS('|',id,tenant_id,name,provider_code,device_code,enabled,deleted,authorization,"
                         "device_state,COALESCE(last_error,''),COALESCE(authorization_operation,'')) FROM yshop_printer ORDER BY id;",
        'yshop_printer_binding': "SELECT CONCAT_WS('|',tenant_id,printer_id,shop_id) FROM yshop_printer_binding ORDER BY tenant_id,printer_id;",
        'yshop_printer_test_job': "SELECT CONCAT_WS('|',tenant_id,request_id,printer_id,state,COALESCE(provider_job_id,''),COALESCE(error_code,'')) "
                                  "FROM yshop_printer_test_job ORDER BY tenant_id,request_id;",
        'yshop_order_print_device_claim': "SELECT CONCAT_WS('|',tenant_id,printer_id,job_id,shop_id) "
                                          "FROM yshop_order_print_device_claim ORDER BY tenant_id,printer_id;",
        'yshop_order_print_device_claim_resolution': "SELECT CONCAT_WS('|',tenant_id,printer_id,job_id,request_key,outcome,"
                                                       "claim_was_present,actor_id,COALESCE(prior_error,'')) "
                                                       "FROM yshop_order_print_device_claim_resolution ORDER BY tenant_id,job_id;",
    }
    return {table: environment.sql(query).splitlines() for table, query in queries.items()}


def side_effect_snapshot(environment):
    return {
        'printerTables': printer_snapshot(environment),
        'printerRows': printer_rows_snapshot(environment),
        'userRoleIds': environment.sql(
            f'SELECT COALESCE(GROUP_CONCAT(role_id ORDER BY role_id),\'\') FROM system_user_role WHERE user_id={HQ_USER_ID} AND deleted=0;'
        ).strip(),
        'deletedUserRoleIds': environment.sql(
            f'SELECT COALESCE(GROUP_CONCAT(role_id ORDER BY role_id),\'\') FROM system_user_role WHERE user_id={HQ_USER_ID} AND deleted=1;'
        ).strip(),
        'headquartersMenuIds': environment.sql(
            f'SELECT COALESCE(GROUP_CONCAT(menu_id ORDER BY menu_id),\'\') FROM system_role_menu WHERE role_id={HQ_ROLE_ID} AND deleted=0;'
        ).strip(),
        'deletedHeadquartersMenuIds': environment.sql(
            f'SELECT COALESCE(GROUP_CONCAT(menu_id ORDER BY menu_id),\'\') FROM system_role_menu WHERE role_id={HQ_ROLE_ID} AND deleted=1;'
        ).strip(),
        'accessTokens': int(environment.sql(
            f'SELECT COUNT(*) FROM system_oauth2_access_token WHERE user_id={HQ_USER_ID};'
        ).strip()),
        'refreshTokens': int(environment.sql(
            f'SELECT COUNT(*) FROM system_oauth2_refresh_token WHERE user_id={HQ_USER_ID};'
        ).strip()),
    }


def denied(response, expected_application_codes=(403,)):
    envelope = response.get('envelope')
    return response.get('httpStatus', 0) in (401, 403) or (
        isinstance(envelope, dict) and envelope.get('code') in expected_application_codes
    )


def provider_counts(counters):
    if not isinstance(counters, dict):
        return None
    return {key: counters.get(key) for key in ('printCalls', 'statusCalls')}


def denial_evidence(response):
    envelope = response.get('envelope')
    return {
        'httpStatus': response.get('httpStatus'),
        'applicationCode': envelope.get('code') if isinstance(envelope, dict) else None,
        'applicationMessage': envelope.get('msg') if isinstance(envelope, dict) else None,
    }


def run(repo, output):
    repo = Path(repo).resolve()
    parent = Path(output).resolve()
    if parent.is_relative_to(repo) and not parent.is_relative_to(repo / '.quality'):
        raise ValueError('PRIVATE_OUTPUT_REQUIRED')
    root = parent / uuid.uuid4().hex
    root.mkdir(parents=True, mode=0o700, exist_ok=False)
    os.chmod(root, 0o700)
    old_umask = os.umask(0o077)
    identity = source_identity(repo)
    owner = uuid.uuid4().hex
    environment = OwnedEnvironment(repo, root / 'owned', identity=identity, owner=owner, run_id=root.name)
    support = TestSupport(repo, root / 'support', identity, owner)
    checks = []
    issued_tokens = []
    calls = []
    report = {
        **identity, 'runId': root.name, 'owner': owner, 'profile': 'owned-b3-http-redis',
        'scope': 'real admin login, warm owned Redis authorization caches, role revocation over real HTTP, printer authorization negatives',
        'status': 'INCONCLUSIVE', 'complete': False, 'cleanup': 'NOT_RUN',
        'startedAt': time.time(), 'checks': checks, 'httpRequests': calls,
        'fakePrinterCounters': None, 'redisCacheEvidence': {},
        'tenantScopeEvidence': 'production TenantContextWebFilter registered only by B3 test support; global tenant feature remains disabled',
    }
    previous_handlers = {}

    def interrupt(signum, frame):
        raise KeyboardInterrupt('CONTROLLED_INTERRUPTION')

    for sig in (signal.SIGINT, signal.SIGTERM):
        previous_handlers[sig] = signal.signal(sig, interrupt)

    def check(name, passed, evidence=None):
        item = {'id': name, 'status': 'PASSED' if passed else 'FAILED', 'executed': True}
        if evidence is not None:
            item['evidence'] = evidence
        checks.append(item)
        if not passed:
            raise AssertionError(name)

    try:
        report['testSupport'] = support.prepare()
        environment.start(configure=lambda env, cfg: fixture(env, cfg, support), launcher=support.launch)
        report['backendArtifact'] = environment.report.get('backendBuild')

        deadline = time.monotonic() + 15
        ready = support.root / 'support-ready.json'
        while not ready.is_file():
            if time.monotonic() >= deadline:
                raise RuntimeError('TEST_SUPPORT_READINESS_TIMEOUT')
            time.sleep(0.1)
        ready_doc = json.loads(ready.read_text())
        if ready_doc.get('owner') != owner or ready_doc.get('status') != 'PASSED':
            raise RuntimeError('TEST_SUPPORT_OWNER_MISMATCH')
        if ready_doc.get('unknownProviderOperationsDenied') is not True:
            raise RuntimeError('TEST_SUPPORT_PROVIDER_BOUNDARY_NOT_VERIFIED')

        backend = environment.context()['backend']

        def api(method, path, body=None, token=None, extra_headers=None):
            parsed = urllib.parse.urlsplit(path)
            if (method, parsed.path) not in ALLOWED_ROUTES:
                raise RuntimeError('ROUTE_OUTSIDE_B3_ALLOWLIST')
            headers = {'Content-Type': 'application/json'}
            if token:
                headers['Authorization'] = 'Bearer ' + token
            if extra_headers:
                headers.update(extra_headers)
            request = urllib.request.Request(
                backend + path,
                data=json.dumps(body).encode() if body is not None else None,
                headers=headers,
                method=method,
            )
            try:
                with urllib.request.urlopen(request, timeout=30) as response:
                    value = {'httpStatus': response.status, 'envelope': json.load(response)}
            except urllib.error.HTTPError as response:
                try:
                    envelope = json.load(response)
                except Exception:
                    envelope = None
                value = {'httpStatus': response.code, 'envelope': envelope}
            payload = value.get('envelope')
            calls.append({'method': method, 'path': parsed.path, 'httpStatus': value['httpStatus'],
                          'applicationCode': payload.get('code') if isinstance(payload, dict) else None})
            if isinstance(payload, dict) and isinstance(payload.get('data'), dict):
                for key in ('accessToken', 'refreshToken'):
                    if isinstance(payload['data'].get(key), str):
                        issued_tokens.append(payload['data'][key])
            return value

        def good(response):
            envelope = response.get('envelope')
            return response.get('httpStatus') == 200 and isinstance(envelope, dict) and envelope.get('code') == 0

        def data(response):
            if not good(response):
                raise AssertionError('EXPECTED_HTTP_SUCCESS')
            return response['envelope'].get('data')

        admin = data(api('POST', '/admin-api/system/auth/login',
                         {'username': 'qualityadmin', 'password': 'syntheticQA12'}))['accessToken']
        hq_login = data(api('POST', '/admin-api/system/auth/login',
                            {'username': 'qualityhq', 'password': 'syntheticQA12'},
                            extra_headers={'tenant-id': '901'}))
        hq_token = hq_login['accessToken']
        check('real admin and headquarters password login', bool(admin and hq_token))

        baseline_db = side_effect_snapshot(environment)
        baseline_vendor = invoke(environment.context() | support.context(), 'printer-counters', {})
        missing_menu = api('GET', '/admin-api/store/printer/page?pageNo=1&pageSize=10', token=hq_token)
        check('missing printer menu permission denies before service side effects',
              denied(missing_menu) and
              side_effect_snapshot(environment) == baseline_db and
              provider_counts(invoke(environment.context() | support.context(), 'printer-counters', {})) ==
              provider_counts(baseline_vendor) and
              baseline_vendor.get('printCalls') == 0 and baseline_vendor.get('statusCalls') == 0,
              {'printerTables': baseline_db, 'denial': denial_evidence(missing_menu),
               'fakePrinterCounters': provider_counts(baseline_vendor)})

        test_menu_id = int(environment.sql(
            "SELECT id FROM system_menu WHERE permission='store:web-print:test' AND parent_id=2308 AND deleted=0;"
        ).strip())
        assigned_menu = data(api('POST', '/admin-api/system/permission/assign-role-menu',
                                 {'roleId': HQ_ROLE_ID, 'menuIds': [2309, test_menu_id]}, admin))
        check('real permission route grants the missing printer query menu',
              assigned_menu is True and environment.sql(
                  f'SELECT COUNT(*) FROM system_role_menu WHERE role_id={HQ_ROLE_ID} AND menu_id IN (2309,{test_menu_id});').strip() == '2')

        page = api('GET', '/admin-api/store/printer/page?pageNo=1&pageSize=10', token=hq_token)
        page_data = page.get('envelope', {}).get('data', {}) if isinstance(page.get('envelope'), dict) else {}
        check('tenant-901 headquarters token sees no tenant-0 printer in scoped page',
              good(page) and page_data.get('total') == 0 and page_data.get('list') == [],
              {'pageTotal': page_data.get('total'), 'pageListCount': len(page_data.get('list', []))
               if isinstance(page_data.get('list'), list) else None})
        cache_patterns = {
            'userRoles': f'*user_role_ids*{HQ_USER_ID}*',
            'printerPermissionMenus': '*permission_menu_ids*store:web-print:query*',
            'printerMenuRoles': '*menu_role_ids*2309*',
        }
        warmed = {name: redis_keys(environment, pattern) for name, pattern in cache_patterns.items()}
        report['redisCacheEvidence']['warmed'] = {name: len(keys) for name, keys in warmed.items()}
        check('real printer request warms owned Redis authorization caches', all(warmed.values()),
              report['redisCacheEvidence']['warmed'])

        wrong_tenant_before = side_effect_snapshot(environment)
        wrong_tenant_vendor_before = invoke(environment.context() | support.context(), 'printer-counters', {})
        wrong_tenant = api('GET', '/admin-api/store/printer/page?pageNo=1&pageSize=10', token=hq_token,
                           extra_headers={'tenant-id': '902'})
        wrong_tenant_after = side_effect_snapshot(environment)
        wrong_tenant_vendor_after = invoke(environment.context() | support.context(), 'printer-counters', {})
        check('tenant-902 request context is denied for tenant-901 token with no side effects',
              denied(wrong_tenant) and wrong_tenant_before == wrong_tenant_after and
              provider_counts(wrong_tenant_vendor_before) == provider_counts(wrong_tenant_vendor_after),
              {'before': wrong_tenant_before, 'after': wrong_tenant_after,
               'denial': denial_evidence(wrong_tenant),
               'fakePrinterCounters': provider_counts(wrong_tenant_vendor_after)})

        resource_before = side_effect_snapshot(environment)
        resource_vendor_before = invoke(environment.context() | support.context(), 'printer-counters', {})
        cross_tenant_printer = api('GET', '/admin-api/store/printer/get?id=9001', token=hq_token)
        resource_after = side_effect_snapshot(environment)
        resource_vendor_after = invoke(environment.context() | support.context(), 'printer-counters', {})
        check('tenant-901 token cannot read tenant-0 printer resource with no side effects',
              denied(cross_tenant_printer, expected_application_codes=(1008016100,)) and
              denial_evidence(cross_tenant_printer).get('applicationMessage') == '打印机不存在' and
              resource_before == resource_after and
              provider_counts(resource_vendor_before) == provider_counts(resource_vendor_after),
              {'before': resource_before, 'after': resource_after,
               'denial': denial_evidence(cross_tenant_printer),
               'fakePrinterCounters': provider_counts(resource_vendor_after)})

        operation_before = side_effect_snapshot(environment)
        operation_vendor_before = invoke(environment.context() | support.context(), 'printer-counters', {})
        cross_tenant_test_print = api('POST', '/admin-api/store/printer/test-print',
                                      {'id': 9001, 'requestId': 'b3-cross-tenant-0001'}, token=hq_token)
        operation_after = side_effect_snapshot(environment)
        operation_vendor_after = invoke(environment.context() | support.context(), 'printer-counters', {})
        check('tenant-901 token cannot test-print tenant-0 printer with no DB or provider side effects',
              denied(cross_tenant_test_print, expected_application_codes=(1008016100,)) and
              denial_evidence(cross_tenant_test_print).get('applicationMessage') == '打印机不存在' and
              operation_before == operation_after and
              provider_counts(operation_vendor_before) == provider_counts(operation_vendor_after) and
              operation_vendor_after.get('printCalls') == 0 and operation_vendor_after.get('statusCalls') == 0,
              {'before': operation_before, 'after': operation_after,
               'denial': denial_evidence(cross_tenant_test_print),
               'fakePrinterCounters': provider_counts(operation_vendor_after)})

        roles_before = environment.sql(f'SELECT role_id FROM system_user_role WHERE user_id={HQ_USER_ID} ORDER BY role_id;').strip()
        check('headquarters role is present before route revocation', roles_before == str(HQ_ROLE_ID))
        revoke_vendor_before = invoke(environment.context() | support.context(), 'printer-counters', {})
        revoked = data(api('POST', '/admin-api/system/permission/assign-user-role',
                           {'userId': HQ_USER_ID, 'roleIds': []}, admin))
        check('real permission route revokes the headquarters role',
              revoked is True and environment.sql(
                  f'SELECT COUNT(*) FROM system_user_role WHERE user_id={HQ_USER_ID} AND role_id={HQ_ROLE_ID} AND deleted=0;').strip() == '0',
              {'response': revoked,
               'activeRoleIds': environment.sql(
                   f'SELECT COALESCE(GROUP_CONCAT(role_id ORDER BY role_id),\'\') FROM system_user_role WHERE user_id={HQ_USER_ID} AND deleted=0;'
               ).strip(),
               'deletedRoleIds': environment.sql(
                   f'SELECT COALESCE(GROUP_CONCAT(role_id ORDER BY role_id),\'\') FROM system_user_role WHERE user_id={HQ_USER_ID} AND deleted=1;'
               ).strip()})

        remaining_user_role_keys = redis_keys(environment, f'*user_role_ids*{HQ_USER_ID}*')
        report['redisCacheEvidence']['userRoleKeysAfterRevocation'] = len(remaining_user_role_keys)
        check('role revocation evicts the warmed user role Redis cache', not remaining_user_role_keys,
              report['redisCacheEvidence']['userRoleKeysAfterRevocation'])

        permission_info = data(api('GET', '/admin-api/system/auth/get-permission-info', token=hq_token))
        check('the same old access token remains a valid session after role revocation',
              permission_info.get('user', {}).get('id') == HQ_USER_ID and
              'business_headquarters' not in permission_info.get('roles', []))

        revoke_before = side_effect_snapshot(environment)
        after_revoke = api('GET', '/admin-api/store/printer/page?pageNo=1&pageSize=10', token=hq_token)
        after_revoke_db = side_effect_snapshot(environment)
        after_revoke_vendor = invoke(environment.context() | support.context(), 'printer-counters', {})
        check('same old token is denied printer API after headquarters role revocation',
              denied(after_revoke) and revoke_before == after_revoke_db and
              provider_counts(revoke_vendor_before) == provider_counts(after_revoke_vendor) and
              after_revoke_vendor.get('printCalls') == 0 and
              after_revoke_vendor.get('statusCalls') == 0,
              {'before': revoke_before, 'after': after_revoke_db,
               'denial': denial_evidence(after_revoke),
               'fakePrinterCounters': provider_counts(after_revoke_vendor)})

        check('printer tables remain unchanged except the synthetic cross-tenant fixture',
              printer_snapshot(environment) == PRINTER_FIXTURE_COUNTS,
              printer_snapshot(environment))
        check('synthetic financial state remains untouched',
              all(environment.sql('SELECT COUNT(*) FROM ' + table + ';').strip() == '0' for table in (
                  'yshop_order_payment', 'yshop_order_payment_attempt', 'yshop_member_wallet_transaction',
                  'yshop_member_recharge_order',
              )))
        logs = (environment.root / 'backend.log').read_text(errors='replace')
        check('issued session credentials are absent from backend log',
              all(token not in logs for token in issued_tokens))
        report['status'] = 'PASSED'
    except BaseException as error:
        report.update(status='FAILED' if isinstance(error, AssertionError) else 'INCONCLUSIVE',
                      reasonType=type(error).__name__, failureCode=str(error) if isinstance(error, AssertionError) else 'B3_EXECUTION_FAILED')
        (root / 'failure-private.log').write_text(traceback.format_exc())
        (root / 'failure-private.log').chmod(0o600)
    finally:
        try:
            if environment.ready:
                report['fakePrinterCounters'] = invoke(environment.context() | support.context(), 'printer-counters', {})
        except Exception:
            report['fakePrinterCountersRead'] = 'FAILED'
        try:
            cleanup = environment.close()
        except Exception:
            cleanup = {'status': 'FAILED', 'errors': ['OWNED_CLEANUP_FAILED']}
        report['cleanup'] = cleanup.get('status', 'FAILED')
        report['cleanupReceipt'] = cleanup
        report.update(complete=True, endedAt=time.time(), sourceUnchanged=source_identity(repo) == identity,
                      testSupportHash=hashlib.sha256((support.root / 'classes/e2esupport/OwnedE2eApplication.class').read_bytes()).hexdigest()
                      if (support.root / 'classes/e2esupport/OwnedE2eApplication.class').is_file() else None)
        if report['cleanup'] != 'PASSED' or not report['sourceUnchanged']:
            report['status'] = 'INCONCLUSIVE'
        report['result'] = 'PASS' if report['status'] == 'PASSED' else 'NOT_READY'
        report['providerBoundariesVerified'] = bool(
            report.get('fakePrinterCounters') is not None and
            report.get('testSupport', {}).get('testSupportExcludedFromProductionJar') is True and
            report['fakePrinterCounters'].get('printCalls') == 0 and
            report['fakePrinterCounters'].get('statusCalls') == 0
        )
        if not report['providerBoundariesVerified']:
            report['status'] = 'INCONCLUSIVE'
            report['result'] = 'NOT_READY'
        report['realProviderOperations'] = 0
        report['fakeProviderOperations'] = None if report.get('fakePrinterCounters') is None else {
            'printCalls': report['fakePrinterCounters'].get('printCalls'),
            'statusCalls': report['fakePrinterCounters'].get('statusCalls'),
        }
        report['checksPassed'] = sum(item['status'] == 'PASSED' for item in checks)
        report['checksFailed'] = sum(item['status'] == 'FAILED' for item in checks)
        report['httpRequests'] = calls
        report['tokensIssued'] = len(issued_tokens)
        path = root / 'report.json'
        temp = path.with_suffix('.json.tmp')
        temp.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        temp.chmod(0o600)
        temp.replace(path)
        for sig, handler in previous_handlers.items():
            signal.signal(sig, handler)
        os.umask(old_umask)
    print(json.dumps({'runId': root.name, 'status': report['status'], 'checksPassed': report['checksPassed'],
                      'checksFailed': report['checksFailed'], 'cleanup': report['cleanup'],
                      'report': str(root / 'report.json')}))
    return 0 if report['status'] == 'PASSED' else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    sys.exit(run(Path(__file__).resolve().parents[3], args.output))

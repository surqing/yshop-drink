"""Real HTTP/SQL positive controls and boundary regressions for the minimal fixes.

Only the caller's disposable schema and synthetic identities are used. These
tests catch absent ownership predicates, default resets before validation,
owner transfers and denial of every request; they do not mock persistence.
"""
from concurrent.futures import ThreadPoolExecutor
import threading


def address_cases(api, sql, case, token, login):
    admin_response = api('POST', '/admin-api/system/auth/login',
                         {'username': 'qualityadmin', 'password': 'syntheticQA12'})
    if admin_response['code'] != 0:
        raise RuntimeError('ADMIN_FIXTURE_LOGIN_FAILED')
    admin = admin_response['data']['accessToken']

    def setup():
        sql("DELETE FROM yshop_user_address WHERE id IN (9201,9202,9203); "
            "UPDATE yshop_user_address SET is_default=0 WHERE uid IN (101,102); "
            "INSERT INTO yshop_user_address(id,uid,real_name,address,phone,detail,is_default,create_time,tenant_id) VALUES"
            "(9201,101,'Synthetic A','Street A','13800000101','Unit A',1,NOW(),0),"
            "(9202,102,'Synthetic B','Street B','13800000102','Unit B',1,NOW(),0),"
            "(9203,101,'Synthetic A second','Street A2','13800000101','Unit A2',0,NOW(),0);")

    def snapshot():
        return sql('SELECT id,uid,real_name,address,phone,detail,is_default,CAST(deleted AS UNSIGNED) '
                   'FROM yshop_user_address WHERE id IN (9201,9202,9203) ORDER BY id')

    def payload(address_id, default=1):
        return {'id': str(address_id), 'uid': 102, 'realName': 'Synthetic edited',
                'address': 'Edited street', 'detail': 'Edited unit', 'phone': '13800000101',
                'isDefault': default}

    def denied_edit(o):
        setup(); before = snapshot()
        r = api('POST', '/app-api/address/addAndEdit', payload(9202), token)
        o.update(code=r['code'], unchanged=snapshot() == before)
        assert r['code'] == 1004004000 and o['unchanged'], 'Foreign edit must reject without transferring ownership or clearing defaults'

    def denied_default(o):
        setup(); before = snapshot()
        r = api('POST', '/app-api/address/default/set/9202', token=token)
        o.update(code=r['code'], unchanged=snapshot() == before)
        assert r['code'] == 1004004000 and o['unchanged'], 'Foreign default must reject before touching either member defaults'

    def denied_delete(o):
        setup(); before = snapshot()
        r = api('POST', '/app-api/address/del/9202', token=token)
        o.update(code=r['code'], unchanged=snapshot() == before)
        assert r['code'] == 1004004000 and o['unchanged'], 'Foreign delete must not delete another member address'

    def invalid_ids(o):
        setup(); before = snapshot(); codes = []
        for value in ['0', '-1', '1.5', '1e3', '9223372036854775808', 'abc']:
            for prefix in ['/app-api/address/default/set/', '/app-api/address/del/']:
                codes.append(api('POST', prefix + value, token=token)['code'])
            codes.append(api('POST', '/app-api/address/addAndEdit', payload(value), token)['code'])
            codes.append(api('POST', '/app-api/score-order/submit',
                             {'productId': '9101', 'addressId': value, 'num': '1'}, token)['code'])
        o.update(codes=codes, unchanged=snapshot() == before)
        assert all(c == 1004004001 for c in codes) and o['unchanged'], 'Invalid IDs must produce parameter errors with no mutation'

    def missing_deleted(o):
        setup(); sql('UPDATE yshop_user_address SET deleted=1,is_default=0 WHERE id=9203')
        before = snapshot(); codes = []
        for value in [9203, 999999]:
            codes.append(api('POST', '/app-api/address/addAndEdit', payload(value), token)['code'])
            codes.append(api('POST', '/app-api/address/default/set/' + str(value), token=token)['code'])
            codes.append(api('POST', '/app-api/address/del/' + str(value), token=token)['code'])
            codes.append(api('POST', '/app-api/score-order/submit',
                             {'productId': '9101', 'addressId': str(value), 'num': '1'}, token)['code'])
        o.update(codes=codes, unchanged=snapshot() == before)
        assert all(c == 1004004000 for c in codes) and o['unchanged'], 'Missing/deleted addresses must fail before a default reset'

    def own_crud(o):
        setup()
        body = payload(''); r = api('POST', '/app-api/address/addAndEdit', body, token)
        assert r['code'] == 0, 'Own address creation must succeed'
        aid = r['data']
        try:
            assert sql('SELECT uid FROM yshop_user_address WHERE id=' + str(aid)).strip() == '101', 'Client UID cannot select address owner'
            edited = dict(payload(aid, 0), realName='Synthetic persisted', address='New street', detail='New unit')
            assert api('POST', '/app-api/address/addAndEdit', edited, token)['code'] == 0
            assert sql('SELECT uid,real_name,address,detail FROM yshop_user_address WHERE id=' + str(aid)).strip() == '101\tSynthetic persisted\tNew street\tNew unit'
            assert api('POST', '/app-api/address/default/set/' + str(aid), token=token)['code'] == 0
            assert sql('SELECT is_default FROM yshop_user_address WHERE id=' + str(aid)).strip() == '1'
            rows = api('GET', '/app-api/address/list?page=1&limit=100', token=token)
            assert rows['code'] == 0 and any(str(x['id']) == str(aid) for x in rows['data'])
            assert all(str(x['id']) != '9202' for x in rows['data']), 'Address list must exclude other member addresses'
            assert sql('SELECT COUNT(*) FROM yshop_user_address WHERE uid=101 AND deleted=0 AND is_default=1').strip() == '1'
            assert api('POST', '/app-api/address/del/' + str(aid), token=token)['code'] == 0
            assert sql('SELECT CAST(deleted AS UNSIGNED) FROM yshop_user_address WHERE id=' + str(aid)).strip() == '1'
            o['ownCrudSucceeded'] = True
        finally:
            sql('DELETE FROM yshop_user_address WHERE id=' + str(aid))

    def reward_sides(o):
        setup()
        sql("DELETE FROM yshop_score_order WHERE product_id=9101; DELETE FROM yshop_user_bill WHERE uid=101 AND category='integral'; "
            'UPDATE yshop_score_product SET stock=10,sales=0,is_switch=1 WHERE id=9101; UPDATE yshop_user SET integral=1000 WHERE id=101;')
        before = sql('SELECT stock,sales FROM yshop_score_product WHERE id=9101; SELECT integral FROM yshop_user WHERE id=101;')
        r = api('POST', '/app-api/score-order/submit', {'productId': '9101', 'addressId': '9202', 'num': '1'}, token)
        assert r['code'] == 1004004000, 'Authenticated foreign checkout must reach ownership rejection'
        assert sql('SELECT stock,sales FROM yshop_score_product WHERE id=9101; SELECT integral FROM yshop_user WHERE id=101;') == before
        assert sql('SELECT COUNT(*) FROM yshop_score_order WHERE product_id=9101').strip() == '0'
        assert sql("SELECT COUNT(*) FROM yshop_user_bill WHERE uid=101 AND category='integral'").strip() == '0'
        r = api('POST', '/app-api/score-order/submit', {'productId': '9101', 'addressId': '9201', 'num': '1'}, token)
        assert r['code'] == 0, 'The same authenticated member must be able to order using its own address'
        assert sql("SELECT COUNT(*) FROM yshop_score_order WHERE product_id=9101 AND uid=101 AND customer_phone='13800000101' AND customer_address='Street AUnit A'").strip() == '1'
        o.update(foreignRejectedWithoutSideEffects=True, ownCheckoutSucceeded=True)

    def admin_body(aid=9201, uid=101, default=1):
        return dict(payload(aid, default), id=aid, uid=uid, province='Synthetic', city='Synthetic',
                    district='Synthetic', postCode='000000', longitude='0', latitude='0')

    def admin_transfer(o):
        setup(); before = snapshot()
        r = api('PUT', '/admin-api/member/user-address/update', admin_body(uid=102), admin)
        o.update(code=r['code'], unchanged=snapshot() == before)
        assert r['code'] != 0 and o['unchanged'], 'Admin maintenance must not transfer an existing address to another UID'
        assert api('PUT', '/admin-api/member/user-address/update', admin_body(default=0), admin)['code'] == 0
        o['authorizedAdminEditSucceeded'] = True

    def race(actions):
        gate = threading.Barrier(len(actions))
        def worker(action):
            gate.wait(timeout=10)
            return action()
        with ThreadPoolExecutor(max_workers=len(actions)) as pool:
            return list(pool.map(worker, actions))

    def concurrent_defaults(o):
        setup()
        results = race([lambda i=i: api('POST', '/app-api/address/default/set/' + str(9201 if i % 2 else 9203), token=token) for i in range(8)])
        assert all(r['code'] == 0 for r in results), 'Valid concurrent defaults must succeed'
        assert sql('SELECT COUNT(*) FROM yshop_user_address WHERE uid=101 AND deleted=0 AND is_default=1').strip() == '1'
        assert sql('SELECT is_default FROM yshop_user_address WHERE id=9202').strip() == '1'
        o['serializedDefaults'] = True

    def admin_races(o):
        for _ in range(5):
            setup()
            results = race([lambda: api('PUT', '/admin-api/member/user-address/update', admin_body(default=1), admin),
                            lambda: api('POST', '/app-api/address/default/set/9203', token=token)])
            assert all(r['code'] == 0 for r in results)
            assert sql('SELECT COUNT(*) FROM yshop_user_address WHERE uid=101 AND deleted=0 AND is_default=1').strip() == '1', 'Admin default edits must share member serialization'
            results = race([lambda: api('DELETE', '/admin-api/member/user-address/delete?id=9203', token=admin),
                            lambda: api('POST', '/app-api/address/default/set/9203', token=token)])
            assert results[0]['code'] == 0 and results[1]['code'] in (0, 1004004000)
            assert sql('SELECT COUNT(*) FROM yshop_user_address WHERE uid=101 AND deleted=0 AND is_default=1').strip() in ('0', '1')
        o['adminAndMemberWritesSerialized'] = True

    for suffix, fn in [('foreign-edit', denied_edit), ('foreign-default', denied_default),
                       ('foreign-delete', denied_delete), ('invalid-ids', invalid_ids),
                       ('missing-deleted', missing_deleted), ('own-crud', own_crud),
                       ('checkout-controls', reward_sides), ('admin-owner', admin_transfer),
                       ('concurrent-defaults', concurrent_defaults), ('admin-races', admin_races)]:
        case('D004-' + suffix, fn.__doc__ or suffix, fn)


def profile_cases(api, sql, case, token):
    """A profile request must succeed while preserving both members' identities."""
    def identities():
        return sql('SELECT id,mobile,username,openid,routine_openid,now_money,integral,status '
                   'FROM yshop_user WHERE id IN (101,102) ORDER BY id')

    def profile(o, mobile):
        before = identities()
        try:
            body = {'nickname': 'Synthetic persisted profile', 'birthday': '2001-02-03',
                    'gender': 1, 'avatar': 'synthetic-new-avatar', 'mobile': mobile,
                    'uid': 102, 'openid': 'synthetic-attacker-openid', 'routineOpenid': 'synthetic-attacker-mini'}
            r = api('POST', '/app-api/member/user/update-nickname', body, token)
            o.update(code=r['code'], identityUnchanged=identities() == before)
            assert r['code'] == 0, 'A valid authenticated profile update must succeed, including old mobile-bearing payloads'
            assert sql('SELECT nickname,birthday,gender,avatar FROM yshop_user WHERE id=101').strip() == 'Synthetic persisted profile\t2001-02-03\t1\tsynthetic-new-avatar'
            assert o['identityUnchanged'], 'Profile must not write mobile, WeChat identity, username or assets'
            victim = api('POST', '/app-api/member/auth/login', {'mobile': '13800000002', 'password': 'syntheticQA12'})
            o['victimLoginCode'] = victim['code']
            assert victim['code'] == 0 and victim['data']['userId'] == 102, 'The other member must still log into its own UID'
        finally:
            sql("UPDATE yshop_user SET mobile='13800000001' WHERE id=101")

    def concurrent(o):
        before = identities(); gate = threading.Barrier(8)
        def worker(i):
            gate.wait(timeout=10)
            return api('POST', '/app-api/member/user/update-nickname',
                       {'nickname': 'Synthetic concurrent ' + str(i), 'birthday': '2001-02-03',
                        'gender': 0, 'avatar': 'synthetic-avatar', 'mobile': '13800000002'}, token)
        try:
            with ThreadPoolExecutor(max_workers=8) as pool:
                results = list(pool.map(worker, range(8)))
            o.update(allSucceeded=all(r['code'] == 0 for r in results), identityUnchanged=identities() == before)
            assert o['allSucceeded'] and o['identityUnchanged'], 'Concurrent valid profile updates must not change identity or reject all requests'
        finally:
            sql("UPDATE yshop_user SET mobile='13800000001' WHERE id=101")

    for suffix, mobile in [('foreign-phone', '13800000002'), ('own-phone', '13800000001'), ('empty-phone', '')]:
        case('D001-' + suffix, suffix, lambda o, mobile=mobile: profile(o, mobile))
    case('D001-concurrent-profile', 'concurrent profile identity preservation', concurrent)

"""One explicit business/check inventory, shared with the scenario executor."""

_CHECKS = {
    'auth-session': ['admin-login','invalid-provider-proof','sms-login','sms-invalid-and-reused',
                     'expired-session','logout-revokes-access','identity-sql'],
    'catalog-order': ['admin-create-product','admin-configure-sku','admin-configure-topping',
                      'order-price-and-choices','admin-order-visible','catalog-edit-keeps-snapshot','invalid-sku-denied','insufficient-stock-denied','order-sql'],
    'store-isolation': ['store-a-order','store-b-order','cross-store-product-denied',
                        'cross-store-coupon-denied','staff-cannot-read-other-store','other-member-denied','other-member-address-denied','isolation-sql'],
    'order-retry': ['first-response-lost','same-key-retry','admin-single-order','single-order-and-stock-sql'],
    'order-cancel': ['order-created','order-cancelled','admin-cancelled-order','repeat-cancel',
                     'stock-restored-once-sql','no-financial-records-sql'],
    'coupon-release': ['admin-create-coupon','claim-limit','coupon-discount','coupon-reserved-sql',
                       'cancel-releases-once','admin-disable-coupon','issued-rights-preserved','new-claim-denied'],
    'coupon-complete': ['admin-create-coupon','coupon-claimed','discounted-order','synthetic-completion',
                        'duplicate-completion','admin-paid-order','coupon-redeemed','completion-ledger-sql'],
}
_LOCAL = {
    'auth-session': ['mini-visitor-login','mini-phone-authorization','mini-denied-authorization','mini-expired-session'],
    'catalog-order': ['mini-sku-and-topping','mini-order-submitted'],
    'store-isolation': ['mini-switch-clears-context'],
    'order-retry': ['mini-retry-state'],
    'order-cancel': ['mini-cancel-page'],
    'coupon-release': ['mini-claim-and-select','mini-released-coupon'],
    'coupon-complete': ['mini-claim-and-select','mini-redeemed-coupon'],
}


def select_scenarios(profile, scenario=None):
    if profile not in ('local','ci'):
        raise ValueError('E2E_PROFILE_REQUIRED')
    if scenario is not None and scenario not in _CHECKS:
        raise ValueError('UNKNOWN_E2E_SCENARIO')
    ids=[scenario] if scenario is not None else list(_CHECKS)
    return [{'id':name, 'variant':'mini-admin' if profile=='local' else 'api-admin',
             'profiles':['local','ci'],
             'requiredCapabilities':['docker','backend','browser','test-support'] + (['mini'] if profile=='local' else []) +
                (['native-confirmation'] if profile=='local' and name in ('order-cancel','coupon-release') else []),
             'expectedCheckIds':_CHECKS[name] + (_LOCAL[name] if profile=='local' else [])}
            for name in ids]


def listing(profile, scenario=None):
    return {'profile':profile, 'scope':'three-end-core' if profile=='local' else 'backend-admin-core',
            'miniStatus':'REQUIRED' if profile=='local' else 'NOT_RUN',
            'selection':'subset' if scenario else 'core', 'scenarios':select_scenarios(profile,scenario)}

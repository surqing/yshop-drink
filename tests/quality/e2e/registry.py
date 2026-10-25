"""One explicit business/check inventory, shared with the scenario executor."""

_CHECKS = {
    'score-redemption': ['score-fixture','score-submitted','score-ledger-sql','same-key-replay','different-content-conflict','insufficient-points','exactly-once-sql'],
    'score-retry': ['score-fixture','committed-score-response-lost','original-score-key-replay','exactly-once-sql'],
    'printer-unknown': ['device-created','unknown-from-explicit-test','query-does-not-send','history-shows-unknown','staff-denied','cached-status-readonly','unknown-test-print-recovery-audited','explicit-status-refresh'],
    'pickup-contact': ['pickup-created','contact-sql','admin-contact','same-key-contact-conflict','confirmed-submission','cancel-restores-once','identity-sql'],
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
    'pickup-fulfillment': ['product-created-for-points','pickup-order-created','payment-confirmed',
                           'work-page-staff-a','work-page-staff-c','work-page-waiting',
                          'two-staff-conflict','accept-through-work-page','prepare-through-work-page','ready-through-work-page',
                          'collect-replay','collected-reward-sql'],
    'store-statistics': ['two-store-orders-paid','date-window-is-half-open','headquarters-summary-page',
                         'store-a-summary-reconcile','store-b-summary-reconcile','details-reconcile-sql',
                         'shop-switch-ignores-stale-summary','branch-a-page-scope','branch-b-page-scope',
                         'branch-cannot-read-foreign-shop'],
    'order-print-jobs': ['unbound-order-waits-on-real-page','offline-device-waits-on-real-page',
                         'order-history-on-real-page','unknown-refresh-readonly','recovery-confirmation-gate',
                         'unknown-recovery-audited','manual-reprint-audited','printed-confirmation-closes-without-resend',
                         'cross-store-print-denied',
                         'print-job-sql-audit'],
    'printer-operation-recovery': ['pending-fixture-created','hq-pending-read-is-side-effect-free',
                                   'query-only-headquarters-page','permission-and-tenant-denials',
                                   'invalid-resolution-requests-no-side-effects','page-resolution-records-audit',
                                   'two-operator-race-single-audit','same-token-role-revocation',
                                   'undelivered-command-needs-explicit-retry',
                                   'lost-response-with-empty-pending-is-not-success',
                                   'new-token-before-reread-resets-confirmation',
                                   'database-business-and-provider-invariants'],
    'printer-operation-recovery-tenant-enabled': ['tenant-enabled-same-tenant-pending-read',
                                                   'tenant-enabled-rejects-missing-and-mismatched-context',
                                                   'tenant-enabled-cross-tenant-resource-and-recovery-denied'],
}
_LOCAL = {
    'score-redemption': ['mini-score-page'],
    'score-retry': ['mini-score-reload'],
    'printer-unknown': [],
    'pickup-contact': ['mini-contact-edited'],
    'auth-session': ['mini-visitor-login','mini-phone-authorization','mini-denied-authorization','mini-expired-session'],
    'catalog-order': ['mini-sku-and-topping','mini-order-submitted'],
    'store-isolation': ['mini-switch-clears-context'],
    'order-retry': ['mini-retry-state'],
    'order-cancel': ['mini-cancel-page'],
    'coupon-release': ['mini-claim-and-select','mini-released-coupon'],
    'coupon-complete': ['mini-claim-and-select','mini-redeemed-coupon'],
    'pickup-fulfillment': ['mini-initial-status','mini-accepted-status','mini-ready-and-collect','mini-collected-status'],
}

_MINI_SCENARIOS = {name for name, checks in _LOCAL.items() if checks}


def select_scenarios(profile, scenario=None):
    if profile not in ('local','ci'):
        raise ValueError('E2E_PROFILE_REQUIRED')
    if scenario is not None and scenario not in _CHECKS:
        raise ValueError('UNKNOWN_E2E_SCENARIO')
    ids=[scenario] if scenario is not None else list(_CHECKS)
    return [{'id':name, 'variant':'mini-admin' if profile=='local' and name in _MINI_SCENARIOS else 'api-admin',
             'profiles':['local','ci'],
             'requiredCapabilities':['docker','backend','browser','test-support'] + (['mini'] if profile=='local' and name in _MINI_SCENARIOS else []) +
                (['native-confirmation'] if profile=='local' and name in ('order-cancel','coupon-release') else []),
             'expectedCheckIds':_CHECKS[name] + (_LOCAL.get(name, []) if profile=='local' else [])}
            for name in ids]


def listing(profile, scenario=None):
    scenarios=select_scenarios(profile,scenario)
    needs_mini=any('mini' in item['requiredCapabilities'] for item in scenarios)
    return {'profile':profile, 'scope':'three-end-core' if needs_mini else 'backend-admin-subset' if scenario else 'backend-admin-core',
            'miniStatus':'REQUIRED' if needs_mini else 'NOT_RUN',
            'selection':'subset' if scenario else 'core', 'scenarios':scenarios}

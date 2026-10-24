"""Source-bound repair states and immutable original business assertions."""
import ast
import hashlib
import json
from pathlib import Path
import subprocess

from e2e.contract import require

LEGACY_IDS = ('D002', 'D003', 'D005', 'D006', 'D007', 'D008')
CONTRACT_PATH = 'docs/quality/defect-contract.json'
HISTORICAL_SOURCE = {
    'sourceSha': '55e379f1120f0ff6ee0b0b09e0988b7b716180b2',
    'sourceDigest': '2c0292e73cab997b5a4432b24e0f1aebe41e25190599dacf0e67bcfeb6228fb9',
}
ASSERTION_FINGERPRINTS = {
    'reset': '8d2cd5732a89f2868cf1477d63919b727ed8c9ada83873f7d4fb74fe612a34c4',
    'reward': '8cdac71c30ae5bdebb10db03ca54194a3a66d21a3c6e13dab852bd7bf1ac2f03',
    'state': '79652f6f58ecbb1f8f178b794fb7559b72ea3e4462c4121c496e5a32a0251d7e',
    'quantity': '8842ba804d61dff33b3f4374e3ed62aacea14741850cc4f575737489ee55bb2e',
    'stock': '77834ddfad70ed887015916f0e0ffa14b606e757b6203b625ebc2441e361b45a',
    'unlisted': 'cb1bc56a337d427933356696ecf921ec73f85eba7b706302a5c34d23cb80e64d',
    'zero': 'e6900a61cb3a41c415804a86aa71d9d065c380d122c3ca26c15b937f38bfd6a6',
    'ledger': '2f8321d3c1109ad40c42ee310d032cc5e2ac9a08d1f8b2ea725cbb7fc5f0a7f1',
    'phone': '3e7d745361eacae23d7459e4e5c4a056294f2bdcba25060eaa896658273ee922',
}

# Correct failures observed twice on immutable 55e379f; fixture failures are not exceptions.
OPEN_FAILURES = {
    'D002': ('Two rewards at 100 points each must debit and record 200 points',
             {'code':0,'orders':1,'stock':8,'points':'900.00','totalScore':100}),
    'D003': ('Insufficient reward stock must reject and roll back order and points',
             {'code':0,'orders':1,'stock':1,'points':'900.00'}),
    'D005': ('Off-shelf reward must not be ordered or debit points',
             {'code':0,'orders':1,'stock':9,'points':'900.00'}),
    'D006': ('Zero quantity must reject without creating paid reward order or point debit',
             {'code':0,'orders':1,'stock':10,'points':'900.00'}),
    'D007': ('Point ledger remaining balance must equal post-debit member balance',
             {'code':0,'orders':1,'stock':9,'points':'900.00','ledgerBalance':'1000.00','ledgerDebit':'100.00'}),
    'D008': ('Pickup order must retain submitted contact phone for merchant fulfillment',
             {'submittedPhonePersisted':False}),
}


def validate_contract(value):
    require(isinstance(value, dict) and set(value) == {'version', 'defects', 'scoreIdempotencyRequired'},
            'DEFECT_CONTRACT_FIELDS_INVALID')
    require(type(value['version']) is int and value['version'] == 1, 'DEFECT_CONTRACT_VERSION_INVALID')
    states = value['defects']
    require(isinstance(states, dict) and set(states) == set(LEGACY_IDS)
            and all(state in ('OPEN', 'FIXED') for state in states.values()), 'DEFECT_CONTRACT_STATES_INVALID')
    require(type(value['scoreIdempotencyRequired']) is bool, 'DEFECT_CONTRACT_CAPABILITIES_INVALID')
    return value


def stable_ast_dump(node):
    """Keep fingerprints identical across Python releases with AST schema additions."""
    value=ast.dump(node,include_attributes=False)
    # Python 3.12 added FunctionDef.type_params. Empty type parameters carry no
    # source semantics and were absent from the Python 3.9 baseline dump.
    return value.replace(', type_params=[]','')


def load_contract(repo, identity, revision=None):
    """The validator supplies contracts from source, never from an untrusted report."""
    repo = Path(repo)
    if revision is None:
        file = repo / CONTRACT_PATH
        content = file.read_text() if file.is_file() else None
    else:
        result = subprocess.run(['git', 'show', revision + ':' + CONTRACT_PATH], cwd=repo,
                                text=True, capture_output=True)
        content = result.stdout if result.returncode == 0 else None
    if content is None:
        require(identity == HISTORICAL_SOURCE, 'UNKNOWN_SOURCE_DEFECT_CONTRACT_REQUIRED')
        return {'version': 1, 'defects': dict.fromkeys(LEGACY_IDS, 'OPEN'), 'scoreIdempotencyRequired': False}
    return validate_contract(json.loads(content))


def assertion_fingerprints(path):
    tree = ast.parse(Path(path).read_text())
    result = {}
    for name in ASSERTION_FINGERPRINTS:
        functions = [node for node in ast.walk(tree) if isinstance(node, ast.FunctionDef) and node.name == name]
        require(len(functions) == 1, 'BASELINE_ASSERTION_FUNCTION_AMBIGUOUS')
        result[name] = hashlib.sha256(stable_ast_dump(functions[0]).encode()).hexdigest()
    require(result == ASSERTION_FINGERPRINTS, 'ORIGINAL_BUSINESS_ASSERTION_CHANGED')
    return result


def migrations_for_source(source):
    """Read only that source's explicit migration list, without importing its code."""
    source = Path(source)
    tree = ast.parse((source / 'tests/quality/e2e/environment.py').read_text())
    values = [ast.literal_eval(node.value) for node in tree.body
              if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == 'MIGRATIONS' for t in node.targets)]
    require(len(values) == 1 and isinstance(values[0], list) and bool(values[0]), 'BASELINE_MIGRATION_MANIFEST_REQUIRED')
    names = values[0]
    require(len(names) == len(set(names)) and all(isinstance(name, str) and Path(name).name == name
            and (source / 'yshop-drink-boot3/sql/migrations' / name).is_file() for name in names),
            'BASELINE_MIGRATION_MANIFEST_INVALID')
    return names

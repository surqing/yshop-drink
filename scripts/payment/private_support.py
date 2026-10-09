"""Project-scoped private files and read-only local database access. Never print inputs."""
import importlib.util
import json
import os
import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
WORKSPACE = Path(os.environ.get('YSHOP_TEST_WORKSPACE', REPO.parent)).resolve()
PRIVATE = WORKSPACE / '.local-dev/private'


def private_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    if path.is_symlink():
        raise ValueError()
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'w') as out:
        json.dump(value, out, indent=2)
        out.write('\n')
    path.chmod(0o600)


def isolated_schema():
    isolated = os.environ.get('YSHOP_ACCEPTANCE_CONFIG')
    if not isolated:return None
    if isolated:
        settings = dict(line.split('=', 1) for line in Path(isolated).read_text().splitlines() if '=' in line)
        match = re.fullmatch(r'jdbc:mysql://127[.]0[.]0[.]1:[0-9]{2,5}/(yshop_acceptance_phase5h_[a-f0-9]{8})[?].*', settings.get('url', ''))
        if not match or not re.fullmatch(r'accept5h_[a-f0-9]{8}', settings.get('username', '')):
            raise ValueError()
        return match[1]


def report_path(folder, name):
    """Synthetic acceptance owns its evidence folder; never replace an operator's report."""
    return Path(folder) / name if isolated_schema() else PRIVATE / name


def local_rows(select):
    if not select.lstrip().upper().startswith('SELECT ') or ';' in select:
        raise ValueError()
    # The explicitly configured project helper owns Docker authentication. No credential search.
    spec = importlib.util.spec_from_file_location('local_database', Path(os.environ.get('YSHOP_DATABASE_HELPER',str(WORKSPACE / '.local-dev/database.py'))))
    helper = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helper)
    schema = isolated_schema()
    prefix = 'USE `' + schema + '`; ' if schema else ''
    text = helper.mysql(prefix + 'START TRANSACTION READ ONLY; ' + select + '; COMMIT;')
    return [json.loads(line) for line in text.splitlines() if line]


def guarded_main(action):
    try:
        return action()
    except Exception:
        # Exception text may contain request bodies, query results or credential material.
        print(json.dumps({'result': 'FAIL', 'reason': 'Private input or local evidence unavailable'}))
        return 1

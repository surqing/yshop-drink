"""Fresh, attributable Surefire evidence. No report from a prior run can be adopted."""
from collections import Counter
from pathlib import Path
import contextlib
import fcntl
import json
import os
import re
import signal
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET


def workspace(repo):
    return Path(os.environ.get('YSHOP_TEST_WORKSPACE', repo.parent)).resolve()


@contextlib.contextmanager
def build_lock(command, cwd, timeout):
    """Serialize Maven against a checkout's shared target/classes, not business DB operations."""
    if not Path(command[0]).name.startswith('mvn'):
        yield; return
    folder=Path(cwd).resolve()
    roots=[p for p in [folder,*folder.parents] if p.name=='yshop-drink-boot3']
    root=(roots[0].parent if roots else folder)/'.quality'
    root.mkdir(mode=0o700,exist_ok=True)
    with (root/'maven.lock').open('a') as lock:
        os.chmod(root/'maven.lock',0o600)
        deadline=time.monotonic()+timeout
        while True:
            try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB);break
            except BlockingIOError:
                if time.monotonic()>=deadline:raise RuntimeError('BUILD_LOCK_TIMEOUT')
                time.sleep(.05)
        try:yield
        finally:fcntl.flock(lock,fcntl.LOCK_UN)


def execute(command, cwd, env, log, timeout=1800):
    """Private output, bounded process group; a timeout never leaves Maven children running."""
    started=time.monotonic()
    with build_lock(command,cwd,timeout), Path(log).open('w') as output:
        os.chmod(log, 0o600)
        child = subprocess.Popen(command, cwd=cwd, env=env, stdout=output,
                                 stderr=subprocess.STDOUT, start_new_session=True)
        try:
            return child.wait(timeout=max(.01,timeout-(time.monotonic()-started)))
        except subprocess.TimeoutExpired:
            os.killpg(child.pid, signal.SIGTERM)
            try:child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(child.pid, signal.SIGKILL);child.wait()
            raise RuntimeError('TEST_PROCESS_TIMEOUT') from None


class Evidence:
    def __init__(self, parent):
        self.id = uuid.uuid4().hex
        self.root = Path(parent) / self.id
        self.root.mkdir(parents=True, mode=0o700, exist_ok=False)
        self.started = time.time()
        self.directory = self.root / 'surefire'
        self.expected = None
        self.summary = None

    def arguments(self, expected):
        self.expected = expected
        (self.root / 'run.json').write_text(json.dumps({'runId': self.id, 'expected': expected}))
        return ['-Dquality.runId=' + self.id, '-Dsurefire.reportsDirectory=' + str(self.directory)]

    def validate(self, expected=None):
        """Expected maps FQCN to exact {method display name: invocation count} or None.
        None validates discovery only and is explicitly unsuitable for final suite certification.
        """
        expected = expected if expected is not None else self.expected
        found = {}
        for file in self.directory.rglob('TEST-*.xml'):
            if file.stat().st_mtime < self.started:
                raise RuntimeError('STALE_TEST_REPORT')
            root = ET.parse(file).getroot()
            name = root.get('name')
            if name in found:
                raise RuntimeError('DUPLICATE_TEST_SUITE')
            properties = {x.get('name'): x.get('value') for x in root.findall('properties/property')}
            if properties.get('quality.runId') != self.id:
                raise RuntimeError('FOREIGN_TEST_REPORT')
            cases = root.findall('testcase')
            if not cases or len(cases) != int(root.get('tests', '-1')):
                raise RuntimeError('EMPTY_OR_INCONSISTENT_TEST_REPORT')
            if any(int(root.get(k, '-1')) != 0 for k in ('errors', 'failures', 'skipped')) or any(c.find(k) is not None for c in cases for k in ('failure', 'error', 'skipped')):
                raise RuntimeError('FAILED_OR_SKIPPED_TESTS')
            names = dict(Counter(c.get('name') for c in cases))
            if name in expected and expected[name] is not None and expected[name] != names:
                raise RuntimeError('TEST_NAMES_OR_INVOCATIONS_CHANGED')
            found[name] = names
        if set(found) != set(expected):
            raise RuntimeError('MISSING_OR_UNEXPECTED_TEST_SUITE')
        self.summary = {'runId': self.id, 'result': 'PASS', 'suites': len(found),
                        'tests': sum(sum(x.values()) for x in found.values()), 'cases': found,
                        'exactNamesChecked': all(x is not None for x in expected.values())}
        (self.root / 'evidence.json').write_text(json.dumps(self.summary, indent=2))
        return self.summary

    def diagnostics(self):
        """Public-safe structural evidence. Never publish XML messages, output or values."""
        result = {'runId': self.id, 'reports': [], 'failures': [], 'missingSuites': []}
        seen = set()
        for file in sorted(self.directory.rglob('TEST-*.xml')):
            try:
                root = ET.parse(file).getroot()
                props = {x.get('name'): x.get('value') for x in root.findall('properties/property')}
                name = root.get('name')
                if file.stat().st_mtime < self.started or props.get('quality.runId') != self.id or name not in (self.expected or {}):
                    result['reports'].append({'result': 'UNTRUSTED_REPORT'}); continue
                seen.add(name)
                result['reports'].append({'suite': name, **{k: int(root.get(k, -1)) for k in ('tests','failures','errors','skipped')}})
                known = {re.match(r'\w+', k).group() for k in (self.expected[name] or {}) if re.match(r'\w+', k)}
                for case in root.findall('testcase'):
                    node = next((case.find(k) for k in ('failure','error','skipped') if case.find(k) is not None), None)
                    if node is None: continue
                    match = re.match(r'\w+', case.get('name', ''))
                    method = match.group() if match and match.group() in known else 'UNKNOWN_CASE'
                    types = [node.get('type','')] + re.findall(r'Caused by: ([\w.$]+)', node.text or '')
                    types = [t for t in types if re.fullmatch(r'(?:java|javax|jakarta|org|com|co)\.[\w.$]{1,180}', t)]
                    frames = re.findall(r'\bat (co\.yixiang[\w.$]+)\(([A-Za-z_$][\w$]*\.java:\d+)\)', node.text or '')[:8]
                    result['failures'].append({'suite': name, 'method': method, 'kind': node.tag,
                                               'exceptionTypes': types[:8], 'locations': [f'{c}({l})' for c,l in frames]})
            except (ET.ParseError, ValueError):
                result['reports'].append({'result': 'MALFORMED_REPORT'})
        result['missingSuites'] = sorted(set(self.expected or {}) - seen)
        (self.root / 'diagnostics.json').write_text(json.dumps(result, indent=2))
        return result


def manifest(repo):
    return json.loads((repo / 'tests/quality/java-manifest.json').read_text())

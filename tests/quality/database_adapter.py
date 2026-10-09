"""Run-owned Docker MySQL adapter. Only a labelled disposable container is admissible."""
import json,os,re,subprocess
from pathlib import Path

def mysql(sql):
    config=Path(os.environ['YSHOP_OWNED_DATABASE_CONFIG'])
    if config.is_symlink() or config.stat().st_mode & 0o077:raise RuntimeError('PRIVATE_DATABASE_CONFIG_REQUIRED')
    data=json.loads(config.read_text());name=data['container'];owner=data['owner']
    if not re.fullmatch('yshop-quality-heavy-[a-f0-9]{32}',name) or name!='yshop-quality-heavy-'+owner:
        raise RuntimeError('DATABASE_RESOURCE_IDENTITY_MISMATCH')
    def docker(args,input=None):
        r=subprocess.run(['docker',*args],input=input,text=True,capture_output=True,timeout=120)
        if r.returncode:
            # Raw stderr stays only in this run's private directory. Public diagnostics
            # expose numeric MySQL error/state, never SQL text or server messages.
            private=config.parent/'database-private-error.log'
            private.write_text(r.stderr);private.chmod(0o600)
            match=re.search(r'ERROR (\d{1,5}) \(([A-Z0-9]{5})\)',r.stderr)
            safe={'result':'FAIL','reasonCode':'OWNED_DATABASE_OPERATION_FAILED'}
            if match:safe.update(mysqlError=int(match[1]),sqlState=match[2])
            (config.parent/'database-diagnostics.json').write_text(json.dumps(safe))
            raise RuntimeError('OWNED_DATABASE_OPERATION_FAILED')
        return r.stdout.strip()
    state=json.loads(docker(['inspect',name]))[0]
    if state['Config']['Labels'].get('yshop.quality.owner')!=owner:
        raise RuntimeError('DATABASE_OWNERSHIP_MISMATCH')
    return docker(['exec','-i',name,'mysql','--defaults-extra-file=/run/client.cnf','--protocol=TCP','-h','127.0.0.1','--batch','--skip-column-names'],sql)

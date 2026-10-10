"""Fail-closed destruction of only run-owned Docker containers and anonymous volumes."""
import json,re

def remove_owned(docker,names,owner):
    removed=[];volumes=[]
    for name in reversed(names):
        present=docker('ps','--all','--filter','name=^/'+name+'$','--format','{{.Names}}').splitlines()
        if not present:continue
        if present != [name]:raise RuntimeError('RESOURCE_IDENTITY_MISMATCH')
        info=json.loads(docker('inspect',name))[0]
        if info['Config']['Labels'].get('yshop.quality.owner')!=owner:raise RuntimeError('OWNERSHIP_MISMATCH')
        mounted=[m['Name'] for m in info.get('Mounts',[]) if m['Type']=='volume']
        if any(not re.fullmatch(r'[0-9a-f]{64}',v) for v in mounted):raise RuntimeError('ANONYMOUS_VOLUME_REQUIRED')
        volumes.extend(mounted);docker('rm','--force','--volumes',name)
        if docker('ps','--all','--filter','name=^/'+name+'$','--format','{{.Names}}').strip():raise RuntimeError('RESOURCE_NOT_DESTROYED')
        removed.append(name)
    for volume in volumes:
        if docker('volume','ls','--filter','name=^'+volume+'$','--format','{{.Name}}').strip():raise RuntimeError('VOLUME_NOT_DESTROYED')
    return {'containersRemoved':len(removed),'anonymousVolumesRemoved':len(volumes),'ownershipChecked':True}

def assert_backend_owner(info, owner):
    if not isinstance(info,dict) or info.get('qualityOwner')!=owner:raise RuntimeError('BACKEND_OWNERSHIP_MISMATCH')

def failed_report(report, error, stage):
    """A later failure overrides successful assertions; exception messages stay private."""
    report['result']='FAIL'
    report['reasonType']=type(error).__name__
    report['failedStage']=stage
    if isinstance(error,KeyError) and error.args and error.args[0] in {'NetworkSettings','Ports','6379/tcp','3306/tcp','HostIp','HostPort','Labels','Mounts'}:
        report['missingMetadataKey']=error.args[0]
    import traceback
    report['locations']=[{'file':f.filename.rsplit('/',1)[-1],'function':f.name,'line':f.lineno} for f in traceback.extract_tb(error.__traceback__) if f.filename.rsplit('/',1)[-1] in {'heavy.py','business-backend.py','auth-mysql.py','owned_resources.py','database_adapter.py'}][-8:]
    if isinstance(error,RuntimeError) and str(error)=='SOURCE_CHANGED_DURING_TEST':
        report['reasonCode']='SOURCE_CHANGED_DURING_TEST'

GUI_CHECKS=['product-create','product-edit','sku-stock','coupon-create','coupon-disable','permission-denied','unpaid-order-query','history-snapshot']
def gui_receipt(path, started, identity, owner):
    from run import controlled_receipt
    return controlled_receipt(path,started,identity,owner,GUI_CHECKS)


def redis_permission_probe(output):
    """Reproduce old Linux bootstrap with a private file; no shared Redis or credentials."""
    import os,secrets,subprocess,time,uuid,sys
    from pathlib import Path
    from evidence import source_identity
    root=Path(output).resolve();root.mkdir(parents=True,mode=0o700,exist_ok=False)
    owner=uuid.uuid4().hex;name='yshop-quality-redis-probe-'+owner
    config=root/'redis.conf';config.write_text('bind 0.0.0.0\nrequirepass '+secrets.token_hex(24)+'\nsave ""\nappendonly no\n');config.chmod(0o600)
    identity=source_identity();report={**identity,'owner':owner,'result':'FAIL','cleanup':'NOT_RUN','scope':'original Redis bootstrap permission reproduction; synthetic only'}
    def docker(*args):
        r=subprocess.run(['docker',*args],capture_output=True,text=True,timeout=120)
        if r.returncode:raise RuntimeError('REDIS_PROBE_DOCKER_FAILED')
        return (r.stdout+r.stderr).strip() if args[0]=='logs' else r.stdout.strip()
    try:
        docker('run','-d','--name',name,'--label','yshop.quality.owner='+owner,'--cap-drop=NET_RAW',
               '--mount','type=bind,source='+str(config)+',target=/run/redis.conf,readonly','redis:7.4','redis-server','/run/redis.conf')
        for _ in range(30):
            state=json.loads(docker('inspect',name))[0]
            if not state['State']['Running']:break
            time.sleep(.1)
        logs=docker('logs',name);private=root/'docker-private.log';private.write_text(logs);private.chmod(0o600)
        denied='permission denied' in logs.lower() and 'config' in logs.lower()
        report['oldBootstrapConfigReadDenied']=denied
        report['oldBootstrapRunning']=state['State']['Running']
        report['hostPlatform']=sys.platform;report['hostFileOwnerUid']=os.getuid()
        if sys.platform.startswith('linux') and os.getuid()!=999 and not denied:raise RuntimeError('OLD_BOOTSTRAP_FAILURE_NOT_REPRODUCED')
        if not denied and not state['State']['Running']:raise RuntimeError('UNRELATED_REDIS_BOOTSTRAP_FAILURE')
        report['result']='PASS'
    except Exception as error:failed_report(report,error,'REDIS_PERMISSION_REPRODUCTION')
    finally:
        try:report['resources']=remove_owned(docker,[name],owner);report['cleanup']='PASS'
        except Exception as error:failed_report(report,error,'CLEANUP');report['cleanup']='FAIL'
        config.unlink(missing_ok=True);report['sourceUnchanged']=source_identity()==identity
        if not report['sourceUnchanged']:report['result']='FAIL'
        (root/'report.json').write_text(json.dumps(report,indent=2))
    print(json.dumps({'result':report['result'],'oldBootstrapConfigReadDenied':report.get('oldBootstrapConfigReadDenied'),'cleanup':report['cleanup']}))
    return 0 if report['result']=='PASS' and report['cleanup']=='PASS' else 1

if __name__=='__main__':
    import argparse,sys
    parser=argparse.ArgumentParser();parser.add_argument('--redis-permission-probe',required=True)
    sys.exit(redis_permission_probe(parser.parse_args().redis_permission_probe))

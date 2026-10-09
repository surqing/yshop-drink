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

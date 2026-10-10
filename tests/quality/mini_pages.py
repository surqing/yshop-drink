"""Fresh HBuilderX compile and official automation; no adoption of historical compiled apps."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import time
from evidence import execute, source_identity, workspace
from page_evidence import mini_receipt

REPO=Path(__file__).resolve().parents[2]

def mini_prerequisite():
    hx=Path(os.environ.get('YSHOP_HBUILDER_CLI','/Applications/HBuilderX.app/Contents/MacOS/cli'))
    wx=Path(os.environ.get('YSHOP_WECHAT_CLI','/Applications/wechatwebdevtools.app/Contents/MacOS/cli'))
    config=Path(os.environ.get('YSHOP_MINI_APP_CONFIG',str(workspace(REPO)/'.uniapp-dev/.env')))
    if not hx.is_file() or not wx.is_file() or not config.is_file():return 'OFFICIAL_TOOL_OR_OWN_APPID_MISSING'
    values=dict(line.split('=',1) for line in config.read_text().splitlines() if '=' in line and not line.startswith('#'))
    if not re.fullmatch(r'wx[0-9a-fA-F]{16}',values.get('WECHAT_APP_ID','').strip()):return 'OWN_APPID_REQUIRED'
    if not (REPO/'tests/mini/node_modules/miniprogram-automator').is_dir():return 'LOCKED_MINI_AUTOMATOR_INSTALL_REQUIRED'
    return None

def run_mini(root,report,fixture,script=None,checks=None):
    output=root/'mini';output.mkdir(mode=0o700)
    identity={k:report[k] for k in ['sourceSha','sourceDigest']}
    hx=Path(os.environ.get('YSHOP_HBUILDER_CLI','/Applications/HBuilderX.app/Contents/MacOS/cli'))
    wxcli=Path(os.environ.get('YSHOP_WECHAT_CLI','/Applications/wechatwebdevtools.app/Contents/MacOS/cli'))
    appconfig=Path(os.environ.get('YSHOP_MINI_APP_CONFIG',str(workspace(REPO)/'.uniapp-dev/.env')))
    if not hx.is_file() or not wxcli.is_file() or not appconfig.is_file():
        return {'result':'BLOCKED','reason':'OFFICIAL_TOOL_OR_OWN_APPID_MISSING','checks':0}
    values=dict(line.split('=',1) for line in appconfig.read_text().splitlines() if '=' in line and not line.startswith('#'))
    appid=values.get('WECHAT_APP_ID','').strip()
    if not re.fullmatch(r'wx[0-9a-fA-F]{16}',appid):return {'result':'BLOCKED','reason':'OWN_APPID_REQUIRED','checks':0}
    project=output/'project';source=REPO/'yshop-drink-uniapp-vue3'
    shutil.copytree(source,project,ignore=shutil.ignore_patterns('node_modules','unpackage','.git','.hbuilderx'))
    hashes={str(p.relative_to(source)):hashlib.sha256(p.read_bytes()).hexdigest() for p in source.rglob('*') if p.is_file() and not any(part in {'node_modules','unpackage','.git','.hbuilderx'} for part in p.relative_to(source).parts)}
    text=(project/'manifest.json').read_text()
    text=re.sub(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"',lambda m:m[0] if m[0].startswith('"') else '',text)
    manifest=json.loads(text)
    def sanitize(value):
        if isinstance(value,dict):
            for key,item in value.items():
                if isinstance(item,(dict,list)):sanitize(item)
                elif re.search(r'appid|secret|key|token',key,re.I):value[key]=''
        elif isinstance(value,list):
            for item in value:sanitize(item)
    sanitize(manifest);manifest['mp-weixin']['appid']=appid
    manifest['mp-weixin'].setdefault('setting',{})['urlCheck']=False
    (project/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2))
    config=(project/'config/index.js').read_text()
    for variable in ['DEV_API_URL','PROD_API_URL']:
        config,count=re.subn(r'(const '+variable+r'\s*=\s*)[^\n]+',lambda m:m[1]+json.dumps(fixture['backend']+'/app-api'),config)
        if count!=1:raise RuntimeError('MINI_API_CONFIG_NOT_UNIQUE')
    config,count=re.subn(r'(export const APP_ID\s*=\s*)[^\n]+',lambda m:m[1]+json.dumps(appid),config)
    if count!=1:raise RuntimeError('MINI_APPID_CONFIG_NOT_UNIQUE')
    (project/'config/index.js').write_text(config)
    for name,digest in hashes.items():
        if name not in {'manifest.json','config/index.js'} and hashlib.sha256((project/name).read_bytes()).hexdigest()!=digest:raise RuntimeError('MINI_MIRROR_SOURCE_CHANGED')
    env=os.environ.copy()
    commands=[('dependencies',['npm','ci','--prefix',str(project),'--ignore-scripts','--no-audit','--no-fund']),
        ('hbuilder-open',[str(hx),'open']),('project-open',[str(hx),'project','open','--path',str(project)]),
        ('compile',[str(hx),'launch','mp-weixin','--project',str(project),'--compile','true'])]
    for name,command in commands:
        if execute(command,REPO,env,output/(name+'.log'),600):raise RuntimeError('MINI_CURRENT_COMPILE_FAILED')
    if '编译成功' not in (output/'compile.log').read_text():raise RuntimeError('MINI_COMPILE_SUCCESS_MISSING')
    dist=project/'unpackage/dist/dev/mp-weixin'
    if not (dist/'app.js').is_file():raise RuntimeError('MINI_DIST_MISSING')
    compiled=''.join(p.read_text(errors='replace') for p in dist.rglob('*.js'))
    if fixture['backend']+'/app-api' not in compiled:raise RuntimeError('MINI_COMPILED_API_MISMATCH')
    configpath=dist/'project.config.json';cfg=json.loads(configpath.read_text());cfg['appid']=appid;configpath.write_text(json.dumps(cfg))
    (dist/'project.private.config.json').write_text(json.dumps({'setting':{'urlCheck':False}}))
    # Instrument the disposable compiled app before App.onLaunch. Production pages stay unchanged.
    guard=(REPO/'tests/mini/cold-guard.js').read_text().replace('__OWNED_BASE__',json.dumps(fixture['backend'])).replace('__MEMBER_TOKEN__',json.dumps(fixture['member']))
    app=dist/'app.js';original=app.read_text();app.write_text(guard+'\n'+original);app.chmod(0o600)
    cold_hash=hashlib.sha256(guard.encode()).hexdigest()
    compiled_hashes={str(p.relative_to(dist)):hashlib.sha256(p.read_bytes()).hexdigest() for p in dist.rglob('*') if p.is_file() and p.name!='project.private.config.json'}
    with socket.socket() as probe:probe.bind(('127.0.0.1',0));port=probe.getsockname()[1]
    ctx={**identity,'runId':report['runId'],'project':str(dist),'cli':str(wxcli),'port':port,
         'compiledHashes':compiled_hashes,'compiledSource':identity,'coldGuardSha256':cold_hash}
    private=output/'context.json';private.write_text(json.dumps(ctx));private.chmod(0o600)
    receipt=output/'receipt.json';env.update(YSHOP_MINI_CONTEXT=str(private),YSHOP_MINI_REPORT=str(receipt))
    started=time.time()
    try:
        code=execute(['node',script or 'tests/mini/pages.cjs'],REPO,env,output/'automation-private.log',360)
        if code:raise RuntimeError('MINI_PAGE_AUTOMATION_FAILED')
        expected=checks if checks is not None else json.loads((REPO/'tests/mini/page-manifest.json').read_text())
        evidence=mini_receipt(receipt,started,identity,report['runId'],expected)
        result=json.loads(receipt.read_text())
        return {'result':'PASSED',**evidence,'orderId':result['orderId'], 'compiledSource':identity,
                'coldGuardSha256':cold_hash,'compiledFileCount':len(compiled_hashes),'scope':'official developer tool simulator; no physical device/provider authentication'}
    finally:
        private.unlink(missing_ok=True)
        app.write_text(original) # Always remove the prepared identity before tool cleanup.
        if receipt.is_file():
            cleanup=json.loads(receipt.read_text())
            if cleanup.get('cleanup')!='PASS':
                closed=execute([str(wxcli),'close','--project',str(dist)],REPO,env,output/'project-close-private.log',60)
                cleanup['cleanup']='PASS' if closed==0 else 'FAIL'
                cleanup['officialProjectCleanup']='PASSED' if closed==0 else 'FAILED'
                receipt.write_text(json.dumps(cleanup,indent=2))

#!/usr/bin/env python3
"""Compile this checkout into a fresh private HBuilderX mirror; never reuse dist evidence."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import uuid
from evidence import execute, workspace, source_identity
REPO=Path(__file__).resolve().parents[2]

def main():
    ws=workspace(REPO);root=ws/'.local-dev/quality/uniapp'/uuid.uuid4().hex
    root.mkdir(parents=True,mode=0o700);project=root/'project'
    appconfig=ws/'.uniapp-dev/.env'
    if not appconfig.exists():raise RuntimeError('PRIVATE_APPID_CONFIG_REQUIRED')
    # Reuse the previously validated mirror sanitization, but explicitly bind both source and
    # destination to this run. The original project, configs and logged-in runtime remain intact.
    prepare=(ws/'.uniapp-dev/prepare.py').read_text()
    prepare=prepare.replace('root = Path(__file__).resolve().parent','root = Path('+repr(str(root))+')')
    prepare=prepare.replace("source = root.parent / 'yshop-drink/yshop-drink-uniapp-vue3'",'source = Path('+repr(str(REPO/'yshop-drink-uniapp-vue3'))+')')
    prepare=prepare.replace("env = root / '.env'",'env = Path('+repr(str(appconfig))+')')
    api_port=os.environ.get('YSHOP_API_PORT','48083')
    if api_port not in ['48081','48082','48083']:raise RuntimeError('AUTHORIZED_LOCAL_API_PORT_REQUIRED')
    previous=os.environ.get('YSHOP_API_PORT');os.environ['YSHOP_API_PORT']=api_port
    exec(compile(prepare,'validated-private-mirror','exec'),{'__file__':str(ws/'.uniapp-dev/prepare.py')})
    env=os.environ.copy()
    if previous is None:os.environ.pop('YSHOP_API_PORT',None)
    else:os.environ['YSHOP_API_PORT']=previous
    hx=env.get('YSHOP_HBUILDER_CLI','/Applications/HBuilderX.app/Contents/MacOS/cli')
    for name,args in [('npm',['npm','ci','--prefix',str(project),'--no-audit','--no-fund']),('open',[hx,'open']),('project',[hx,'project','open','--path',str(project)]),('compile',[hx,'launch','mp-weixin','--project',str(project),'--compile','true'])]:
        if execute(args,REPO,env,root/(name+'.log'),600):raise RuntimeError('UNIAPP_'+name.upper()+'_FAILED')
    if '编译成功' not in (root/'compile.log').read_text():raise RuntimeError('UNIAPP_SUCCESS_EVIDENCE_MISSING')
    output=project/'unpackage/dist/dev/mp-weixin'
    if not (output/'app.js').exists():raise RuntimeError('UNIAPP_DIST_MISSING')
    values=dict(line.split('=',1) for line in appconfig.read_text().splitlines() if '=' in line and not line.startswith('#'))
    configpath=output/'project.config.json';config=json.loads(configpath.read_text());config['appid']=values['WECHAT_APP_ID'].strip()
    configpath.write_text(json.dumps(config,ensure_ascii=False,indent=2)+'\n')
    # The user has explicitly authorized this official local-only debug setting in this chat.
    local={'setting':{'urlCheck':values.get('WECHAT_LOCAL_HTTP','0').strip()!='1'}}
    (output/'project.private.config.json').write_text(json.dumps(local)+'\n')
    compiled=''.join(f.read_text(errors='replace') for f in output.rglob('*.js'))
    if 'http://127.0.0.1:'+api_port+'/app-api' not in compiled:raise RuntimeError('COMPILED_API_BINDING_MISMATCH')
    report={**source_identity(),'result':'PASS','sourceRepo':str(REPO),'sourceHashes':json.loads((root/'source-hashes.json').read_text()),'project':str(output),'apiPort':env['YSHOP_API_PORT'],'appidConfigured':bool(config['appid']),'paymentRequests':{'value':None,'evidence':'COMPILATION_ONLY_NOT_MEASURED'}}
    (root/'report.json').write_text(json.dumps(report,indent=2))
    print(json.dumps({'result':'PASS','report':str(root/'report.json'),'project':str(output)}))
    return 0
if __name__=='__main__':
    try:sys.exit(main())
    except Exception as e:
        print(json.dumps({'result':'FAIL','errorType':type(e).__name__}))
        sys.exit(1)

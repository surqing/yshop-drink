"""Certify a frozen-source set without confusing macOS CI variants with Linux CI."""
import argparse
from collections import Counter
import json
from pathlib import Path
import re
from evidence import source_identity
from .contract import atomic_text, require, validate_receipt
from .registry import listing

def certify(reports,identity):
    require(len(reports)==3 and Counter(r.get('profile') for r in reports)==Counter({'local':2,'ci':1}),
            'ACCEPTANCE_REQUIRES_TWO_LOCAL_AND_ONE_CI')
    for key in ['runId','owner']:
        values=[report.get(key) for report in reports]
        require(all(isinstance(value,str) and re.fullmatch('[a-f0-9]{32}',value) for value in values)
                and len(set(values))==3,'ACCEPTANCE_INDEPENDENT_RUNS_REQUIRED')
    evidence=[]
    for report in reports:
        require(all(report.get(k)==v for k,v in identity.items()),'ACCEPTANCE_SOURCE_MISMATCH')
        require(report.get('status')=='PASSED' and report.get('result')=='PASS'
                and all(report.get(key) is True for key in ['complete','receiptValidated','sourceUnchanged','providerBoundariesVerified'])
                and report.get('cleanup')=='PASSED','ACCEPTANCE_INCOMPLETE_RUN')
        profile=report['profile'];plan=listing(profile)
        require(report.get('planned')==plan['scenarios'],'ACCEPTANCE_PLAN_MISMATCH')
        require(report.get('selection')=='core' and report.get('scope')==plan['scope'],'ACCEPTANCE_CORE_REQUIRED')
        require(report.get('hostPlatform')==('darwin' if profile=='local' else 'linux'),'ACCEPTANCE_PLATFORM_MISMATCH')
        require(report.get('miniStatus')==('PASSED' if profile=='local' else 'NOT_RUN')
                and report.get('threeEndCorePassed') is (profile=='local'),'ACCEPTANCE_SCOPE_MISMATCH')
        artifact=report.get('backendArtifact',{});support=report.get('testSupport',{})
        require(artifact.get('result')=='PASS' and all(artifact.get(k)==v and support.get(k)==v for k,v in identity.items())
                and bool(re.fullmatch('[a-f0-9]{64}',artifact.get('artifactHash','')))
                and support.get('productionArtifactHash')==artifact['artifactHash']
                and support.get('testSupportExcludedFromProductionJar') is True,'ACCEPTANCE_ARTIFACT_MISMATCH')
        cases=report.get('scenarios',[])
        require(bool(cases) and all(case.get('driversCleanup')=='PASSED' for case in cases),'ACCEPTANCE_DRIVER_CLEANUP_REQUIRED')
        # Revalidate every registered business check and current Mini witness, rather than trust a summary boolean.
        receipt={**identity,'runId':report['runId'],'owner':report['owner'],'complete':True,'status':'PASSED',
                 'startedAt':report.get('startedAt'),'endedAt':report.get('endedAt'),'driversCleanup':'PASSED',
                 'planned':[{'id':case['id'],'variant':case['variant']} for case in plan['scenarios']],
                 'scenarios':cases}
        checked=validate_receipt(receipt,plan['scenarios'],identity,report['runId'],report['owner'],report.get('startedAt',0))
        evidence.append({'runId':report['runId'],'profile':profile,'hostPlatform':report['hostPlatform'],
                         'checks':checked['checks'],'cleanup':'PASSED','artifactHash':artifact['artifactHash']})
    return {**identity,'status':'PASSED','scope':'two independent local core runs and Linux backend-admin core',
            'threeEndCorePassed':True,'linuxBackendAdminPassed':True,'qualityGateReady':False,
            'phase6DAllowed':False,'runs':evidence}

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--local',action='append',required=True)
    parser.add_argument('--ci',required=True);parser.add_argument('--output',required=True)
    args=parser.parse_args()
    reports=[json.loads(Path(path).read_text()) for path in [*args.local,args.ci]]
    result=certify(reports,source_identity())
    atomic_text(args.output,json.dumps(result,ensure_ascii=False,indent=2))
    print(json.dumps({'status':result['status'],'runs':len(result['runs'])}))

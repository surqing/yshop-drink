"""Validate the narrow regression exception without making failing suites green."""
import argparse
from collections import Counter
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import tarfile
import time
from evidence import source_identity
from e2e.contract import atomic_text, require
LEGACY_IDS = ('D002','D003','D005','D006','D007','D008')
SECURITY_IDS = ('D001','D004',*('D004-'+x for x in (
    'foreign-edit','foreign-default','foreign-delete','invalid-ids','missing-deleted',
    'own-crud','checkout-controls','admin-owner','concurrent-defaults','admin-races')),
    *('D001-'+x for x in ('foreign-phone','own-phone','empty-phone','concurrent-profile')))

def compare_defects(baseline,candidate,baseline_identity,identity):
    for report,expected in [(baseline,baseline_identity),(candidate,identity)]:
        require(all(report.get(k)==v for k,v in expected.items()),'REGRESSION_SOURCE_MISMATCH')
        require(report.get('complete') is True and report.get('sourceUnchanged') is True,
                'REGRESSION_INCOMPLETE_OR_CHANGED')
        require(report.get('status')=='FAILED','RAW_FAILURE_STATUS_REQUIRED')
        require(report.get('cleanup')=='PASSED','REGRESSION_CLEANUP_REQUIRED')
        receipt=report.get('cleanupReceipt',{})
        require(receipt.get('status')=='PASSED' and receipt.get('ownershipChecked') is True
                and receipt.get('owner')==report.get('owner') and receipt.get('runId')==report.get('runId')
                and receipt.get('containersRemoved')==2 and receipt.get('anonymousVolumesRemoved')==2
                and receipt.get('errors')==[], 'REGRESSION_RESOURCE_CLEANUP_REQUIRED')
        require(bool(re.fullmatch('[a-f0-9]{64}',report.get('backendArtifactHash',''))),'REGRESSION_ARTIFACT_REQUIRED')
        start,end=report.get('startedAt'),report.get('endedAt')
        require(type(start) in (int,float) and type(end) in (int,float) and 0<start<=end<=time.time()+5,
                'REGRESSION_TIMES_INVALID')
    require(baseline.get('harnessSource')==identity and baseline.get('harnessUnchanged') is True,
            'BASELINE_HARNESS_MISMATCH')
    for key in ('runId','owner'):
        values=[report.get(key) for report in (baseline,candidate)]
        require(all(isinstance(v,str) and re.fullmatch('[a-f0-9]{32}',v) for v in values)
                and len(set(values))==2,'REGRESSION_INDEPENDENT_RUNS_REQUIRED')
    tables=[]
    for report,ids in [(baseline,LEGACY_IDS),(candidate,(*LEGACY_IDS,*SECURITY_IDS))]:
        rows=report.get('cases')
        require(isinstance(rows,list) and all(isinstance(row,dict) for row in rows),'REGRESSION_CASES_INVALID')
        keys=[(row.get('id'),row.get('repetition')) for row in rows]
        require(all(type(row.get('repetition')) is int for row in rows)
                and Counter(keys)==Counter((name,n) for name in ids for n in (1,2)),
                'REGRESSION_CASES_MISMATCH')
        tables.append(dict(zip(keys,rows)))
    old,new=tables
    for name in LEGACY_IDS:
        for n in (1,2):
            before,after=old[name,n],new[name,n]
            require(before.get('status')==after.get('status')=='FAILED','LEGACY_FAILURE_STATUS_CHANGED')
            require(isinstance(before.get('actual'),dict) and bool(before['actual'])
                    and before['actual']==after.get('actual'),'LEGACY_BUSINESS_STATE_CHANGED')
            require(isinstance(before.get('assertion'),str) and bool(before['assertion'])
                    and before['assertion']==after.get('assertion'),'LEGACY_EXPECTATION_CHANGED')
    for name in SECURITY_IDS:
        for n in (1,2):
            require(new[name,n].get('status')=='PASSED' and bool(new[name,n].get('actual')),
                    'SECURITY_REGRESSION_NOT_PASSED')
    return {**identity,'regressionScopeStatus':'PASSED','rawCandidateStatus':'FAILED',
            'rawBaselineStatus':'FAILED','baselineSource':baseline_identity,
            'runIds':[baseline['runId'],candidate['runId']], 'retainedFailures':list(LEGACY_IDS),
            'legacyComparisons':12,'securityChecks':32,'qualityGateReady':False,'phase6DAllowed':False}


def git_identity(repo,revision):
    """Hash archived tracked bytes, never copy candidate files into the baseline."""
    sha=subprocess.check_output(['git','rev-parse','--verify',revision+'^{commit}'],cwd=repo,text=True).strip()
    data=subprocess.check_output(['git','archive','--format=tar',sha],cwd=repo)
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        files={member.name:archive.extractfile(member).read() for member in archive.getmembers() if member.isfile()}
        require(all(member.isdir() or member.isfile() for member in archive.getmembers()),'BASELINE_NONREGULAR_ASSET')
    digest=hashlib.sha256()
    for name in sorted(files):digest.update(name.encode()+b'\0'+files[name])
    return {'sourceSha':sha,'sourceDigest':digest.hexdigest()}


def main():
    parser=argparse.ArgumentParser()
    for name in ('baseline','candidate','base','output'):parser.add_argument('--'+name,required=True)
    args=parser.parse_args();repo=Path(__file__).resolve().parents[2]
    paths=[Path(args.baseline),Path(args.candidate)]
    result=compare_defects(*(json.loads(p.read_text()) for p in paths),git_identity(repo,args.base),source_identity(repo))
    result['reports']=[{'path':str(p.resolve()),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in paths]
    atomic_text(args.output,json.dumps(result,indent=2)+'\n')
    print(json.dumps({k:result[k] for k in ('regressionScopeStatus','rawCandidateStatus','retainedFailures')}))

if __name__=='__main__':main()

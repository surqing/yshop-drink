"""Copy an inspected allowlist of summaries; never upload an entire private run."""
import argparse
import json
from pathlib import Path
import re
import shutil

FILES=('report.json','report.html','summary.md','junit.xml')
PRIVATE_KEYS={'accounts','password','secret','token','accessToken','refreshToken','authorization','headers',
              'requestBody','responseBody','loginCode','phoneCode','commandDir','smsOutbox','apiKey','apiSecret'}
FORBIDDEN={re.sub('[^a-z]','',key.lower()) for key in PRIVATE_KEYS}
SENSITIVE=re.compile(r'Bearer\s+\S+|-----BEGIN [A-Z ]*PRIVATE KEY-----|\bgh[pousr]_[A-Za-z0-9]+|'
                     r'\b(?:password|api[_-]?secret|access[_-]?token|refresh[_-]?token)\s*[=:]\s*\S+',re.I)

def inspect(value):
    if isinstance(value,dict):
        for key,item in value.items():
            if re.sub('[^a-z]','',key.lower()) in FORBIDDEN:raise ValueError('UNSAFE_PUBLIC_ARTIFACT')
            inspect(item)
    elif isinstance(value,list):
        for item in value:inspect(item)
    elif isinstance(value,str) and SENSITIVE.search(value):raise ValueError('UNSAFE_PUBLIC_ARTIFACT')

def safe_bundle(run,destination):
    run=Path(run);destination=Path(destination)
    for name in FILES:
        file=run/name
        if file.is_symlink() or not file.is_file():raise ValueError('PUBLIC_ARTIFACT_REQUIRED')
        text=file.read_text()
        if SENSITIVE.search(text):raise ValueError('UNSAFE_PUBLIC_ARTIFACT')
        if name=='report.json':inspect(json.loads(text))
    destination.mkdir(parents=True,mode=0o700,exist_ok=False)
    for name in FILES:
        shutil.copyfile(run/name,destination/name);(destination/name).chmod(0o600)
    return destination

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--runs',required=True);parser.add_argument('--output',required=True)
    args=parser.parse_args();runs=list(Path(args.runs).glob('*/report.json'))
    if len(runs)!=1:raise SystemExit('PUBLIC_RUN_MISSING_OR_AMBIGUOUS')
    safe_bundle(runs[0].parent,args.output)

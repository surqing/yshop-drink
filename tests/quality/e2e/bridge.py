"""Private subprocess command bridge to the owned application's real Spring services."""
import argparse
import json
import os
from pathlib import Path
import re
import sys
import time
import uuid
from .contract import atomic_text


def validate_context(context):
    owner=context.get('owner','')
    mailbox=Path(context.get('commandDir','')).resolve()
    if not re.fullmatch(r'[a-f0-9]{32}',owner) or mailbox.name!='commands-'+owner:
        raise ValueError('BRIDGE_CONTEXT_INVALID')
    if not mailbox.is_dir() or mailbox.is_symlink() or mailbox.stat().st_mode&0o077:
        raise ValueError('BRIDGE_CONTEXT_INVALID')
    return mailbox


def invoke(context, action, ids, timeout=60):
    mailbox=validate_context(context)
    if action not in ('complete','token-lifetime','printer-counters','printer-state','order-print-poll','simulate-print-crash'):
        raise ValueError('BRIDGE_ACTION_UNKNOWN')
    if action=='complete' and not re.fullmatch(r'[A-Za-z0-9_-]{1,64}',str(ids.get('orderId',''))):
        raise ValueError('ENTITY_ID_INVALID')
    if action=='token-lifetime' and ids.get('seconds') not in (2,3600):
        raise ValueError('INVALID_TEST_TOKEN_LIFETIME')
    if action=='printer-state' and ids.get('state') not in ('ONLINE','OFFLINE','PAPER_OUT','UNKNOWN'):
        raise ValueError('INVALID_TEST_PRINTER_STATE')
    if action=='simulate-print-crash' and (isinstance(ids.get('jobId'),bool) or not re.fullmatch(r'[1-9][0-9]{0,17}',str(ids.get('jobId','')))):
        raise ValueError('ENTITY_ID_INVALID')
    command_id=uuid.uuid4().hex
    command={**ids,'owner':context['owner'],'commandId':command_id,'action':action}
    request=mailbox/'requests'/(command_id+'.json')
    response=mailbox/'responses'/request.name
    atomic_text(request,json.dumps(command))
    deadline=time.monotonic()+timeout
    while not response.is_file():
        if time.monotonic()>=deadline:raise TimeoutError('BRIDGE_COMMAND_TIMEOUT')
        time.sleep(.1)
    reply=json.loads(response.read_text())
    if reply.get('owner')!=context['owner'] or reply.get('commandId')!=command_id:
        raise ValueError('BRIDGE_REPLY_FOREIGN')
    if reply.get('status')!='PASSED':raise RuntimeError('BRIDGE_COMMAND_FAILED')
    return reply


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--context',required=True);parser.add_argument('--action',required=True);parser.add_argument('--ids',required=True)
    args=parser.parse_args()
    try:
        reply=invoke(json.loads(Path(args.context).read_text()),args.action,json.loads(args.ids))
        print(json.dumps(reply));return 0
    except Exception as error:
        print(json.dumps({'status':'FAILED','reasonType':type(error).__name__}),file=sys.stderr);return 1

if __name__=='__main__':sys.exit(main())

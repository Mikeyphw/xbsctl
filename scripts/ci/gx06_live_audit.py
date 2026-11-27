#!/usr/bin/env python3
"""Read-only GitHub stack/merge-readiness and exact-run evidence audit for GX06.

Use separately from Devtool's authoritative delivery-session/CI state. This
script never merges, changes labels, comments, dispatches or upgrades release.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from gx06_stack_gate import (REPO, GX06_BRANCH, GX05_BRANCH, RUN_REQUIRED,
    SHA, SHA256, LOCK, Refused, need, validated_lock, load_bounded, git)

BASE='/repos/'+REPO
CANONICAL='Devtool XBS Go and Android candidates'
COMPAT='XBS GX03 hosted API26 / API34 compatibility'
REQUIRED_JOBS={CANONICAL:{'go-contract','android-companion-jvm','android-companion-api36'},
               COMPAT:{'android-api26','android-api34'}}

class Client:
    def get(self, path):
        need(isinstance(path,str) and path.startswith(BASE+'/') and
             not any(c in path for c in ('\\','\n','\r','\0',' ')) and len(path)<500,
             'GX06_GITHUB_READ_PATH_UNSAFE')
        try:
            p=subprocess.run(['gh','api',path],capture_output=True,text=True,timeout=70,check=False)
        except (OSError,subprocess.TimeoutExpired) as e:
            raise Refused('GX06_GITHUB_READ_FAILED '+path) from e
        need(p.returncode==0,'GX06_GITHUB_READ_FAILED '+path)
        need(0<len(p.stdout)<=8_000_000,'GX06_GITHUB_RESPONSE_MISSING_OR_OVERSIZED')
        try: return json.loads(p.stdout)
        except ValueError as e: raise Refused('GX06_GITHUB_RESPONSE_NOT_JSON') from e

def require_pr(pr,record):
    need(type(pr) is dict and pr.get('number')==record['pr'] and pr.get('state')=='open' and
         not pr.get('merged',False),'GX06_PR_CLOSED_OR_MISSING '+str(record['pr']))
    h=pr.get('head',{});b=pr.get('base',{})
    need(type(h) is dict and type(b) is dict and
         h.get('sha')==record['head'] and h.get('ref')==record['branch'] and
         (h.get('repo') or {}).get('full_name')==REPO and
         b.get('ref')==record['base_branch'] and b.get('sha')==record['base_sha'],
         'GX06_PR_SOURCE_OR_BASE_DRIFT '+str(record['pr']))
    need(pr.get('mergeable') is not False,'GX06_PR_PROVIDER_CONFLICT_REPORTED '+str(record['pr']))
    return {'pr':record['pr'],'head':h['sha'],'base':b['sha'],'draft':pr.get('draft') is True,
            'mergeability':'PROVIDER_TRUE_NOT_FINAL_MERGE_PROOF' if pr.get('mergeable') is True else 'UNKNOWN'}

def require_run(run,entry,record):
    need(type(run) is dict and run.get('id')==entry['id'] and
         run.get('workflow_id')==entry['workflow_id'] and
         run.get('name')==entry['workflow'] and
         run.get('head_sha')==record['head'] and run.get('head_branch')==record['branch'] and
         run.get('event')=='pull_request' and
         run.get('status')=='completed' and run.get('conclusion')=='success' and
         type(run.get('run_attempt')) is int and run['run_attempt']>=1,
         'GX06_RUN_SOURCE_OR_RESULT_MISMATCH '+str(entry['id']))
    return run['run_attempt']

def require_jobs(payload, required):
    need(type(payload) is dict and type(payload.get('jobs')) is list,
         'GX06_JOB_RESPONSE_NOT_VALID')
    jobs=payload['jobs'];seen=set()
    for j in jobs:
        need(type(j) is dict and isinstance(j.get('name'),str) and
             j['name'] not in seen,'GX06_DUPLICATE_OR_INVALID_JOB')
        seen.add(j['name'])
    need(required<=seen,'GX06_REQUIRED_JOBS_MISSING '+repr(sorted(required-seen)))
    for j in jobs:
        if j['name'] in required:
            need(j.get('status')=='completed' and j.get('conclusion')=='success',
                 'GX06_REQUIRED_JOB_FAILED_OR_SKIPPED '+j['name'])
    return sorted(required)

def require_artifacts(data, run_id, attempt):
    need(type(data) is dict and type(data.get('artifacts')) is list,
         'GX06_ARTIFACT_RESPONSE_INVALID')
    rows=data['artifacts']
    need(len(rows)>=1 and type(data.get('total_count')) is int and
         data['total_count']==len(rows) and len(rows)<=100,
         'GX06_ARTIFACTS_MISSING_PAGINATED_OR_DUPLICATED')
    seen=set();digests=set();out=[]
    for a in rows:
        need(type(a) is dict and type(a.get('name')) is str and
             str(run_id)+'-'+str(attempt) in a['name'] and a['name'] not in seen and
             a.get('expired') is False and type(a.get('size_in_bytes')) is int and
             a['size_in_bytes']>0 and type(a.get('id')) is int and a['id']>0,
             'GX06_ARTIFACT_IDENTITY_SIZE_OR_EXPIRY_INVALID')
        digest=a.get('digest')
        need(type(digest) is str and digest.startswith('sha256:') and
             SHA256.fullmatch(digest[7:]) is not None and digest not in digests,
             'GX06_ARTIFACT_DIGEST_MISSING_OR_DUPLICATED')
        seen.add(a['name']);digests.add(digest)
        out.append({'id':a['id'],'name':a['name'],'size':a['size_in_bytes'],
                    'github_provider_digest':digest,'download_sha256_verified':False})
    return out

def verify_historical_stack(lock,client):
    stack=validated_lock(lock)
    result=[]
    for item in stack:
        pr=client.get(BASE+'/pulls/'+str(item['pr']))
        record=require_pr(pr,item)
        runs=[]
        for declared in item['runs']:
            run_id=declared['id']
            run=client.get(BASE+'/actions/runs/'+str(run_id))
            attempt=require_run(run,declared,item)
            job_data=client.get(BASE+'/actions/runs/'+str(run_id)+'/attempts/'+str(attempt)+'/jobs?per_page=100')
            required=require_jobs(job_data,REQUIRED_JOBS[declared['workflow']])
            artifacts=require_artifacts(client.get(BASE+'/actions/runs/'+str(run_id)+'/artifacts?per_page=100'),run_id,attempt)
            runs.append({'run_id':run_id,'attempt':attempt,'workflow_id':declared['workflow_id'],
                         'workflow':declared['workflow'],'jobs':required,'artifacts':artifacts})
        record['runs']=runs
        result.append(record)
    return result

def verify_current_gx06(client,head):
    need(SHA.fullmatch(head or '') is not None,'GX06_CURRENT_HEAD_INVALID')
    # Search open PRs by exact branch; read-only, bounded pagination.
    d=client.get(BASE+'/pulls?state=open&per_page=100')
    need(type(d) is list and 1<=len(d)<=100,'GX06_PR_LIST_UNAVAILABLE_OR_PAGINATED')
    matched=[p for p in d if (p.get('head') or {}).get('ref')==GX06_BRANCH]
    need(len(matched)==1,'GX06_CURRENT_PR_MISSING_OR_AMBIGUOUS')
    p=matched[0];number=p.get('number')
    need(type(number) is int,'GX06_CURRENT_PR_NUMBER_INVALID')
    pr=client.get(BASE+'/pulls/'+str(number))
    record={'pr':number,'head':head,'branch':GX06_BRANCH,'base_branch':GX05_BRANCH,
            'base_sha':(pr.get('base') or {}).get('sha')}
    need(record['base_sha']==lock_gx05_head(),'GX06_CURRENT_PR_BASE_DRIFT')
    proven=require_pr(pr,record)
    d=client.get(BASE+'/actions/runs?branch='+GX06_BRANCH+'&event=pull_request&per_page=100')
    need(type(d) is dict and type(d.get('workflow_runs')) is list and
         len(d['workflow_runs'])<=100 and type(d.get('total_count')) is int and
         d['total_count']<=100,'GX06_CURRENT_RUN_LIST_PAGINATED_OR_UNAVAILABLE')
    got={}
    for row in d['workflow_runs']:
        if row.get('head_sha')!=head:continue
        name=row.get('name');wid=row.get('workflow_id')
        if name not in RUN_REQUIRED or wid!=RUN_REQUIRED[name]:continue
        # Multiple attempts or re-runs require operator selection; never guess.
        need(name not in got,'GX06_CURRENT_MULTIPLE_RUNS_AMBIGUOUS '+name)
        got[name]=row
    need(set(got)==set(RUN_REQUIRED),'GX06_CURRENT_REQUIRED_WORKFLOWS_MISSING')
    proven['runs']=[]
    for name in sorted(got):
        row=got[name];rid=row.get('id')
        need(type(rid) is int and rid>0,'GX06_CURRENT_RUN_ID_INVALID')
        detail=client.get(BASE+'/actions/runs/'+str(rid))
        attempt=require_run(detail,{'id':rid,'workflow_id':RUN_REQUIRED[name], 'workflow':name},
                            {'head':head,'branch':GX06_BRANCH})
        required=require_jobs(client.get(BASE+'/actions/runs/'+str(rid)+'/attempts/'+str(attempt)+'/jobs?per_page=100'),REQUIRED_JOBS[name])
        artifacts=require_artifacts(client.get(BASE+'/actions/runs/'+str(rid)+'/artifacts?per_page=100'),rid,attempt)
        proven['runs'].append({'run_id':rid,'attempt':attempt,'workflow':name,
                               'jobs':required,'artifacts':artifacts})
    return proven

def lock_gx05_head():
    from gx06_stack_gate import GX05_HEAD
    return GX05_HEAD

def read_merge_preview(root:Path, main:str, head:str):
    """Read-only git merge-tree; never applies a merge or rewrites refs."""
    need(SHA.fullmatch(main or '') is not None and SHA.fullmatch(head or '') is not None,
         'GX06_MERGE_PREVIEW_SHA_INVALID')
    before=git(root,'rev-parse','HEAD')
    need(before==head,'GX06_MERGE_PREVIEW_WRONG_HEAD')
    try:
        p=subprocess.run(['git','-C',str(root),'merge-tree','--write-tree',main,head],
                         capture_output=True,text=True,timeout=90,check=False)
    except (OSError,subprocess.TimeoutExpired) as e: raise Refused('GX06_MERGE_TREE_UNAVAILABLE') from e
    need(p.returncode in (0,1),'GX06_MERGE_TREE_CANNOT_DETERMINE')
    need(git(root,'rev-parse','HEAD')==before and
         not git(root,'status','--porcelain','--untracked-files=all'),
         'GX06_MERGE_PREVIEW_MUTATED_SOURCE')
    return {'source_sha':head,'main_sha':main,'conflicts':p.returncode==1,
            'merge_tree_hash':p.stdout.splitlines()[0] if p.returncode==0 and
                p.stdout.splitlines() and SHA.fullmatch(p.stdout.splitlines()[0]) else None,
            'merge_applied':False,'aggregate_ci_qualified':False}

def audit_live(root:Path,client,with_current=False,merge_preview=False):
    lock=load_bounded(root/LOCK,'LOCK',200000)
    history=verify_historical_stack(lock,client)
    current=git(root,'rev-parse','HEAD')
    # GH current main must be read fresh; the baseline snap is informational.
    main_obj=client.get(BASE+'/branches/main')
    main=((main_obj.get('commit') or {}).get('sha') if isinstance(main_obj,dict) else None)
    need(type(main) is str and SHA.fullmatch(main) is not None,'GX06_MAIN_HEAD_NOT_VERIFIED')
    checked={'schema':'xbs-gx06-live-stack/v1','repository':REPO,
             'stack':history,'main_current_sha':main,
             'main_at_inspection':lock['main_at_inspection'],
             'main_moved_since_inspection':main!=lock['main_at_inspection'],
             'source_sha':current,'r10a_applied':False,
             'physical_root_qualified':False,'release_qualified':False,
             'auto_merge_approved':False,'original_cases_accepted':False,
             'artifact_digests':'GITHUB_PROVIDER_REPORTED_ONLY_NOT_INDEPENDENTLY_REHASHED'}
    if with_current: checked['gx06_pr']=verify_current_gx06(client,current)
    else: checked['gx06_pr']='NOT_CHECKED'
    checked['merge_readiness']=(read_merge_preview(root,main,current) if merge_preview
          else {'state':'NOT_RUN_EXACT_AGGREGATE_MERGE_PREVIEW_REQUIRED','merge_applied':False})
    checked['roadmap_handback']='BLOCKED_UNTIL_ORIGINAL_GATES_AND_PHYSICAL_SIGNING'
    return checked

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--repo',default='.')
    p.add_argument('--current-gx06',action='store_true')
    p.add_argument('--merge-preview',action='store_true')
    p.add_argument('--output',help='Create a new report exclusively; never overwrite')
    args=p.parse_args()
    try:
        root=Path(args.repo).resolve(strict=True)
        report=audit_live(root,Client(),args.current_gx06,args.merge_preview)
        encoded=(json.dumps(report,indent=2,sort_keys=True)+'\n').encode('utf-8')
        if args.output:
            out=Path(args.output).expanduser().absolute()
            need(out.parent.is_dir() and not out.is_symlink() and not out.exists(),
                 'GX06_REPORT_OUTPUT_ALREADY_EXISTS_OR_PARENT_MISSING')
            fd=os.open(out,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
            with os.fdopen(fd,'wb') as f: f.write(encoded);f.flush();os.fsync(f.fileno())
            print('GX06_LIVE_REPORT_CREATED '+str(out)+' sha256='+hashlib.sha256(encoded).hexdigest(),flush=True)
        else: sys.stdout.buffer.write(encoded)
        print('GX06_LIVE_READ_ONLY_COMPLETE release_qualified=false merge_applied=false',flush=True)
        return 0
    except (Refused,KeyError,ValueError,OSError,subprocess.SubprocessError) as e:
        print('GX06_LIVE_REFUSED '+str(e),file=sys.stderr,flush=True)
        return 2

if __name__=='__main__': raise SystemExit(main())

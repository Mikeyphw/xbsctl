#!/usr/bin/env python3
"""R07A: observed GitHub source/attempt/artifact gate. No dispatch or mutation.

Uses gh CLI's authenticated API, real job logs and artifact bytes. Prints only
summaries and selected error lines; never asserts physical/root qualification.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import zipfile

REPO='Mikeyphw/xbsctl'
BRANCH='feature/xbs-r07a-ci-policy-20261114'
WORKFLOW='Devtool XBS Go and Android candidates'
REQUIRED={
    'go-contract': ('R06B HF03 actual Go test case counts','R06B source-pinned Devtool runtime qualification','XBS contracts','Source-bound final seal','Android module candidate','Module checksum'),
    'android-companion-jvm': ('R05C HF04 complete repository Python CI suite','Android JVM unit suite','R06B HF03 actual Android JVM case counts','Publish SHA-bound Android JVM counts'),
    'android-companion-api36': ('R05C source and workflow contracts','Android API36 rootless instrumentation','Publish SHA-bound debug candidate, no logs or private device state'),
}
class Refused(RuntimeError):pass

def need(ok,msg):
    if not ok:raise Refused(msg)

def gh(*args,timeout=100):
    try:r=subprocess.run(['gh',*args],text=True,encoding='utf-8',errors='replace',capture_output=True,timeout=timeout)
    except (OSError,subprocess.TimeoutExpired) as e:raise Refused('GH_INVOCATION_FAILED '+str(args[:3])) from e
    if r.returncode:
        safe=' '.join((r.stderr or '').splitlines()[-2:])[:500]
        raise Refused('GH_COMMAND_FAILED '+' '.join(args[:3])+' rc='+str(r.returncode)+' '+safe)
    return r.stdout

def api(endpoint):
    try:return json.loads(gh('api',endpoint))
    except json.JSONDecodeError as e:raise Refused('GH_API_JSON_INVALID '+endpoint[:90]) from e

def choose(runs,sha,branch):
    need(re.fullmatch(r'[a-f0-9]{40}',sha)!=None,'SOURCE_SHA_INVALID')
    matches=[r for r in runs if r.get('head_sha')==sha and r.get('head_branch')==branch
             and r.get('event')=='pull_request' and r.get('name')==WORKFLOW]
    need(bool(matches),'NO_REAL_PR_RUN_AT_EXACT_SHA')
    latest=max(matches,key=lambda r:r.get('id',0))
    need(latest.get('status')=='completed' and latest.get('conclusion')=='success','LATEST_RUN_NOT_GREEN '+str(latest.get('id'))+' status='+str(latest.get('status'))+' conclusion='+str(latest.get('conclusion')))
    need(type(latest.get('run_attempt')) is int and latest['run_attempt']>0,'RUN_ATTEMPT_MISSING')
    return latest

def qualify_jobs(jobs):
    relevant={}
    for job in jobs:
        name=job.get('name')
        if name in REQUIRED:
            need(name not in relevant,'DUPLICATE_REQUIRED_JOB '+name)
            relevant[name]=job
    need(set(relevant)==set(REQUIRED),'MISSING_REQUIRED_JOB '+','.join(sorted(set(REQUIRED)-set(relevant))))
    for name,expected in REQUIRED.items():
        job=relevant[name]
        need(job.get('status')=='completed' and job.get('conclusion')=='success','REQUIRED_JOB_NOT_GREEN '+name)
        need(type(job.get('id')) is int,'JOB_ID_MISSING '+name)
        steps=job.get('steps',[])
        for label in expected:
            matching=[s for s in steps if s.get('name')==label]
            need(len(matching)==1 and matching[0].get('conclusion')=='success','STEP_NOT_GREEN '+name+'/'+label)
    return relevant

def counts_from_logs(logs):
    patterns={
        'go':('go-contract',r'XBS_R06B_HF03_ACTUAL_CASE_COUNTS_PASS dist/r06b-go-test-counts\.json cases=(\d+)'),
        'jvm':('android-companion-jvm',r'XBS_R06B_HF03_ACTUAL_CASE_COUNTS_PASS android-evidence/r06b-jvm-test-counts\.json cases=(\d+)'),
        'python':('android-companion-jvm',r'XBS_R05C_HF04_FULL_PYTHON_CI_ALL_TESTS_PASS files=\d+ cases=(\d+)'),
        'api36':('android-companion-api36',r'XBS_R05C_API36_INSTRUMENTATION_CASES_PASS\s+(\d+)'),
    }
    result={}
    for key,(job,pat) in patterns.items():
        vals=[int(v) for v in re.findall(pat,logs[job])]
        if key=='python':
            # The self-test proves fake test counts, but the full suite is the max.
            need(len(vals)>=1,'MISSING_REAL_PYTHON_COUNT')
            value=max(vals)
        else:
            need(len(vals)==1,'MISSING_OR_AMBIGUOUS_COUNT '+key)
            value=vals[0]
        need(value>= (16 if key=='api36' else 1),'EMPTY_OR_PARTIAL_SUITE '+key)
        result[key]=value
    need(bool(re.search(r'XBS_R06B_ACTUAL_NATIVE_PLAN_AND_PREFLIGHT_PASS nodes=6 affected_cases=3',logs['go-contract'])),
         'NATIVE_PLAN_PREFLIGHT_NOT_OBSERVED')
    return result

def artifact_list(items,run_id,attempt):
    required={
        'module':f'xbs-android-module-{run_id}-{attempt}',
        'jvm':f'xbs-android-jvm-counts-{run_id}-{attempt}',
        'api36':f'xbs-companion-r05c-api36-{run_id}-{attempt}',
    }
    result={}
    for kind,name in required.items():
        matches=[x for x in items if x.get('name')==name]
        need(len(matches)==1,'MISSING_OR_DUPLICATE_ARTIFACT '+name)
        x=matches[0]
        need(not x.get('expired') and type(x.get('size_in_bytes')) is int and x['size_in_bytes']>0,'INVALID_ARTIFACT '+name)
        result[kind]=x
    return result

def read_json_file(folder,name):
    found=list(folder.rglob(name))
    need(len(found)==1 and found[0].is_file() and not found[0].is_symlink(),'ARTIFACT_RECEIPT_MISSING '+name)
    try:return json.loads(found[0].read_text())
    except (UnicodeDecodeError,json.JSONDecodeError) as e:raise Refused('ARTIFACT_RECEIPT_INVALID '+name) from e

def check_receipt(receipt,sha,run_id,attempt,min_cases):
    need(isinstance(receipt,dict),'RECEIPT_NONOBJECT')
    need(receipt.get('xbs_source_sha')==sha,'RECEIPT_WRONG_SOURCE_SHA')
    need(receipt.get('github_run_id')==str(run_id),'RECEIPT_WRONG_RUN')
    need(receipt.get('github_run_attempt')==str(attempt),'RECEIPT_WRONG_ATTEMPT')
    need(type(receipt.get('test_cases')) is int and receipt['test_cases']==min_cases,'RECEIPT_CASE_COUNT_MISMATCH')
    need(receipt.get('failed_cases')==0,'RECEIPT_FAILED_CASES')
    need(receipt.get('unexpected_skipped_cases',0)==0,'RECEIPT_UNEXPECTED_SKIPS')

def verify_artifacts(folder,sha,run_id,attempt,count):
    go=read_json_file(folder/'module','r06b-go-test-counts.json')
    check_receipt(go,sha,run_id,attempt,count['go'])
    native=read_json_file(folder/'module','r06b-devtool-qualification.json')
    need(native.get('schema')=='xbs-r06b-devtool-runtime/v1' and
         native.get('xbs_source_sha')==sha and native.get('github_run_id')==str(run_id)
         and native.get('github_run_attempt')==str(attempt),'NATIVE_RECEIPT_NOT_BOUND')
    need(native.get('physical_root_qualified') is False and native.get('release_qualified') is False,
         'PHYSICAL_OR_RELEASE_FALSE_CLAIM')
    jvm=read_json_file(folder/'jvm','r06b-jvm-test-counts.json')
    check_receipt(jvm,sha,run_id,attempt,count['jvm'])
    def find_one(base,name):
        files=list(base.rglob(name))
        need(len(files)==1 and files[0].is_file(),'MISSING_ARTIFACT_FILE '+name)
        return files[0]
    module=find_one(folder/'module','XtremeBS-go.zip')
    manifest=find_one(folder/'module','SHA256SUMS')
    digest=hashlib.sha256(module.read_bytes()).hexdigest()
    need(any(line.split()[0]==digest and line.strip().endswith('XtremeBS-go.zip') for line in manifest.read_text().splitlines() if line.strip()),'MODULE_SHA256_MISMATCH')
    apk=find_one(folder/'api36','xbs-companion-r05c-api36-debug.apk')
    apk_digest=hashlib.sha256(apk.read_bytes()).hexdigest()
    apk_sha=find_one(folder/'api36','SHA256SUMS')
    need(any(line.split()[0]==apk_digest and line.strip().endswith(apk.name) for line in apk_sha.read_text().splitlines() if line.strip()),'API36_APK_SHA256_MISMATCH')
    actual_sha=find_one(folder/'api36','SOURCE_SHA').read_text().strip()
    need(actual_sha==sha,'API36_SOURCE_SHA_MISMATCH')
    need(zipfile.is_zipfile(module) and zipfile.is_zipfile(apk),'MODULE_OR_APK_NOT_ZIP_CONTAINER')
    return {'module_sha256':digest,'api36_apk_sha256':apk_digest}

def diagnose(run_id,repo):
    try:
        jobs=api(f'repos/{repo}/actions/runs/{run_id}/jobs?per_page=100').get('jobs',[])
        for job in jobs:
            if job.get('conclusion') not in {'failure','cancelled','timed_out','action_required'}:continue
            print('XBS_R07A_JOB_FAILURE '+job.get('name','?')+' '+str(job.get('conclusion')))
            for step in job.get('steps',[]):
                if step.get('conclusion')=='failure':print('XBS_R07A_STEP_FAILURE '+str(step.get('name')))
            try:
                log=gh('run','view',str(run_id),'--job',str(job['id']),'--log','-R',repo,timeout=120)
                lines=[line for line in log.splitlines() if re.search(r'REFUSED|##\[error\]|Traceback|AssertionError|FAIL(?:ED)?|error:',line,re.I)]
                for line in lines[-18:]:print('XBS_R07A_LOG '+line[-350:])
            except Refused as e:print('XBS_R07A_DIAG_LOG_UNAVAILABLE '+str(e))
    except Refused as e:print('XBS_R07A_DIAG_UNAVAILABLE '+str(e))

def observed_qualification(repo,branch,sha,pr):
    print('XBS_R07A_RAW_GH_PR_CHECKS_BEGIN',flush=True)
    raw=subprocess.run(['gh','pr','checks',str(pr),'-R',repo],capture_output=True,text=True,errors='replace')
    print((raw.stdout or '')[-18000:] or '(no checks returned)')
    if raw.stderr:print('XBS_R07A_GH_CHECKS_STDERR '+raw.stderr[-800:])
    print('XBS_R07A_RAW_GH_PR_CHECKS_END',flush=True)
    # A newly-created PR run may not appear in the API immediately. Never
    # dispatch a substitute run; re-read the exact expected source identity.
    matching=[]
    for discovery in range(8):
        runs=api(f'repos/{repo}/actions/runs?event=pull_request&per_page=100').get('workflow_runs',[])
        matching=[r for r in runs if r.get('head_sha')==sha and r.get('head_branch')==branch and r.get('name')==WORKFLOW]
        if matching:break
        print('XBS_R07A_WAITING_FOR_OBSERVED_PR_RUN attempt='+str(discovery+1),flush=True)
        time.sleep(5)
    need(matching,'NO_EXACT_SHA_PR_WORKFLOW_FOUND_AFTER_BOUNDED_POLL')
    run=max(matching,key=lambda r:r.get('id',0))
    run_id=run['id'];print('XBS_R07A_OBSERVED_RUN '+str(run_id)+' attempt='+str(run.get('run_attempt')),flush=True)
    if run.get('status')!='completed':
        print('XBS_R07A_GH_WATCH_REAL_RUN '+str(run_id),flush=True)
        try:gh('run','watch',str(run_id),'-R',repo,'--exit-status',timeout=3600)
        except Refused as e:print('XBS_R07A_WATCH_FAILED '+str(e),flush=True)
    # Re-read *this exact run*, not an unrelated latest run.
    final=api(f'repos/{repo}/actions/runs/{run_id}')
    try:
        need(final.get('head_sha')==sha,'RUN_SOURCE_DRIFT')
        need(final.get('status')=='completed' and final.get('conclusion')=='success','HOSTED_RUN_NOT_GREEN')
        need(final.get('run_attempt')==run.get('run_attempt'),'RUN_ATTEMPT_CHANGED_DURING_WATCH')
        jobs=api(f'repos/{repo}/actions/runs/{run_id}/jobs?per_page=100').get('jobs',[])
        selected=qualify_jobs(jobs)
        logs={name:gh('run','view',str(run_id),'--job',str(job['id']),'--log','-R',repo,timeout=120) for name,job in selected.items()}
        counts=counts_from_logs(logs)
        items=api(f'repos/{repo}/actions/runs/{run_id}/artifacts?per_page=100').get('artifacts',[])
        names=artifact_list(items,run_id,final['run_attempt'])
        with tempfile.TemporaryDirectory(prefix='xbs-r07a-artifacts-') as temp:
            path=Path(temp)
            for kind,item in names.items():
                dest=path/kind;dest.mkdir()
                gh('run','download',str(run_id),'--name',item['name'],'--dir',str(dest),'-R',repo,timeout=180)
            hashes=verify_artifacts(path,sha,run_id,final['run_attempt'],counts)
        print('XBS_R07A_ACTUAL_GITHUB_QUALIFIED '+json.dumps({
            'source_sha':sha,'run_id':run_id,'run_attempt':final['run_attempt'],
            'real_case_counts':counts,'verified_artifact_sha256':hashes,
            'scope':'github_hosted_api36_no_physical_root_no_release_signoff',
        },sort_keys=True),flush=True)
    except Refused:
        diagnose(run_id,repo)
        raise

if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--repo',default=REPO);p.add_argument('--branch',default=BRANCH)
    p.add_argument('--sha',required=True);p.add_argument('--pr',type=int,required=True)
    args=p.parse_args()
    try:observed_qualification(args.repo,args.branch,args.sha,args.pr)
    except Refused as e:
        print('XBS_R07A_REFUSED '+str(e),file=sys.stderr)
        raise SystemExit(2)

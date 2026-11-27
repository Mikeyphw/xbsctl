#!/usr/bin/env python3
"""XBS GX02 reusable source/run/attempt/PR/artifact and recovery control plane.

Pure standard-library logic; no external commands, network, Android, or repository writes.
Callers handle Git and GitHub CLI. Always fail closed on ambiguous identity.
"""
from __future__ import annotations
import dataclasses
import datetime as dt
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import tempfile
import zipfile

REPO='Mikeyphw/xbsctl'
WORKFLOW_ID=386774409
WORKFLOW_NAME='Devtool XBS Go and Android candidates'
BASE_SHA='568ba5dd140164bf179b91dd24c2557f114f0aa6'
BASE_BRANCH='feature/xbs-gx01-evidence-reconciliation-20261114'
BRANCH='feature/xbs-gx02-devtool-ci-reliability-20261114'
SOURCE_BLOBS={
 '.github/workflows/devtool-xbs-ci.yml':'c3bbdfe8f85f9232332026cec0e627fd4c107a0f',
 '.devtool.toml':'499a5baa27f9f8ef2221397e0652326c24c2bcfd',
 'docs/engineering/XBS_GX_TRACKER.json':'',
 'scripts/ci/gx01_ci_verify.py':'3a474626c4c957e51485e3b63aa0e964f7dc9b13',
}
EXPECTED_JOBS={'go-contract','android-companion-jvm','android-companion-api36'}
OPTIONAL_JOBS={'android-companion-debug'}
GX01_STEP='GX01 independent ledger audit and adversarial regressions'
GX02_STEP='GX02 source-bound Devtool and CI reliability acceptance'
STEP_FRAGMENT='''      - name: GX02 source-bound Devtool and CI reliability acceptance
        env:
          XBS_EXPECTED_HEAD_SHA: ${{ github.event.pull_request.head.sha || github.sha }}
        run: |
          python3 -B scripts/ci/gx02_ci_verify.py .
          python3 -B scripts/ci/test_gx02_control.py
'''

class Refused(RuntimeError): pass
def need(ok, reason):
    if not ok: raise Refused(reason)
def sha256(data:bytes)->str:return hashlib.sha256(data).hexdigest()
def canonical(data:object)->bytes:return (json.dumps(data,indent=2,ensure_ascii=False,sort_keys=True)+'\n').encode('utf-8')
def sha_format(s):return type(s) is str and bool(re.fullmatch(r'[0-9a-f]{40}',s))
def sha256_format(s):return type(s) is str and bool(re.fullmatch(r'[0-9a-f]{64}',s))
def is_safe_rel(path):
    if not isinstance(path,str) or not path or '\\' in path or '\x00' in path: return False
    p=PurePosixPath(path)
    return not p.is_absolute() and all(x not in ('','..','.') for x in p.parts) and path==str(p)
def json_object(data,source='JSON'):
    if type(data) is bytes:
        try:data=data.decode('utf-8')
        except UnicodeDecodeError as e:raise Refused(source+'_NOT_UTF8') from e
    try:obj=json.loads(data) if isinstance(data,str) else data
    except (ValueError,TypeError) as e:raise Refused(source+'_MALFORMED') from e
    need(type(obj) is dict,source+'_NOT_OBJECT')
    return obj

def utc(value):
    need(isinstance(value,str) and bool(value),'TIMESTAMP_MISSING')
    try:
        d=dt.datetime.fromisoformat(value.replace('Z','+00:00'))
    except ValueError as e:raise Refused('TIMESTAMP_INVALID') from e
    need(d.tzinfo is not None,'TIMESTAMP_MUST_BE_OFFSET_AWARE')
    return d.astimezone(dt.timezone.utc)

@dataclasses.dataclass(frozen=True)
class RunSelector:
    repository:str
    head_sha:str
    branch:str
    earliest:str
    workflow_id:int=WORKFLOW_ID
    event:str='pull_request'
    remembered_run:int|None=None
    remembered_attempt:int|None=None

    def validate(self):
        need(self.repository==REPO,'RUN_REPOSITORY_MISMATCH')
        need(sha_format(self.head_sha),'RUN_SHA_MALFORMED')
        need(self.branch==BRANCH,'RUN_BRANCH_MISMATCH')
        utc(self.earliest)
        need(type(self.workflow_id) is int and self.workflow_id==WORKFLOW_ID,'RUN_WORKFLOW_MISMATCH')
        need(self.event=='pull_request','RUN_EVENT_MISMATCH')
        if self.remembered_run is not None:need(type(self.remembered_run) is int and self.remembered_run>0,'REMEMBERED_RUN_INVALID')
        if self.remembered_attempt is not None:need(type(self.remembered_attempt) is int and self.remembered_attempt>0 and self.remembered_run is not None,'REMEMBERED_ATTEMPT_INVALID')

def match_run(run:dict,s:RunSelector):
    s.validate()
    if type(run) is not dict:return False
    if run.get('head_sha')!=s.head_sha or run.get('head_branch')!=s.branch or run.get('event')!=s.event:return False
    if run.get('repository',{}).get('full_name')!=s.repository:return False
    if type(run.get('workflow_id')) is not int or run['workflow_id']!=s.workflow_id:return False
    if s.remembered_run and run.get('id')!=s.remembered_run:return False
    attempt=run.get('run_attempt')
    if type(attempt) is not int or attempt<1:return False
    if s.remembered_attempt and attempt<s.remembered_attempt:return False
    return utc(run.get('created_at',''))>=utc(s.earliest)

def select_run(runs:list,s:RunSelector):
    s.validate()
    need(isinstance(runs,list),'RUNS_NOT_LIST')
    hits=[r for r in runs if match_run(r,s)]
    ids=[x['id'] for x in hits]
    need(len(ids)==len(set(ids)),'DUPLICATE_RUN_ID')
    need(len(hits)<=1,'AMBIGUOUS_EXACT_RUN_MULTIPLE_IDS')
    return hits[0] if hits else None

def check_state(rc:int)->str:
    need(type(rc) is int,'CHECK_RC_INVALID')
    return 'PENDING' if rc==8 else 'COMPLETED_ZERO_ADVISORY' if rc==0 else 'UNQUALIFIED_OR_FAILED'

def classify_run(run):
    need(type(run) is dict,'RUN_MISSING')
    status=run.get('status');result=run.get('conclusion')
    if status in ('queued','pending','waiting','in_progress','requested'):return 'PENDING'
    need(status=='completed','RUN_STATUS_UNKNOWN '+str(status))
    if result=='success':return 'SUCCESS_PENDING_JOB_AND_ARTIFACT_AUDIT'
    if result in ('failure','cancelled','timed_out','action_required','startup_failure','stale'):
        return 'FAILED_'+result.upper()
    return 'REFUSED_UNKNOWN_CONCLUSION'

def audit_jobs(jobs,require_gx02=True):
    need(isinstance(jobs,list) and bool(jobs),'JOBS_NOT_PRESENT')
    by={}
    for j in jobs:
        need(type(j) is dict and type(j.get('name')) is str,'JOB_IDENTITY_BAD')
        name=j['name'];need(name not in by,'DUPLICATE_JOB '+name);by[name]=j
    need(EXPECTED_JOBS.issubset(by),'REQUIRED_JOBS_MISSING '+str(sorted(EXPECTED_JOBS-set(by))))
    need(set(by).issubset(EXPECTED_JOBS|OPTIONAL_JOBS),'UNKNOWN_JOB '+str(sorted(set(by)-EXPECTED_JOBS-OPTIONAL_JOBS)))
    for name in EXPECTED_JOBS:
        need(by[name].get('status')=='completed' and by[name].get('conclusion')=='success','REQUIRED_JOB_NOT_SUCCESS '+name)
    for name in OPTIONAL_JOBS & set(by):
        need(by[name].get('conclusion') in ('skipped','success'),'OPTIONAL_JOB_FAILED '+name)
    steps=by['go-contract'].get('steps')
    need(type(steps) is list,'GO_JOB_STEPS_MISSING')
    for name in [GX01_STEP]+([GX02_STEP] if require_gx02 else []):
        matches=[x for x in steps if x.get('name')==name]
        need(len(matches)==1 and matches[0].get('conclusion')=='success','REQUIRED_STEP_NOT_EXECUTED '+name)
    return {'required_jobs':sorted(EXPECTED_JOBS),'optional_job':by.get('android-companion-debug',{}).get('conclusion'),'go_steps':[GX01_STEP]+([GX02_STEP] if require_gx02 else [])}

def update_workflow(text:str):
    need(type(text) is str,'WORKFLOW_SOURCE_NOT_TEXT')
    marker='      - name: XBS contracts\n'
    need(text.count(marker)==1,'CANONICAL_WORKFLOW_ANCHOR_DRIFT')
    need(text.count('      - name: '+GX01_STEP)==1,'GX01_REQUIRED_STEP_DRIFT')
    need(text.count('      - name: '+GX02_STEP)==0,'GX02_DUPLICATE_INSERTION')
    need('  contents: read\n' in text and text.count('  pull_request:\n')==1,'WORKFLOW_PERMISSIONS_OR_EVENT_DRIFT')
    need('continue-on-error:' not in text and '|| true' not in text,'WORKFLOW_FAIL_OPEN')
    need(text.count('ref: ${{ github.event.pull_request.head.sha || github.sha }}')==4,'CANONICAL_CHECKOUT_SHA_DRIFT')
    updated=text.replace(marker,STEP_FRAGMENT+marker)
    need(updated.count('      - name: '+GX02_STEP)==1,'GX02_INJECTION_FAILED')
    return updated

def validate_workflow(text:str):
    need(type(text) is str,'WORKFLOW_MISSING')
    need(text.count('      - name: '+GX02_STEP)==1,'GX02_STEP_NOT_UNIQUE')
    need(text.count('scripts/ci/gx02_ci_verify.py .')==1 and text.count('scripts/ci/test_gx02_control.py')==1,'GX02_TEST_NOT_ACTUALLY_RUN')
    need(text.index('      - name: '+GX02_STEP)>text.index('      - name: '+GX01_STEP),'GX02_ORDER_DRIFT')
    need(text.index('      - name: '+GX02_STEP)<text.index('      - name: XBS contracts'),'GX02_ORDER_CONTRACT_DRIFT')
    need('  contents: read\n' in text and '  pull_request:\n' in text,'WORKFLOW_SCOPE_DRIFT')
    return True

def prepare_native_manifest(changes:dict[str,bytes],existing:dict[str,bytes],branch=BRANCH,remote="https://github.com/Mikeyphw/xbsctl.git",repo_name="xbsctl"):
    need(type(changes) is dict and len(changes)>=5,'OVERLAY_PAYLOAD_INVENTORY_TOO_SMALL')
    # Devtool 1.1.0 compares repository.name to repo_root.name or the full
    # repo-root path, not to the remote repository's canonical name. Every
    # GX02 worktree is under a generated xbs-gx02-* directory.
    need(isinstance(repo_name,str) and bool(re.fullmatch(r'[A-Za-z0-9._-]+',repo_name))
         and repo_name not in ('.','..'),'DEVTOOL_WORKTREE_NAME_INVALID')
    need(branch==BRANCH and isinstance(remote,str) and bool(remote),
         'DEVTOOL_REPO_BRANCH_OR_REMOTE_INVALID')
    items=[]
    for path,b in sorted(changes.items()):
        need(is_safe_rel(path),'UNSAFE_PATH '+str(path))
        need(type(b) is bytes and len(b)>0,'PAYLOAD_EMPTY '+path)
        action='modify' if path in existing else 'add'
        item={'path':path,'action':action,'sha256':sha256(b),'size':len(b)}
        if action=='modify':item['source_sha256']=sha256(existing[path])
        items.append(item)
    return {'schema_version':2,'type':'overlay','name':'XBS GX02 consolidated Devtool+CI reliability',
        'description':'Hosted-only exact run evidence, atomic Devtool transaction and negative tests',
        'repository':{'name':repo_name,'remote':remote,'branch':branch,
                      'enforce_remote':True,'enforce_branch':True},
        'target':'xbs','inventory':{'format':1,'entries':items},
        'apply':{'atomic':True,'conflict_policy':'fail','path_policy':'repository-relative',
                 'requires_clean_worktree':True,'commit':{'enabled':False}},
        'validation':{'required':False,'restore':False,'build':False,'test':False,'rollback_on_failure':True},
        'notes':['No local Android Gradle, no ADB, root, signed release, merge or physical acceptance']}

def validate_native_manifest(manifest,changes,existing,repo_name="xbsctl"):
    need(manifest.get('schema_version')==2 and manifest.get('type')=='overlay','DEVTOOL_SCHEMA_DRIFT')
    ident=manifest.get('repository')
    need(type(ident) is dict and ident.get('name')==repo_name,
         'DEVTOOL_WORKTREE_REPOSITORY_NAME_MISMATCH')
    need(ident.get('branch')==BRANCH and ident.get('enforce_branch') is True and
         ident.get('enforce_remote') is True and isinstance(ident.get('remote'),str) and
         bool(ident['remote']),'DEVTOOL_REMOTE_OR_BRANCH_ENFORCEMENT_MISSING')
    inv=manifest.get('inventory',{}).get('entries')
    need(type(inv) is list and len(inv)==len(changes),'INVENTORY_CARDINALITY')
    seen=set()
    for row in inv:
        path=row.get('path')
        need(is_safe_rel(path) and path not in seen,'UNSAFE_OR_DUPLICATE_PATH')
        seen.add(path);need(path in changes,'INVENTORY_EXTRA_PATH')
        need(row.get('sha256')==sha256(changes[path]) and row.get('size')==len(changes[path]),'PAYLOAD_DIGEST_MISMATCH '+path)
        if path in existing:need(row['action']=='modify' and row.get('source_sha256')==sha256(existing[path]),'SOURCE_SHA256_MISMATCH '+path)
        else:need(row['action']=='add' and 'source_sha256' not in row,'ADDED_SOURCE_MISMATCH '+path)
    need(manifest.get('apply',{}).get('atomic') is True and manifest['apply'].get('conflict_policy')=='fail','UNSAFE_APPLY_POLICY')
    need(manifest.get('validation',{}).get('build') is False,'LOCAL_BUILD_FORBIDDEN')
    return True

def create_devtool_zip(path:Path,changes:dict[str,bytes],existing:dict[str,bytes],remote="https://github.com/Mikeyphw/xbsctl.git",repo_name="xbsctl"):
    manifest=prepare_native_manifest(changes,existing,remote=remote,repo_name=repo_name)
    validate_native_manifest(manifest,changes,existing,repo_name=repo_name)
    path=Path(path)
    need(not path.is_symlink(),'ARTIFACT_SYMLINK_REFUSED')
    with zipfile.ZipFile(path,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as z:
        z.writestr('.devtool-artifact.json',canonical(manifest))
        for rel,data in sorted(changes.items()):z.writestr(rel,data)
    return manifest

def inspect_zip_bytes(data:bytes,max_files=100,max_size=30_000_000):
    need(type(data) is bytes and len(data)>0,'ARTIFACT_ZIP_EMPTY')
    try:z=zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile as e:raise Refused('ARTIFACT_ZIP_INVALID') from e
    with z:
        names=set();total=0
        need(len(z.infolist())<=max_files,'ARTIFACT_ZIP_TOO_MANY_FILES')
        for item in z.infolist():
            n=item.filename
            need(is_safe_rel(n) and n not in names,'ARTIFACT_ZIP_PATH_DUPLICATE_OR_UNSAFE '+repr(n))
            names.add(n)
            need(not stat.S_ISLNK(item.external_attr>>16),'ARTIFACT_ZIP_SYMLINK '+n)
            need(item.file_size<=max_size,'ARTIFACT_ZIP_MEMBER_OVERSIZED '+n)
            total+=item.file_size;need(total<=max_size,'ARTIFACT_ZIP_OVER_TOTAL_LIMIT')
        need(z.testzip() is None,'ARTIFACT_ZIP_CRC_MISMATCH')
        return {n:z.read(n) for n in names}

def audit_apk_pair(files,sha:str,run_id:int,attempt:int):
    need(sha_format(sha) and type(run_id) is int and run_id>0 and type(attempt) is int and attempt>0,'ARTIFACT_IDENTITY_INVALID')
    expected='xbs-companion-r05c-api36-debug.apk'
    need('SOURCE_SHA' in files and 'SHA256SUMS' in files and expected in files,'API36_APK_PAIR_INCOMPLETE')
    need(files['SOURCE_SHA'].decode('utf-8').strip()==sha,'APK_SOURCE_SHA_DRIFT')
    checksum=files['SHA256SUMS'].decode('utf-8').strip()
    need(re.fullmatch('[0-9a-f]{64}  '+re.escape(expected),checksum) is not None,'APK_CHECKSUM_MANIFEST_INVALID')
    need(checksum[:64]==sha256(files[expected]),'APK_CHECKSUM_MISMATCH')
    need(len(files[expected])>=1024,'APK_TOO_SMALL')
    try:
        with zipfile.ZipFile(io.BytesIO(files[expected])) as apk:
            names=apk.namelist()
            need(len(names)==len(set(names)) and 'AndroidManifest.xml' in names and
                 any(n=='classes.dex' or re.fullmatch(r'classes[0-9]+\.dex',n) for n in names),
                 'APK_MANIFEST_OR_BYTECODE_MISSING')
            need(apk.testzip() is None,'APK_CRC_MISMATCH')
    except zipfile.BadZipFile as e:
        raise Refused('APK_NOT_VALID_ZIP') from e
    return {'apk_sha256':sha256(files[expected]),'source_sha':sha,'run_id':run_id,'attempt':attempt,'signature_qualification':'CI_APKSIGNER_STEP_NOT_INDEPENDENTLY_REPRODUCED','device':'EMULATOR_ONLY'}

def audit_count_receipt(data,sha:str,run_id:int,attempt:int,allow_skips:int=0):
    record=json_object(data,'TEST_COUNT_RECEIPT')
    need(record.get('xbs_source_sha')==sha and record.get('github_run_id')==str(run_id) and record.get('github_run_attempt')==str(attempt),'COUNT_RECEIPT_IDENTITY_DRIFT')
    need(type(record.get('test_cases')) is int and record['test_cases']>0,'TEST_COUNT_ZERO')
    need(type(record.get('skipped_cases')) is int and 0<=record['skipped_cases']<=allow_skips,'TEST_COUNT_UNEXPECTED_SKIPS')
    approved=record.get('approved_skips',[])
    need(type(approved) is list and len(approved)==record['skipped_cases'],'TEST_COUNT_SKIP_RECEIPT_DRIFT')
    need(type(record.get('failed_cases')) is int and record['failed_cases']==0,'TEST_COUNT_HAS_FAILURES')
    return {'case_count':record['test_cases'],'skipped_cases':record['skipped_cases'],'source_sha':sha,'run_id':run_id,'attempt':attempt}

def audit_coverage(profile:bytes,expected_sha256=None):
    need(type(profile) is bytes and len(profile)>100,'COVERPROFILE_EMPTY')
    need(profile.startswith(b'mode: '),'COVERPROFILE_BAD_HEADER')
    lines=profile.decode('utf-8','replace').splitlines()[1:]
    need(len(lines)>2,'COVERPROFILE_NO_CASES')
    tot=0
    for line in lines:
        m=re.fullmatch(r'\S+?:\d+\.\d+,\d+\.\d+ (\d+) (\d+)',line)
        need(m is not None,'COVERPROFILE_MALFORMED')
        tot+=int(m.group(1))
    need(tot>0,'COVERPROFILE_ZERO_STATEMENTS')
    digest=sha256(profile)
    if expected_sha256 is not None:need(digest==expected_sha256,'COVERPROFILE_DIGEST_MISMATCH')
    return {'sha256':digest,'records':len(lines),'statements':tot}

def reject_excluded_commands(commands:list[list[str]]):
    for cmd in commands:
        need(isinstance(cmd,list) and all(isinstance(x,str) for x in cmd),'COMMAND_INVALID')
        # User-facing Termux/Devtool commands must remain human-readable.
        # Internal JSON evidence files and GitHub REST parsing remain allowed.
        need('--json' not in cmd,'FORBIDDEN_JSON_CLI_FLAG')
        base=Path(cmd[0]).name.lower() if cmd else ''
        need(base not in {'adb','su','ksud','magisk','gradle','gradlew','fastboot'},'FORBIDDEN_LOCAL_ANDROID_OR_ROOT_COMMAND '+base)
    return True

def readable_devtool_preflight(prefix:list[str],artifact:str):
    """Single reviewed human-output Devtool preflight inventory.

    Keep the native Devtool transaction and fail-closed exit codes. No explicit
    machine-format switches on a command executed by the launcher.
    """
    need(isinstance(prefix,list) and len(prefix)>=2 and prefix[0]=='devtool' and
         all(isinstance(p,str) for p in prefix),'DEVTOOL_PREFIX_INVALID')
    need(isinstance(artifact,str) and artifact.endswith('.zip') and artifact.strip(),
         'DEVTOOL_ARTIFACT_PATH_INVALID')
    steps=[
        ('STATUS', [*prefix,'status']),
        # Source and payload diagnostics must run before the summary-only
        # overlay plan. The prior plan-first sequence concealed the exact
        # repository-name mismatch behind a generic 'refuse' table.
        ('MANIFEST_CHECK', [*prefix,'validate-overlay',artifact]),
        ('OVERLAY_CHECK', [*prefix,'overlay','check',artifact]),
        ('OVERLAY_PLAN', [*prefix,'overlay','plan',artifact]),
    ]
    reject_excluded_commands([cmd for _,cmd in steps])
    return steps

STAGES=['NEW','WORKTREE','DEVTOOL_APPLIED','LOCAL_TESTED','COMMITTED','PUSHED','PR_OPEN','HOSTED_PENDING','HOSTED_FAILED','HOSTED_VERIFIED']
STATE_ORDER={s:i for i,s in enumerate(STAGES)}

class Journal:
    def __init__(self,path:Path,root_sha=BASE_SHA):
        self.path=Path(path);self.root_sha=root_sha
    def initial(self):
        return {'schema':'xbs-gx02-journal/v1','repo':REPO,'branch':BRANCH,
            'base_branch':BASE_BRANCH,'base_sha':self.root_sha,'state':'NEW',
            'worktree':None,'commit_sha':None,'pushed_sha':None,'pr_number':None,
            'pr_created_at':None,'run_id':None,'run_attempt':None,'run_status':None,
            'artifacts':{},'devtool_transaction':None,'errors':[]}
    def _safe(self):
        need(not self.path.is_symlink(),'JOURNAL_SYMLINK_REFUSED')
        need(not self.path.parent.is_symlink(),'JOURNAL_PARENT_SYMLINK_REFUSED')
        need(self.path.suffix=='.json','JOURNAL_EXTENSION_UNSAFE')
    def load(self):
        self._safe()
        if not self.path.exists():return None
        need(self.path.is_file(),'JOURNAL_NOT_REGULAR')
        mode=stat.S_IMODE(self.path.stat().st_mode)
        need(mode & 0o077==0,'JOURNAL_PERMISSIONS_EXPOSED')
        data=json_object(self.path.read_bytes(),'JOURNAL')
        need(data.get('schema')=='xbs-gx02-journal/v1' and data.get('repo')==REPO and
             data.get('base_sha')==self.root_sha and data.get('branch')==BRANCH,'FOREIGN_JOURNAL')
        need(data.get('state') in STAGES,'UNKNOWN_JOURNAL_STATE')
        for key in ('commit_sha','pushed_sha'):
            if data.get(key):need(sha_format(data[key]),'JOURNAL_SHA_CORRUPT '+key)
        if data.get('run_attempt') is not None:need(type(data['run_attempt']) is int and data['run_attempt']>0,'JOURNAL_ATTEMPT_INVALID')
        return data
    def save(self,data):
        self._safe();need(data.get('schema')=='xbs-gx02-journal/v1' and data.get('base_sha')==self.root_sha,'JOURNAL_SCHEMA_OR_PIN_DRIFT')
        need(data.get('state') in STAGES,'JOURNAL_STATE_INVALID')
        self.path.parent.mkdir(parents=True,exist_ok=True)
        fd=None;temp=None
        try:
            fd,temp=tempfile.mkstemp(prefix='.gx02-journal-',dir=self.path.parent)
            os.fchmod(fd,0o600)
            with os.fdopen(fd,'wb') as f:
                fd=None;f.write(canonical(data));f.flush();os.fsync(f.fileno())
            os.replace(temp,self.path);temp=None
            if hasattr(os,'O_DIRECTORY'):
                parentfd=os.open(self.path.parent,os.O_RDONLY|os.O_DIRECTORY)
                try:os.fsync(parentfd)
                finally:os.close(parentfd)
        finally:
            if fd is not None:os.close(fd)
            if temp and Path(temp).exists():Path(temp).unlink()
    @staticmethod
    def advance(data,next_state):
        need(next_state in STATE_ORDER,'JOURNAL_TRANSITION_UNKNOWN')
        prev=data['state']
        need(STATE_ORDER[next_state]>=STATE_ORDER[prev] or (prev=='HOSTED_FAILED' and next_state=='HOSTED_PENDING'),
             'JOURNAL_STATE_REGRESSION '+prev+'->'+next_state)
        data['state']=next_state
        return data

def safe_pr_list(prs,sha,base=BASE_BRANCH,branch=BRANCH):
    need(type(prs) is list,'PR_LIST_INVALID')
    matches=[]
    for item in prs:
        if item.get('head',{}).get('ref')!=branch:continue
        matches.append(item)
    need(len(matches)<=1,'DUPLICATE_BRANCH_PR')
    if not matches:return None
    p=matches[0]
    need(p.get('head',{}).get('sha')==sha and p.get('base',{}).get('ref')==base,'EXISTING_PR_SHA_OR_BASE_DRIFT')
    need(p.get('state')=='open' and not p.get('merged_at'),'EXISTING_PR_NOT_OPEN')
    return p

def redact(text:str):
    text=str(text)[:18000]
    for pattern in [r'(?i)(authorization:\s*bearer\s+)[^\s]+',r'(?i)(token|password|secret|api[_-]?key)=([^\s]+)',r'gh[opsu]_[A-Za-z0-9_]{15,}']:
        text=re.sub(pattern,lambda m:m.group(1)+'[REDACTED]' if m.lastindex and m.lastindex>=2 else '[REDACTED]',text)
    return text

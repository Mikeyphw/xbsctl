#!/usr/bin/env python3
"""Exact-main aggregate source gate; no merges, root/device writes or release claims."""
from __future__ import annotations
import argparse,os,re,subprocess,sys
from pathlib import Path

AGG_BRANCH = 'feature/xbs-aggregate-r08-r10-main-20261118'
AGG_HEAD = 'b693612fc76bef3bd4d7fb8375861a990180fd0a'
FIX_BRANCH = 'feature/xbs-aggregate-r07a-ci-repair-20261118'
R08R10_HEAD = '77825deb673c4973e82b9c428efcbc5991409a11'
MAIN_SHA = 'c52561dfa203090befb680a058aa5e5e79870f1b'
MERGE_TREE = 'ae7292929149833cbecd65667d9a4f7b0588697d'
MAIN_FILES = {'.github/workflows/devtool-s7-p05-android-consumer.yml': '11367960a6fd311926db890b632433000b767a0b', 'docs/DEVTOOL_S7_P05_ANDROID_CONSUMER.md': 'a1153a71c0f1dbd177cf87f89f94b8919d85fd71', 'docs/DEVTOOL_S7_P05_HF01_SDK.md': '8ec037fdbb6a19f3c794ef422a36832980e0502f', 'docs/DEVTOOL_S7_P05_HF02_GRADLE_PATH.md': 'a306b89ea079461eb21d4f46c10a517a6765cd87', 'tests/test_devtool_s7_p05_android_adoption.py': '305be76876c32d796a652f09485e4fb4f02148ab'}
GRADLE_BLOB = '3c0fbf076bef2868f521bd1c052397a9fde187cc'

class Refused(RuntimeError):pass

def need(condition, reason):
    if not condition: raise Refused(reason)

def verify_blob_map(observed):
    need(type(observed) is dict and set(observed)==set(MAIN_FILES)|{'android-app/build.gradle'},'AGG_FILE_INVENTORY_MISMATCH')
    for name,blob in MAIN_FILES.items():
        need(observed.get(name)==blob,'AGG_MAIN_BLOB_DRIFT '+name)
    need(observed['android-app/build.gradle']==GRADLE_BLOB,'AGG_GRADLE_CONFLICT_RESOLUTION_DRIFT')
    return True

def git(root,*args):
    p=subprocess.run(['git','-C',str(root),*args],text=True,capture_output=True,timeout=45)
    need(p.returncode==0,'AGG_GIT_REFUSED '+repr(args))
    return p.stdout.strip()

def audit(root,hosted=False):
    root=Path(root).resolve(strict=True)
    from gx06_stack_gate import reviewed_stage, stage_branch, stage_base_branch, hosted_branch, validate_hosted_context, HF01_TREE, MAIN_TREE
    branch=hosted_branch(os.environ) if hosted else git(root,'symbolic-ref','--short','HEAD')
    stage=reviewed_stage(root)
    need(stage in ('AGG','FIX','HF01','INT01','INT01_MAIN') and branch==stage_branch(stage),'AGG_UNREVIEWED_BRANCH')
    head=git(root,'rev-parse','HEAD')
    need(re.fullmatch('[a-f0-9]{40}',head) is not None,'AGG_INVALID_HEAD')
    need(not git(root,'status','--porcelain','--untracked-files=all'),'AGG_DIRTY_WORKTREE')
    observed={p:git(root,'rev-parse','HEAD:'+p) for p in list(MAIN_FILES)+['android-app/build.gradle']}
    verify_blob_map(observed)
    policy=__import__('json').loads((root/'config/delivery-required-ci.json').read_text())
    need(policy.get('base_branch')==stage_base_branch(stage),'AGG_CI_BASE_DRIFT')
    required={(x['name'],x['workflow_id']) for x in policy['required_workflows']}
    need(required=={
        ('Devtool XBS Go and Android candidates',386774409),
        ('XBS GX03 hosted API26 / API34 compatibility',419253895),
        ('Devtool S7 P05 XBS Android Consumer Adoption',418896455)},'AGG_REQUIRED_WORKFLOW_DRIFT')
    if stage in ('INT01','INT01_MAIN'):
        from gx06_stack_gate import HF01_HEAD
        need(git(root,'rev-parse',MAIN_SHA+'^{tree}')==MAIN_TREE,'INT01_PINNED_MAIN_TREE_DRIFT')
        need(git(root,'merge-tree','--write-tree',MAIN_SHA,HF01_HEAD)==HF01_TREE,'INT01_HF01_PREVIEW_DRIFT')
        candidate=git(root,'rev-parse','HEAD^2') if stage=='INT01_MAIN' else head
        preview=git(root,'merge-tree','--write-tree',MAIN_SHA,candidate)
        need(preview==git(root,'rev-parse',candidate+'^{tree}'),'INT01_CANDIDATE_MERGE_PREVIEW_DRIFT')
    else:
        preview=MERGE_TREE
    if hosted:
        validate_hosted_context(root,os.environ,stage=stage,head=head)
    print('XBS_AGGREGATE_SOURCE_INTEGRITY_PASS sha='+head+' parent='+R08R10_HEAD+' main='+MAIN_SHA+' preview='+preview+' release=false',flush=True)
    return {'head':head,'source':'SHA_AND_BLOB_PINNED','merge_applied':stage=='INT01_MAIN','aggregate_ci_qualified':False,
            'release_qualified':False,'physical_root_qualified':False}

def main():
    p=argparse.ArgumentParser();p.add_argument('--repo',default='.');p.add_argument('--hosted',action='store_true');a=p.parse_args()
    try:audit(a.repo,a.hosted);return 0
    except (Refused,ValueError,KeyError,OSError) as e:
        print('AGGREGATE_SOURCE_REFUSED '+str(e),file=sys.stderr);return 2
if __name__=='__main__':raise SystemExit(main())

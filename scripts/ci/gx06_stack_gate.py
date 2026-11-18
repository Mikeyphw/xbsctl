#!/usr/bin/env python3
"""GX06 read-only source, stack and original-roadmap gate.

Runs inside the already-required GX02 hosted source step, no network/device/release
side effects. Separate --live command is GitHub read-only and never substitutes
for hosted acceptance at the new exact GX06 HEAD.
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

REPO = 'Mikeyphw/xbsctl'
GX05_HEAD = '1b5fbcd42bd39956c5251ea8f43eadbddf7aefcf'
GX05_TREE = '45daa340788913aa1b626a3f3f96b6c20f141391'
GX06_BRANCH = 'feature/xbs-gx06-stack-integration-20261118'
GX06_HEAD = '5287f4a41eedc2b7fa3a33c6af820f117db78b84'
R08R10_BRANCH = 'feature/xbs-r08-r10-acceptance-rebuild-20261118'
R08R10_HEAD = '77825deb673c4973e82b9c428efcbc5991409a11'
AGG_BRANCH = 'feature/xbs-aggregate-r08-r10-main-20261118'
AGG_MAIN_HEAD = 'c52561dfa203090befb680a058aa5e5e79870f1b'
AGG_MERGE_TREE = 'ae7292929149833cbecd65667d9a4f7b0588697d'
AGG_HEAD = 'b693612fc76bef3bd4d7fb8375861a990180fd0a'
FIX_BRANCH = 'feature/xbs-aggregate-r07a-ci-repair-20261118'
GX05_BRANCH = 'feature/xbs-gx05-security-performance-readiness-20261116'
LOCK = 'docs/engineering/XBS_GX06_STACK_LOCK.json'
GATES = 'docs/engineering/XBS_GX06_COVERAGE.json'
HANDOFF = 'docs/engineering/XBS_GX06_HANDOFF.md'
ORIGINAL = (
    'docs/engineering/XBS_IMPLEMENTATION_LEDGER.json',
    'docs/engineering/XBS_TEST_MATRIX.json',
    'docs/engineering/XBS_GX_TRACKER.json',
    'docs/engineering/gx01_handoff/archive/v2_original/XBS_IMPLEMENTATION_LEDGER.json',
    'docs/engineering/gx01_handoff/archive/v2_original/XBS_TEST_MATRIX.json',
    'docs/engineering/gx01_handoff/archive/v2_original/XBS_FINAL_ROADMAP_v2.md',
)
GX06_CHANGED_PATHS = frozenset({
    'config/delivery-required-ci.json',
    '.github/workflows/devtool-xbs-ci.yml',
    'docs/engineering/XBS_GX06_STACK_LOCK.json',
    'docs/engineering/XBS_GX06_COVERAGE.json',
    'docs/engineering/XBS_GX06_HANDOFF.md',
    'scripts/ci/gx02_ci_verify.py',
    'scripts/ci/gx03_contract_audit.py',
    'scripts/ci/gx06_stack_gate.py',
    'scripts/ci/gx06_live_audit.py',
    'scripts/ci/test_gx06_stack_gate.py',
    'scripts/ci/test_gx06_live_audit.py',
})
AGG_CHANGED_PATHS = frozenset({
    '.github/workflows/devtool-s7-p05-android-consumer.yml',
    '.github/workflows/devtool-xbs-ci.yml',
    'config/delivery-required-ci.json',
    'docs/DEVTOOL_S7_P05_ANDROID_CONSUMER.md',
    'docs/DEVTOOL_S7_P05_HF01_SDK.md',
    'docs/DEVTOOL_S7_P05_HF02_GRADLE_PATH.md',
    'docs/engineering/XBS_AGGREGATE_INTEGRATION_20261118.md',
    'scripts/ci/aggregate_integration.py',
    'scripts/ci/gx02_ci_verify.py',
    'scripts/ci/gx03_contract_audit.py',
    'scripts/ci/gx06_stack_gate.py',
    'scripts/ci/r08_r10_acceptance.py',
    'scripts/ci/test_aggregate_integration.py',
    'tests/test_devtool_s7_p05_android_adoption.py',
})
FIX_CHANGED_PATHS = frozenset({
    'config/delivery-required-ci.json',
    'docs/engineering/XBS_AGGREGATE_INTEGRATION_20261118.md',
    'scripts/ci/aggregate_integration.py',
    'scripts/ci/gx02_ci_verify.py',
    'scripts/ci/gx03_contract_audit.py',
    'scripts/ci/gx06_stack_gate.py',
    'scripts/ci/r07a_policy.py',
    'scripts/ci/test_aggregate_integration.py',
    'scripts/ci/test_r07a_policy.py',
})
R08R10_CHANGED_PATHS = frozenset(('.github/workflows/devtool-xbs-ci.yml', 'android-app/app/src/androidTest/java/io/github/mikeceead/xbscompanion/Gx04TilesInstrumentedTest.java', 'android-app/build.gradle', 'config/delivery-required-ci.json', 'docs/engineering/XBS_R08_R10_ACCEPTANCE_HANDOFF.md', 'docs/engineering/XBS_R08_R10_EVIDENCE_MAP.json', 'scripts/ci/gx02_ci_verify.py', 'scripts/ci/gx03_contract_audit.py', 'scripts/ci/gx03_emulator_host.py', 'scripts/ci/gx06_stack_gate.py', 'scripts/ci/r08_r10_acceptance.py', 'scripts/ci/test_gx04_tiles.py', 'scripts/ci/test_gx06_stack_gate.py', 'scripts/ci/test_r08_r10_acceptance.py'))
SHA = re.compile(r'[0-9a-f]{40}\Z')
SHA256 = re.compile(r'[0-9a-f]{64}\Z')
RUN_REQUIRED = {'Devtool XBS Go and Android candidates': 386774409,
                'XBS GX03 hosted API26 / API34 compatibility': 419253895}

class Refused(ValueError): pass

def need(condition, reason):
    if not condition: raise Refused(reason)

def load_bounded(path: Path, label: str, bound=1_000_000):
    need(path.is_file() and not path.is_symlink() and 0 < path.stat().st_size <= bound,
         'GX06_'+label+'_FILE_UNSAFE_OR_MISSING')
    try: data=json.loads(path.read_bytes())
    except (UnicodeError, ValueError) as e: raise Refused('GX06_'+label+'_INVALID_JSON') from e
    need(type(data) is dict,'GX06_'+label+'_NOT_OBJECT')
    return data

def git(root: Path, *args: str):
    try:
        p=subprocess.run(['git','-C',str(root),*args], text=True,
                         capture_output=True,timeout=25,check=False)
    except (OSError,subprocess.TimeoutExpired) as e: raise Refused('GX06_GIT_UNAVAILABLE') from e
    need(p.returncode==0, 'GX06_GIT_FAILED '+repr(args[:3]))
    return p.stdout.strip()

def validated_lock(lock):
    need(lock.get('schema')=='xbs-gx06-stack-lock/v1' and lock.get('repository')==REPO,
         'GX06_LOCK_SCHEMA_OR_REPOSITORY_DRIFT')
    need(lock.get('base_head')==GX05_HEAD and lock.get('base_tree')==GX05_TREE and
         lock.get('branch')==GX06_BRANCH, 'GX06_LOCK_SOURCE_DRIFT')
    need(lock.get('expected_changed_paths')==sorted(GX06_CHANGED_PATHS),'GX06_CHANGED_INVENTORY_DRIFT')
    stack=lock.get('stack')
    need(type(stack) is list and len(stack)==5,'GX06_STACK_FIVE_GATES_REQUIRED')
    seen=set()
    for i,p in enumerate(stack,1):
        need(type(p) is dict and p.get('milestone')=='GX0'+str(i) and
             type(p.get('pr')) is int and p['pr'] not in seen and
             type(p.get('head')) is str and SHA.fullmatch(p['head']) is not None and
             type(p.get('branch')) is str and p['branch'].startswith('feature/xbs-') and
             type(p.get('runs')) is list and p['runs'], 'GX06_STACK_ENTRY_INVALID '+str(i))
        seen.add(p['pr'])
        if i>1:
            previous=stack[i-2]
            need(p.get('base_branch')==previous['branch'] and p.get('base_sha')==previous['head'],
                 'GX06_NON_CONTIGUOUS_PR_STACK GX0'+str(i))
        run_seen=set()
        for run in p['runs']:
            need(type(run) is dict and type(run.get('id')) is int and run['id']>0 and
                 run['id'] not in run_seen and run.get('workflow') in RUN_REQUIRED and
                 run.get('workflow_id')==RUN_REQUIRED[run['workflow']],
                 'GX06_RUN_IDENTITY_OR_DUPLICATE')
            run_seen.add(run['id'])
        expected_workflows=set(RUN_REQUIRED) if i>=3 else {'Devtool XBS Go and Android candidates'}
        need({r['workflow'] for r in p['runs']}==expected_workflows,
             'GX06_WORKFLOW_COVERAGE_MISSING GX0'+str(i))
    need([p['pr'] for p in stack]==[27,28,30,31,32], 'GX06_PR_ORDER_DRIFT')
    need(stack[-1]['head']==GX05_HEAD and stack[-1]['branch']==GX05_BRANCH,
         'GX06_GX05_HEAD_DRIFT')
    need(lock.get('main_at_inspection') and SHA.fullmatch(lock['main_at_inspection']) is not None,
         'GX06_MAIN_SHA_MISSING')
    return stack

def validate_original_inventory(ledger, matrix, tracker):
    phases=ledger.get('phases')
    need(type(phases) is list and len(phases)==14,
         'GX06_ORIGINAL_PHASES_CHANGED')
    phase_ids=[p.get('id') for p in phases if isinstance(p,dict)]
    need(phase_ids==['R'+str(i).zfill(2) for i in range(14)],'GX06_ORIGINAL_PHASE_IDS_DRIFT')
    tasks=[task for phase in phases for task in phase.get('tasks', [])]
    ids=[t.get('id') for t in tasks if isinstance(t,dict)]
    need(len(tasks)==178 and len(ids)==178 and len(set(ids))==178,
         'GX06_ORIGINAL_178_TASKS_CHANGED')
    cases=matrix.get('cases')
    need(type(cases) is list and len(cases)==131 and
         len(set(c.get('id') for c in cases if isinstance(c,dict)))==131,
         'GX06_ORIGINAL_131_CASES_CHANGED')
    findings=ledger.get('findings')
    need(type(findings) is list and len(findings)==26 and
         len(set(f.get('id') for f in findings if isinstance(f,dict)))==26,
         'GX06_ORIGINAL_26_FINDINGS_CHANGED')
    milestones=tracker.get('side_milestones')
    need(type(milestones) is list and
         [g.get('id') for g in milestones]==['GX01','GX02','GX03','GX04','GX05','GX06'],
         'GX06_SIX_MILESTONES_CHANGED')
    need(tracker.get('original_task_count')==178 and tracker.get('original_test_case_count')==131,
         'GX06_TRACKER_COUNT_DRIFT')
    phases_r={p['id']:len(p['tasks']) for p in phases}
    return {'original_tasks':len(tasks),'original_cases':len(cases),'original_findings':len(findings),
            'original_phase_task_counts':phases_r,
            'original_case_acceptance_promoted':False,
            'unresolved_original_acceptance':True,
            'r08_r13_remain_open_for_exact_evidence':True}

def validate_release_limits(report):
    for key in ('physical_root_qualified','kernel_su_qualified',
                'signed_release_qualified','battery_savings_measured','auto_merge_approved',
                'original_cases_accepted'):
        need(report.get(key) is False,'GX06_UNAUTHORIZED_ACCEPTANCE '+key)
    need(report.get('release_gate')=='REFUSED_UNTIL_R11_R13_PHYSICAL_SIGNING_AND_APPROVAL',
         'GX06_RELEASE_GATE_WEAKENED')
    need(report.get('r10a')=='SIDE_TRACK_PREFLIGHT_ONLY_NOT_ORIGINAL_R10_APPLIED',
         'GX06_R10A_FALSE_APPLICATION')
    need(report.get('gx06')=='IMPLEMENTED_HOSTED_PENDING_LIVE_STACK_REVIEW',
         'GX06_PREMATURE_GATE_ACCEPTANCE')

def audit_local(root: Path, *, env: dict|None=None):
    """Read-only hosted/local evidence: no remote requests, no file writes."""
    root=Path(root).resolve(strict=True)
    env = dict(os.environ) if env is None else env
    lock=load_bounded(root/LOCK,'LOCK',200000)
    stack=validated_lock(lock)
    report=load_bounded(root/GATES,'COVERAGE',200000)
    validate_release_limits(report)
    need(report.get('schema')=='xbs-gx06-coverage/v1' and
         report.get('source_base')==GX05_HEAD, 'GX06_COVERAGE_SOURCE_DRIFT')
    head=git(root,'rev-parse','HEAD')
    tree=git(root,'rev-parse','HEAD^{tree}')
    need(SHA.fullmatch(head) is not None and SHA.fullmatch(tree) is not None,
         'GX06_HEAD_OR_TREE_INVALID')
    branch=(env.get('GITHUB_HEAD_REF') if env.get('GITHUB_ACTIONS')=='true'
            else git(root,'symbolic-ref','--short','HEAD'))
    need(branch in (GX06_BRANCH,R08R10_BRANCH,AGG_BRANCH,FIX_BRANCH),'GX06_UNREVIEWED_BRANCH')
    fix=branch==FIX_BRANCH
    aggregate=branch in (AGG_BRANCH,FIX_BRANCH)
    continuation=branch in (R08R10_BRANCH,AGG_BRANCH,FIX_BRANCH)
    if continuation:
        need(git(root,'rev-parse','HEAD^')==(AGG_HEAD if fix else R08R10_HEAD if aggregate else GX06_HEAD),
             'R08R10_OR_AGG_PARENT_NOT_EXACT')
        need(git(root,'rev-parse',GX06_HEAD+'^')==GX05_HEAD,
             'R08R10_GX06_SOURCE_PARENT_DRIFT')
        if aggregate:
            need(git(root,'rev-parse',R08R10_HEAD+'^')==GX06_HEAD,
                 'AGG_R08R10_SOURCE_PARENT_DRIFT')
            if fix:
                need(git(root,'rev-parse',AGG_HEAD+'^')==R08R10_HEAD,
                     'R07A_FIX_AGGREGATE_PARENT_DRIFT')
    else:
        need(git(root,'rev-parse','HEAD^')==GX05_HEAD,'GX06_UNREVIEWED_PARENT')
    need(git(root,'rev-parse',GX05_HEAD+'^{tree}')==GX05_TREE,'GX06_GX05_TREE_DRIFT')
    need(not git(root,'status','--porcelain','--untracked-files=all'),
         'GX06_DIRTY_SOURCE_CHECKOUT')
    gx06_diff_target=GX06_HEAD if continuation else 'HEAD'
    changed=set(git(root,'diff','--name-only',GX05_HEAD,gx06_diff_target).splitlines())
    need(changed==GX06_CHANGED_PATHS,
         'GX06_EXACT_CHANGE_INVENTORY_MISMATCH '+repr(sorted(changed^GX06_CHANGED_PATHS)))
    if continuation:
        r08_target=R08R10_HEAD if aggregate else 'HEAD'
        next_changed=set(git(root,'diff','--name-only',GX06_HEAD,r08_target).splitlines())
        need(next_changed==R08R10_CHANGED_PATHS,
             'R08R10_EXACT_CHANGE_INVENTORY_MISMATCH '+repr(sorted(next_changed^R08R10_CHANGED_PATHS)))
        if aggregate:
            agg_target=AGG_HEAD if fix else 'HEAD'
            agg_changed=set(git(root,'diff','--name-only',R08R10_HEAD,agg_target).splitlines())
            need(agg_changed==AGG_CHANGED_PATHS,
                 'AGG_EXACT_CHANGE_INVENTORY_MISMATCH '+repr(sorted(agg_changed^AGG_CHANGED_PATHS)))
            if fix:
                fix_changed=set(git(root,'diff','--name-only',AGG_HEAD,'HEAD').splitlines())
                need(fix_changed==FIX_CHANGED_PATHS,
                     'R07A_FIX_EXACT_INVENTORY_MISMATCH '+repr(sorted(fix_changed^FIX_CHANGED_PATHS)))
        from r08_r10_acceptance import audit_source
        audit_source(root, expected_base=AGG_BRANCH if fix else R08R10_BRANCH if aggregate else GX06_BRANCH)
    for path in ORIGINAL:
        need(git(root,'rev-parse',GX05_HEAD+':'+path)==git(root,'rev-parse','HEAD:'+path),
             'GX06_ORIGINAL_PROTECTED_FILE_CHANGED '+path)
    for earlier in stack:
        status=subprocess.run(['git','-C',str(root),'merge-base','--is-ancestor',
                               earlier['head'],'HEAD'],capture_output=True,timeout=25)
        need(status.returncode==0,'GX06_ANCESTRY_NOT_PROVEN '+earlier['milestone'])
    # Main has S7 P05 separately; it is not automatically part of this GX stack.
    ledger=load_bounded(root/ORIGINAL[0],'LEDGER',5000000)
    matrix=load_bounded(root/ORIGINAL[1],'MATRIX',4000000)
    tracker=load_bounded(root/ORIGINAL[2],'TRACKER',100000)
    original=validate_original_inventory(ledger,matrix,tracker)
    policy=load_bounded(root/'config/delivery-required-ci.json','CI_POLICY',100000)
    required_workflows=set(RUN_REQUIRED.items())
    if aggregate:
        required_workflows.add(('Devtool S7 P05 XBS Android Consumer Adoption',418896455))
    expected_base=(AGG_BRANCH if fix else R08R10_BRANCH if aggregate else GX06_BRANCH if continuation else GX05_BRANCH)
    need(policy.get('base_branch')==expected_base and
         policy.get('repository')==REPO and
         policy.get('event')=='pull_request' and policy.get('release_qualification')=='separate' and
         {(v.get('name'),v.get('workflow_id')) for v in policy.get('required_workflows',[])}==required_workflows,
         'GX06_DELIVERY_CI_POLICY_INVALID_FOR_TARGET_BRANCH')
    if env.get('GITHUB_ACTIONS')=='true':
        need(env.get('GITHUB_EVENT_NAME')=='pull_request' and
             env.get('GITHUB_HEAD_REF')==branch and
             env.get('XBS_EXPECTED_HEAD_SHA')==head,
             'GX06_HOSTED_PR_SOURCE_IDENTITY_REFUSED')
        run,attempt=env.get('GITHUB_RUN_ID',''),env.get('GITHUB_RUN_ATTEMPT','')
        need(run.isdigit() and int(run)>0 and attempt.isdigit() and int(attempt)>0,
             'GX06_HOSTED_RUN_ATTEMPT_MISSING')
    else: run=attempt=''
    out=dict(original, schema='xbs-gx06-local-audit/v1', source_sha=head,
             source_tree=tree, base_sha=GX05_HEAD, branch=branch,
             github_run_id=run, github_run_attempt=attempt,
             inspected_prs=[p['pr'] for p in stack],
             prior_hosted_status='HISTORICAL_RUN_IDS_SOURCE_BOUND_LIVE_RECHECK_REQUIRED',
             gx06_hosted_status='CURRENT_WORKFLOW_TESTS_MUST_PASS',
             main_merge_status='NOT_PROVEN_REQUIRES_EXACT_MERGE_TREE_REVIEW',
             physical_qualified=False, release_qualified=False,
             automatic_merge=False)
    return out

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('root',nargs='?',default='.')
    args=p.parse_args()
    try:
        report=audit_local(Path(args.root))
        print('GX06_SOURCE_ROADMAP_GATE_PASS '+json.dumps(report,sort_keys=True),flush=True)
        return 0
    except (Refused,KeyError,OSError,subprocess.SubprocessError) as e:
        print('GX06_SOURCE_ROADMAP_REFUSED '+str(e),file=sys.stderr,flush=True)
        return 2

if __name__=='__main__': raise SystemExit(main())

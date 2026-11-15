#!/usr/bin/env python3
"""GX02 hosted-only independent evidence and workflow auditor.

Must run in existing go-contract GitHub Actions job after the actual R06B
source-pinned Devtool/affected and R09F artifact-producing steps. Never invoke
Gradle, ADB or root and never produce device acceptance.
"""
from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from gx02_control import (Refused, need, json_object, audit_count_receipt,
    audit_coverage, validate_workflow, BASE_SHA, GX02_STEP)

IMMUTABLE_ORIGINAL=[
 'docs/engineering/XBS_IMPLEMENTATION_LEDGER.json',
 'docs/engineering/XBS_TEST_MATRIX.json',
 'docs/engineering/XBS_GX_TRACKER.json',
 'docs/engineering/gx01_handoff/archive/v2_original/XBS_FINAL_ROADMAP_v2.md',
 'docs/engineering/gx01_handoff/archive/v2_original/XBS_IMPLEMENTATION_LEDGER.json',
 'docs/engineering/gx01_handoff/archive/v2_original/XBS_TEST_MATRIX.json',
]
ALLOWED_CHANGES={
 '.github/workflows/devtool-xbs-ci.yml',
 'scripts/ci/gx02_control.py','scripts/ci/gx02_ci_verify.py','scripts/ci/test_gx02_control.py',
 'docs/engineering/XBS_GX02_CONTROL_PLANE_HANDOFF.md',
 'docs/engineering/XBS_GX02_SOURCE_PINS.json',
 'docs/engineering/XBS_GX02_LOCAL_STATUS.json',
}


def git(root,*args):
    p=subprocess.run(['git','-C',str(root),*args],capture_output=True,text=True,timeout=30)
    need(p.returncode==0,'GIT_SOURCE_VERIFICATION_FAILED '+repr(args[:3]))
    return p.stdout.strip()

def audit_source_and_receipts(root:Path, env:dict):
    need(env.get('GITHUB_ACTIONS')=='true' and env.get('GITHUB_EVENT_NAME')=='pull_request',
         'HOSTED_PULL_REQUEST_RUN_REQUIRED')
    head=git(root,'rev-parse','HEAD')
    need(re.fullmatch('[0-9a-f]{40}',head) and head==env.get('XBS_EXPECTED_HEAD_SHA'),
         'GX02_HOSTED_HEAD_SHA_MISMATCH')
    run=env.get('GITHUB_RUN_ID','');attempt=env.get('GITHUB_RUN_ATTEMPT','')
    need(run.isdigit() and int(run)>0 and attempt.isdigit() and int(attempt)>0,
         'GX02_GITHUB_RUN_ATTEMPT_MISSING')
    # Preserve the immutable GX02 introducing-commit proof on each descendant.
    # GX03 must be its direct child; its own exact-file policy is separately gated.
    frozen='ea46616247484edc26bcd9e47e0a5fd8f4565208'
    need(git(root,'rev-parse',frozen+'^')=='593db754484676ceacd3307be9d1f7db86eb9943',
         'GX02_HF05_PREVIOUS_HEAD_NOT_EXACT')
    need(git(root,'rev-parse',frozen+'~2')==BASE_SHA,
         'GX02_HF05_GX01_ANCESTOR_DRIFT')
    if head!=frozen:
        gx03_intro='78475b2d1634711d7d615210479befedf3d14417'
        need(git(root,'rev-parse',gx03_intro+'^')==frozen,
             'GX02_GX03_INTRO_PARENT_DRIFT')
        if head!=gx03_intro:
            hf05='8604a5b97c4b07702a4ad024dbd614d59645a8a6'
            need(git(root,'rev-parse',hf05+'^')==gx03_intro,
                 'GX02_HF05_PARENT_DRIFT')
            if head!=hf05:
                hf06='7ee71272537948ab04a7f581e520d307e9044321'
                need(git(root,'rev-parse',hf06+'^')==hf05,
                     'GX02_HF06_PARENT_DRIFT')
                expected_hf06={'scripts/ci/gx02_ci_verify.py',
                               'scripts/ci/gx03_contract_audit.py'}
                changed_hf06=set(git(root,'diff','--name-only',hf05,hf06).splitlines())
                need(changed_hf06==expected_hf06,
                     'GX02_HF06_CHANGED_PATHS_DRIFT '+repr(sorted(changed_hf06^expected_hf06)))
                if head!=hf06:
                    hf07='b0298c83e5eaaa730f6333e5a750025599b04fa2'
                    need(git(root,'rev-parse',hf07+'^')==hf06,'GX02_HF07_PARENT_DRIFT')
                    expected_hf07={'scripts/ci/gx03_emulator_host.py',
                               'scripts/ci/test_gx03_hosted.py',
                               'scripts/ci/gx02_ci_verify.py',
                               'scripts/ci/gx03_contract_audit.py'}
                    changed_hf07=set(git(root,'diff','--name-only',hf06,hf07).splitlines())
                    need(changed_hf07==expected_hf07,
                         'GX02_HF07_CHANGED_PATHS_DRIFT '+repr(sorted(changed_hf07^expected_hf07)))
                    if head!=hf07:
                        need(git(root,'rev-parse','HEAD^')==hf07,'GX02_HF08_UNREVIEWED_PARENT')
                        expected_hf08={'scripts/ci/gx03_emulator_host.py',
                               'scripts/ci/test_gx03_hosted.py',
                               'scripts/ci/gx02_ci_verify.py',
                               'scripts/ci/gx03_contract_audit.py'}
                        changed_hf08=set(git(root,'diff','--name-only',hf07,'HEAD').splitlines())
                        need(changed_hf08==expected_hf08,
                             'GX02_HF08_CHANGED_PATHS_DRIFT '+repr(sorted(changed_hf08^expected_hf08)))
        for path in sorted(ALLOWED_CHANGES-{
             '.github/workflows/devtool-xbs-ci.yml',
             'scripts/ci/gx02_ci_verify.py'}):
            need(git(root,'rev-parse',frozen+':'+path)==git(root,'rev-parse','HEAD:'+path),
                 'GX02_INTRODUCED_ARTIFACT_MUTATED '+path)
    changed=set(git(root,'diff','--name-only',BASE_SHA,frozen).splitlines())
    need(changed==ALLOWED_CHANGES,'GX02_INTRODUCING_COMMIT_CHANGED_PATHS_DRIFT '+repr(sorted(changed^ALLOWED_CHANGES)))
    for path in IMMUTABLE_ORIGINAL:
        need(git(root,'rev-parse',BASE_SHA+':'+path)==git(root,'rev-parse','HEAD:'+path),
             'ORIGINAL_ROADMAP_OR_LEDGER_MUTATED '+path)
    from gx01_ci_verify import audit as gx01_audit
    gx01_audit(root)  # independently enforce original 178/131/26 and no false PASS
    from r07a_policy import guard as r07a_guard
    workflow=(root/'.github/workflows/devtool-xbs-ci.yml').read_text()
    r07a_guard(workflow)
    validate_workflow(workflow)
    need('R06B source-pinned Devtool runtime qualification' in workflow and
         'Android API36 rootless instrumentation' in workflow and
         'R06B HF03 actual Android JVM case counts' in workflow,
         'CANONICAL_FULL_CI_COVERAGE_WEAKENED')
    status=json_object((root/'docs/engineering/XBS_GX02_LOCAL_STATUS.json').read_bytes(),'GX02_STATUS')
    need(status.get('gx02')=='IMPLEMENTED_LOCAL_HOSTED_PENDING' and
         status.get('physical_root_qualified') is False and status.get('signed_release_qualified') is False and
         status.get('original_test_cases_passed')==0 and
         status.get('gx01_head_sha')==BASE_SHA, 'GX02_STATUS_FALSE_ACCEPTANCE')
    pins=json_object((root/'docs/engineering/XBS_GX02_SOURCE_PINS.json').read_bytes(),'GX02_SOURCE_PINS')
    need(pins.get('gx01_sha')==BASE_SHA and pins.get('gx01_ci_run_id')==43662889628 and
         pins.get('gx01_ci_conclusion')=='success','GX02_BASELINE_RUN_PIN_DRIFT')
    files=['dist/r06b-go-test-counts.json','dist/r06b-devtool-qualification.json',
           'dist/r09f-final-ledger.json','dist/r09d-risk.coverprofile']
    for file in files:
        p=root/file
        need(p.is_file() and not p.is_symlink(),'GX02_EXPECTED_ACTUAL_HOSTED_RECEIPT_MISSING '+file)
    got=audit_count_receipt((root/files[0]).read_bytes(),head,int(run),int(attempt),allow_skips=2)
    devtool=json_object((root/files[1]).read_bytes(),'R06B_DEVTOOL')
    need(devtool.get('schema')=='xbs-r06b-devtool-runtime/v1' and
         devtool.get('xbs_source_sha')==head and devtool.get('github_run_id')==run and
         devtool.get('github_run_attempt')==attempt,'GX02_DEVTOOL_RUN_SOURCE_MISMATCH')
    need(devtool.get('devtool_source_commit')=='44eb17bbbd63194aa67cde0f7b63d5ada44fb96e' and
         devtool.get('installed_devtool_version')=='1.1.0' and
         re.fullmatch('[0-9a-f]{64}',str(devtool.get('wheel_sha256',''))) is not None,
         'GX02_PINNED_DEVTOOL_NOT_ACTUALLY_EXECUTED')
    native=devtool.get('native')
    need(type(native) is dict and type(native.get('affected')) is list and
         len(native['affected'])==3 and type(native.get('graph_nodes')) is int and native['graph_nodes']>=6,
         'GX02_NATIVE_AFFECTED_TESTS_NOT_ACTUALLY_PROVEN')
    need(devtool.get('android_gradle_ran') is False and devtool.get('device_mutated') is False and
         devtool.get('physical_root_qualified') is False and devtool.get('release_qualified') is False,
         'GX02_UNAUTHORIZED_DEVICE_OR_RELEASE_CLAIM')
    f=json_object((root/files[2]).read_bytes(),'R09F_FINAL_LEDGER')
    need(f.get('xbs_source_sha')==head and f.get('github_run_id')==run and
         f.get('github_run_attempt')==attempt,'GX02_R09F_RECEIPT_NOT_CURRENT_SHA_ATTEMPT')
    need(f.get('device_mutated') is False and f.get('physical_root_qualified') is False and
         f.get('release_qualified') is False,'GX02_R09F_PHYSICAL_CLAIM_REFUSED')
    coverage=audit_coverage((root/files[3]).read_bytes())
    return {'source_sha':head,'github_run_id':int(run),'github_run_attempt':int(attempt),
            'devtool_source_sha':devtool['devtool_source_commit'],
            'devtool_wheel_sha256':devtool['wheel_sha256'],
            'affected_scenarios':len(native['affected']),
            'actual_go_cases':got['case_count'],
            'approved_rootless_go_skips':got['skipped_cases'],
            'full_coverage_profile_sha256':coverage['sha256'],
            'original_tasks':178,'original_cases':131,'original_findings':26,
            'physical_root_qualified':False,'signed_release_qualified':False}

if __name__=='__main__':
    try:
        p=Path(sys.argv[1] if len(sys.argv)>1 else '.').resolve()
        report=audit_source_and_receipts(p,dict(os.environ))
        print('XBS_GX02_INDEPENDENT_HOSTED_AUDIT_PASS '+json.dumps(report,sort_keys=True),flush=True)
    except (Refused,KeyError,ValueError,OSError,subprocess.SubprocessError) as e:
        print('XBS_GX02_HOSTED_REFUSED '+str(e),file=sys.stderr,flush=True)
        raise SystemExit(2)

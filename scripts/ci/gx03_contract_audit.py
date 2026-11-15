#!/usr/bin/env python3
"""GX03 source-only hosted auditor. Never promotes original R10/EM cases to PASS."""
from __future__ import annotations
import os
import json
from pathlib import Path
import re
import subprocess
import sys
from gx03_evidence import need,Refused,expected_inventory,ORIGINAL_CASES

BASE='ea46616247484edc26bcd9e47e0a5fd8f4565208'
FLOW='.github/workflows/devtool-xbs-ci.yml'
COMPAT='.github/workflows/gx03-hosted-compat.yml'
TESTBASE='android-app/app/src/androidTest/java/io/github/mikeceead/xbscompanion/'
ALLOWED={FLOW,COMPAT,'scripts/ci/gx03_evidence.py','scripts/ci/gx03_emulator_host.py',
         'scripts/ci/gx03_contract_audit.py','scripts/ci/test_gx03_hosted.py',
         TESTBASE+'Gx03JourneyInstrumentedTest.java',
         TESTBASE+'Gx03ProcessDeathInstrumentedTest.java',
         'docs/engineering/XBS_GX03_ANDROID_HANDOFF.md',
         'docs/engineering/XBS_GX03_ORIGINAL_CASE_INVENTORY.json',
 'scripts/ci/gx02_ci_verify.py',
 'scripts/ci/r07a_policy.py','scripts/ci/test_gx03_policy.py'}

def git(root,*args):
    p=subprocess.run(['git','-C',str(root),*args],capture_output=True,text=True)
    need(p.returncode==0,'GX03_GIT_FAILED '+repr(args[:3])+' '+p.stderr[-400:])
    return p.stdout.strip()

def verify_source(root:Path):
    need(os.getenv('GITHUB_ACTIONS')=='true' and os.getenv('GITHUB_EVENT_NAME')=='pull_request',
         'GX03_HOSTED_PR_SOURCE_ONLY')
    need(git(root,'rev-parse','HEAD')==os.getenv('XBS_EXPECTED_HEAD_SHA'),
         'GX03_RUN_SOURCE_SHA_MISMATCH')
    # Preserve the exact GX03 introducing commit and allow precisely one
    # reviewed descendant patch. Arbitrary history, paths and rewrites refuse.
    introduction='78475b2d1634711d7d615210479befedf3d14417'
    need(git(root,'rev-parse',introduction+'^')==BASE,'GX03_INTRODUCING_PARENT_DRIFT')
    changed=set(git(root,'diff','--name-only',BASE,introduction).splitlines())
    need(changed==ALLOWED,'GX03_SOURCE_FILE_INVENTORY_DRIFT '+repr(sorted(changed^ALLOWED)))
    if head!=introduction:
        need(git(root,'rev-parse','HEAD^')==introduction,'GX03_UNREVIEWED_HOTFIX_PARENT')
        expected_hotfix={'scripts/ci/test_r05c_hf08_emulator_shell_scope.py',
                         'scripts/ci/gx02_ci_verify.py',
                         'scripts/ci/gx03_contract_audit.py'}
        changed_hotfix=set(git(root,'diff','--name-only',introduction,'HEAD').splitlines())
        need(changed_hotfix==expected_hotfix,
             'GX03_HOTFIX_SOURCE_FILE_DRIFT '+repr(sorted(changed_hotfix^expected_hotfix)))
    need(not git(root,'status','--porcelain'),'GX03_DIRTY_HOSTED_CHECKOUT')

def verify_local(root:Path):
    original=root/'docs/engineering/gx01_handoff/XBS_TEST_MATRIX_v3.json'
    need(original.is_file(),'GX01_ORIGINAL_CASE_MATRIX_MISSING')
    original_matrix=json.loads(original.read_text())
    inventory=expected_inventory(original_matrix)
    stored=json.loads((root/'docs/engineering/XBS_GX03_ORIGINAL_CASE_INVENTORY.json').read_text())
    need(stored['original_case_ids']==inventory['original_case_ids'] and
         stored['original_case_count']==inventory['original_case_count'] and
         stored['original_cases_accepted']==[] and stored['signed_release']==False and
         stored['physical_root_qualified']==False,'GX03_CASE_INVENTORY_FALSE_CLAIM')
    from r07a_policy import audit as r07a_audit
    need(r07a_audit(root).get('reviewed_workflows')==2,
         'GX03_R07A_COMPAT_NOT_SOURCE_PINNED')
    workflow=(root/FLOW).read_text()
    need(workflow.count('name: GX03 Android journeys and compatibility source gates')==1,
         'GX03_SOURCE_GATE_MISSING_OR_DUPLICATE')
    need(workflow.count('uses: reactivecircus/android-emulator-runner@a421e43855164a8197daf9d8d40fe71c6996bb0d')==1,
         'CANONICAL_API36_EMULATOR_CHANGED')
    need(workflow.count('connectedDebugAndroidTest')==1,'CANONICAL_R05C_EMULATOR_TASK_CHANGED')
    need('gx03_emulator_host.py --mode preflight --api 36' in workflow and
         'gx03_emulator_host.py --mode api36 --api 36' in workflow,
         'GX03_API36_REAL_EMULATOR_GATE_MISSING')
    need('r05c_verify_instrumentation.py' in workflow and
         'R05C source and workflow contracts' in workflow,'R05C_ORIGINAL_GATE_REMOVED')
    need(workflow.count('          ref: ${{ github.event.pull_request.head.sha || github.sha }}')==4,
         'EXACT_SHA_CHECKOUT_QUANTITY_CHANGED')
    need(workflow.count('          fetch-depth: 12')==1,'STACKED_CHECKOUT_HISTORY_TOO_SHALLOW')
    need('needs: [go-contract, android-companion-jvm]' in workflow,
         'API36_REQUIRED_JOB_DEPENDENCIES_CHANGED')
    need('GX03 actual app/test APKs and redacted screenshots' in workflow and
         'if-no-files-found: error' in workflow,
         'GX03_API36_ARTIFACT_NOT_MANDATORY')
    compat=(root/COMPAT).read_text()
    need(compat.count('uses: reactivecircus/android-emulator-runner@a421e43855164a8197daf9d8d40fe71c6996bb0d')==2,
         'COMPAT_REAL_EMULATOR_JOBS_MISSING')
    need(compat.count('api-level: 26')==1 and compat.count('api-level: 34')==1,
         'API26_API34_SHARDS_MISSING')
    need(compat.count('connectedDebugAndroidTest')==2 and compat.count('if-no-files-found: error')==2,
         'COMPAT_REQUIRED_TESTS_OR_ARTIFACTS_MISSING')
    need('permissions:\n  contents: read' in compat and '  pull_request:\n' in compat,
         'COMPAT_TOKEN_OR_PR_POLICY_CHANGED')
    need('continue-on-error' not in workflow and 'continue-on-error' not in compat,
         'GX03_FAIL_OPEN_WORKFLOW')
    java=(root/(TESTBASE+'Gx03ProcessDeathInstrumentedTest.java')).read_text()
    need('stageAWriteDurablePendingOwnership' in java and
         'stageBInspectRecoveryAfterExternalForceStop' in java,
         'GX03_REAL_PROCESS_DEATH_STAGES_MISSING')
    host=(root/'scripts/ci/gx03_emulator_host.py').read_text()
    need("'shell','am','force-stop',PKG" in host and
         'GITHUB_ACTIONS' in host and 'GITHUB_RUN_ATTEMPT' in host and
         'root_command_effects_executed' in host,'GX03_HOSTED_PROCESS_GATE_LOST')
    return len(ORIGINAL_CASES)

if __name__=='__main__':
    import argparse
    ap=argparse.ArgumentParser()
    ap.add_argument('repo',nargs='?',default='.')
    ap.add_argument('--hosted',action='store_true')
    a=ap.parse_args()
    try:
        root=Path(a.repo).resolve()
        amount=verify_local(root)
        if a.hosted:verify_source(root)
        print('GX03_SOURCE_POLICY_PASS original_em_inventory='+str(amount)+
              ' cases_accepted=0 no_root_no_release=true')
    except (Refused,ValueError,KeyError,OSError) as e:
        print('GX03_SOURCE_POLICY_REFUSED '+str(e),file=sys.stderr)
        sys.exit(2)

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
    head=git(root,'rev-parse','HEAD')
    need(head==os.getenv('XBS_EXPECTED_HEAD_SHA'),
         'GX03_RUN_SOURCE_SHA_MISMATCH')
    # Preserve the exact GX03 introducing commit and allow precisely one
    # reviewed descendant patch. Arbitrary history, paths and rewrites refuse.
    introduction='78475b2d1634711d7d615210479befedf3d14417'
    need(git(root,'rev-parse',introduction+'^')==BASE,'GX03_INTRODUCING_PARENT_DRIFT')
    changed=set(git(root,'diff','--name-only',BASE,introduction).splitlines())
    need(changed==ALLOWED,'GX03_SOURCE_FILE_INVENTORY_DRIFT '+repr(sorted(changed^ALLOWED)))
    if head!=introduction:
        hf05='8604a5b97c4b07702a4ad024dbd614d59645a8a6'
        need(git(root,'rev-parse',hf05+'^')==introduction,
             'GX03_HF05_EXACT_PARENT_DRIFT')
        expected_hf05={'scripts/ci/test_r05c_hf08_emulator_shell_scope.py',
                         'scripts/ci/gx02_ci_verify.py',
                         'scripts/ci/gx03_contract_audit.py'}
        changed_hf05=set(git(root,'diff','--name-only',introduction,hf05).splitlines())
        need(changed_hf05==expected_hf05,
             'GX03_HF05_SOURCE_FILE_DRIFT '+repr(sorted(changed_hf05^expected_hf05)))
        if head!=hf05:
            hf06='7ee71272537948ab04a7f581e520d307e9044321'
            need(git(root,'rev-parse',hf06+'^')==hf05,'GX03_HF06_PARENT_DRIFT')
            expected_hf06={'scripts/ci/gx02_ci_verify.py',
                           'scripts/ci/gx03_contract_audit.py'}
            changed_hf06=set(git(root,'diff','--name-only',hf05,hf06).splitlines())
            need(changed_hf06==expected_hf06,
                 'GX03_HF06_SOURCE_FILE_DRIFT '+repr(sorted(changed_hf06^expected_hf06)))
            if head!=hf06:
                hf07='b0298c83e5eaaa730f6333e5a750025599b04fa2'
                need(git(root,'rev-parse',hf07+'^')==hf06,'GX03_HF07_PARENT_DRIFT')
                expected_hf07={'scripts/ci/gx03_emulator_host.py',
                               'scripts/ci/test_gx03_hosted.py',
                               'scripts/ci/gx02_ci_verify.py',
                               'scripts/ci/gx03_contract_audit.py'}
                changed_hf07=set(git(root,'diff','--name-only',hf06,hf07).splitlines())
                need(changed_hf07==expected_hf07,
                     'GX03_HF07_SOURCE_FILE_DRIFT '+repr(sorted(changed_hf07^expected_hf07)))
                if head!=hf07:
                        hf08='aab3ff6d9e6cd9c343218606a5d8c6c619f1c487'
                        need(git(root,'rev-parse',hf08+'^')==hf07,'GX03_HF08_PARENT_DRIFT')
                        expected_hf08={'scripts/ci/gx03_emulator_host.py',
                                       'scripts/ci/test_gx03_hosted.py',
                                       'scripts/ci/gx02_ci_verify.py',
                                       'scripts/ci/gx03_contract_audit.py'}
                        changed_hf08=set(git(root,'diff','--name-only',hf07,hf08).splitlines())
                        need(changed_hf08==expected_hf08,'GX03_HF08_CHANGED_PATHS_DRIFT')
                        if head!=hf08:
                            gx04='fc1c9760c2fdf63f7b6c5ee64c98e657a9240627'
                            need(git(root,'rev-parse',gx04+'^')==hf08,'GX03_GX04_PARENT_DRIFT')
                            expected_gx04={
                                'android-app/app/src/androidTest/java/io/github/mikeceead/xbscompanion/Gx04TilesInstrumentedTest.java',
                                'android-app/app/src/main/AndroidManifest.xml',
                                'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/ExecutionSource.java',
                                'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/XbsPauseTileService.java',
                                'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/XbsProfilesTileService.java',
                                'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/XbsQuickTileService.java',
                                'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/XbsRestoreDefaultsTileService.java',
                                'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/XbsSafeModeTileService.java',
                                'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/XbsSaverTileService.java',
                                'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/XbsTileCompletionPolicy.java',
                                'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/XbsTileConfiguration.java',
                                'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/XbsTileController.java',
                                'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/XbsTileDecision.java',
                                'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/XbsTileLifecycleStore.java',
                                'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/XbsTileRefreshReceiver.java',
                                'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/XbsTileSettingsActivity.java',
                                'docs/engineering/XBS_GX04_COVERAGE.json',
                                'docs/engineering/XBS_GX04_P01_NATIVE_TILE_HANDOFF.md',
                                'scripts/ci/gx02_ci_verify.py',
                                'scripts/ci/gx03_contract_audit.py',
                                'scripts/ci/gx03_emulator_host.py',
                                'scripts/ci/gx04_decision_harness.java.txt',
                                'scripts/ci/test_gx04_tiles.py',
                            }
                            changed_gx04=set(git(root,'diff','--name-only',hf08,gx04).splitlines())
                            need(changed_gx04==expected_gx04,'GX03_GX04_CHANGED_PATHS_DRIFT '+repr(sorted(changed_gx04^expected_gx04)))
                            if head!=gx04:
                                hf01='be9fad6af9ae622c1a3dcb64eb968f4278dfb6c1'
                                need(git(root,'rev-parse',hf01+'^')==gx04,'GX03_GX04_HF01_PARENT_DRIFT')
                                expected_hf01={
                                    'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/XbsQuickTileService.java',
                                    'scripts/ci/gx02_ci_verify.py',
                                    'scripts/ci/gx03_contract_audit.py',
                                    'scripts/ci/gx03_emulator_host.py',
                                    'scripts/ci/test_gx04_tiles.py',
                                }
                                changed_hf01=set(git(root,'diff','--name-only',gx04,hf01).splitlines())
                                need(changed_hf01==expected_hf01,'GX03_GX04_HF01_PATH_DRIFT '+repr(sorted(changed_hf01^expected_hf01)))
                                if head!=hf01:
                                    gx05='1b5fbcd42bd39956c5251ea8f43eadbddf7aefcf'
                                    need(git(root,'rev-parse',gx05+'^')==hf01,'GX03_GX05_PARENT_DRIFT')
                                    expected_gx05={'android-app/app/src/androidTest/java/io/github/mikeceead/xbscompanion/Gx05PreflightInstrumentedTest.java','config/delivery-required-ci.json','docs/engineering/XBS_GX05_CONSENT_MATRIX.json','docs/engineering/XBS_GX05_COVERAGE.json','docs/engineering/XBS_GX05_HANDOFF.md','scripts/ci/gx02_ci_verify.py','scripts/ci/gx03_contract_audit.py','scripts/ci/gx03_emulator_host.py','scripts/ci/gx05_hosted_measure.py','scripts/ci/gx05_preflight.py','scripts/ci/test_gx05_hosted_measure.py','scripts/ci/test_gx05_preflight.py'}
                                    changed_gx05=set(git(root,'diff','--name-only',hf01,gx05).splitlines())
                                    need(changed_gx05==expected_gx05,'GX03_GX05_FILES_DRIFT '+repr(sorted(changed_gx05^expected_gx05)))
                                    if head!=gx05:
                                        from gx06_stack_gate import (GX06_CHANGED_PATHS, GX05_HEAD, GX06_HEAD, R08R10_HEAD,
                                            R08R10_CHANGED_PATHS, AGG_CHANGED_PATHS, AGG_HEAD, FIX_CHANGED_PATHS)
                                        need(git(root,'rev-parse',GX06_HEAD+'^')==GX05_HEAD,
                                            'GX06_IMMUTABLE_PARENT_DRIFT')
                                        parent=git(root,'rev-parse','HEAD^')
                                        need(parent in (GX05_HEAD,GX06_HEAD,R08R10_HEAD,AGG_HEAD),'AGG_UNREVIEWED_PARENT')
                                        gx06_target=GX06_HEAD if parent!=GX05_HEAD else 'HEAD'
                                        changed_gx06=set(git(root,'diff','--name-only',gx05,gx06_target).splitlines())
                                        need(changed_gx06==GX06_CHANGED_PATHS,
                                            'GX06_CHANGED_PATHS_DRIFT '+repr(sorted(changed_gx06^GX06_CHANGED_PATHS)))
                                        if parent in (GX06_HEAD,R08R10_HEAD,AGG_HEAD):
                                            r08_target=R08R10_HEAD if parent==R08R10_HEAD else 'HEAD'
                                            next_changed=set(git(root,'diff','--name-only',GX06_HEAD,r08_target).splitlines())
                                            need(next_changed==R08R10_CHANGED_PATHS,
                                                'R08R10_CHANGED_PATHS_DRIFT '+repr(sorted(next_changed^R08R10_CHANGED_PATHS)))
                                        if parent in (R08R10_HEAD,AGG_HEAD):
                                            aggregate_target=AGG_HEAD if parent==AGG_HEAD else 'HEAD'
                                            integration_changed=set(git(root,'diff','--name-only',R08R10_HEAD,aggregate_target).splitlines())
                                            need(integration_changed==AGG_CHANGED_PATHS,
                                                'AGG_CHANGED_PATHS_DRIFT '+repr(sorted(integration_changed^AGG_CHANGED_PATHS)))
                                            if parent==AGG_HEAD:
                                                fix_changed=set(git(root,'diff','--name-only',AGG_HEAD,'HEAD').splitlines())
                                                need(fix_changed==FIX_CHANGED_PATHS,
                                                    'R07A_FIX_PATHS_DRIFT '+repr(sorted(fix_changed^FIX_CHANGED_PATHS)))
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
    expected_workflows=3 if (root/'.github/workflows/devtool-s7-p05-android-consumer.yml').is_file() else 2
    need(r07a_audit(root).get('reviewed_workflows')==expected_workflows,
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
    need(workflow.count('          fetch-depth: 24')==1,'STACKED_CHECKOUT_HISTORY_TOO_SHALLOW')
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
    need("'shell','am','instrument','-w','-r','-e','clearPackageData','false'" in host and
         "install_verified_pair_for_stage(serial,report['apk_pair'])" in host and
         "second=direct_stage(serial,cls+'#stageBInspectRecoveryAfterExternalForceStop')" in host and
         "gradle_test(cls+'#stageAWriteDurablePendingOwnership')" not in host,
         'GX03_HF07_PERSISTENT_PROCESS_STAGE_NOT_ENFORCED')
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

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
FIX_HEAD = '5cfa4e6e846263d85338b5d394e201fd6ac4f6c1'
HF01_HEAD = '455c7493ee3b14e7ac5d0595d82daf2f69983e0f'
HF01_TREE = 'ec485329264b86501fd70e76accc8e37ac060071'
INT01_BRANCH = 'feature/xbs-aggregate-main-qualification-20261127'
MAIN_TREE = 'f5d7788728ec808e4ba883519ac75d0c54d98858'
INT01_PUBLISHED_HEAD = '81cd237d8e4ad15fa305cd9243dcdb686347a515'
INTEGRATED_MAIN_HEAD = 'ff9e67661a76e0114a025c5e516524750ba30e88'
INTEGRATED_MAIN_TREE = '648ba25d3fc15b0fecec9ad097eb7fc3dc4235a0'
UI03_BRANCH = 'feature/xbs-r08-r10-transaction-safety-20261127'
UI02_PUBLISHED_HEAD = '49a49e4ff48ff83417737cb62b02dd0a4926ac99'
UI02_MERGED_HEAD = '043b1851d31fef0601e76443f47b88e59f3ca373'
UI02_MERGED_TREE = '5638a149d150991d3b5fc93c6070bacc8106c35e'
UI03_CHANGED_PATHS = frozenset({
    '.github/workflows/devtool-xbs-ci.yml',
    'android-app/app/src/androidTest/java/io/github/mikeceead/xbscompanion/Gx10EditorInstrumentedTest.java',
    'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/EditorBackend.java',
    'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/ManagedPathPolicy.java',
    'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/RootFileSnapshot.java',
    'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/XbsConfigActivity.java',
    'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/XbsConfigApi.java',
    'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/XbsConfigTransaction.java',
    'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/XbsEssentialsActivity.java',
    'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/XbsEventEditorActivity.java',
    'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/XbsRootApi.java',
    'android-app/app/src/test/java/io/github/mikeceead/xbscompanion/R05bTransactionGenerationTest.java',
    'android-app/app/src/test/java/io/github/mikeceead/xbscompanion/XbsConfigTransactionTest.java',
    'docs/engineering/XBS_R08_R10_UI03.md',
    'docs/engineering/XBS_R08_R10_UI03_EVIDENCE.json',
    'scripts/ci/aggregate_integration.py',
    'scripts/ci/gx06_stack_gate.py',
    'scripts/ci/gx10_transaction.py',
    'scripts/ci/test_aggregate_integration.py',
    'scripts/ci/test_gx10_transaction.py',
})
UI02_BRANCH = 'feature/xbs-r08-r10-editor-lifecycle-20261127'
UI01_HF01_PUBLISHED_HEAD = '4809610597722d10d4d6c7d3c2231d2c547ddc21'
UI01_MERGED_HEAD = 'f98ae063f6b20a6a4b6cac86342dbacaefe873b0'
UI01_MERGED_TREE = 'cad6c6abbb0eeff73b888c8f1b67ed2bc7abe01f'
UI02_CHANGED_PATHS = frozenset({
    '.github/workflows/devtool-xbs-ci.yml',
    'android-app/app/src/androidTest/java/io/github/mikeceead/xbscompanion/Gx10EditorInstrumentedTest.java',
    'android-app/app/src/debug/java/io/github/mikeceead/xbscompanion/EditorBackendProvider.java',
    'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/EditorBackend.java',
    'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/EditorSession.java',
    'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/EssentialsDraftPolicy.java',
    'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/TaskerEventEditActivity.java',
    'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/TaskerPluginActivity.java',
    'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/TaskerStateEditActivity.java',
    'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/XbsConfigActivity.java',
    'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/XbsConfigApi.java',
    'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/XbsEssentialsActivity.java',
    'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/XbsEventEditorActivity.java',
    'android-app/app/src/main/res/values/ids.xml',
    'android-app/app/src/release/java/io/github/mikeceead/xbscompanion/EditorBackendProvider.java',
    'docs/engineering/XBS_R08_R10_UI02.md',
    'docs/engineering/XBS_R08_R10_UI02_EVIDENCE.json',
    'scripts/ci/aggregate_integration.py',
    'scripts/ci/gx03_emulator_host.py',
    'scripts/ci/gx06_stack_gate.py',
    'scripts/ci/gx10_editor.py',
    'scripts/ci/test_aggregate_integration.py',
    'scripts/ci/test_gx10_editor.py',
})
UI01_BRANCH = 'feature/xbs-r08-r10-acceptance-closure-20261127'
UI01_PUBLISHED_HEAD = '8b911a1966375c01e2521e3faa3b74cc7b9dcdad'
UI01_PUBLISHED_TREE = 'a325734f9ea08153494eb5ece81502e01ba33c7f'
UI01_HF01_CHANGED_PATHS = frozenset({
    'android-app/app/src/androidTest/java/io/github/mikeceead/xbscompanion/Gx10PresentationInstrumentedTest.java',
    'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/MainActivity.java',
    'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/XbsTileSettingsActivity.java',
    'android-app/app/src/main/res/values/ids.xml',
    'docs/engineering/XBS_R08_R10_UI01_HF01.md',
    'scripts/ci/aggregate_integration.py',
    'scripts/ci/gx06_stack_gate.py',
    'scripts/ci/gx10_presentation.py',
    'scripts/ci/test_aggregate_integration.py',
    'scripts/ci/test_gx10_presentation.py',
})
UI01_CHANGED_PATHS = frozenset({
    '.github/workflows/devtool-xbs-ci.yml',
    'android-app/app/src/androidTest/java/io/github/mikeceead/xbscompanion/Gx10PresentationInstrumentedTest.java',
    'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/AppPalette.java',
    'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/MainActivity.java',
    'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/ThemePalettePolicy.java',
    'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/UiKit.java',
    'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/XbsActivity.java',
    'android-app/app/src/main/java/io/github/mikeceead/xbscompanion/XbsTileSettingsActivity.java',
    'android-app/app/src/main/res/values-ar/strings.xml',
    'android-app/app/src/main/res/values-night/colors.xml',
    'android-app/app/src/main/res/values-v27/styles.xml',
    'android-app/app/src/main/res/values/colors.xml',
    'android-app/app/src/main/res/values/ids.xml',
    'android-app/app/src/main/res/values/strings.xml',
    'android-app/app/src/main/res/values/styles.xml',
    'android-app/app/src/test/java/io/github/mikeceead/xbscompanion/ThemePalettePolicyTest.java',
    'docs/engineering/XBS_R08_R10_UI01_EVIDENCE.json',
    'docs/engineering/XBS_R08_R10_UI01_HANDOFF.md',
    'scripts/ci/aggregate_integration.py',
    'scripts/ci/gx03_emulator_host.py',
    'scripts/ci/gx03_evidence.py',
    'scripts/ci/gx06_stack_gate.py',
    'scripts/ci/gx10_presentation.py',
    'scripts/ci/test_aggregate_integration.py',
    'scripts/ci/test_gx10_presentation.py',
})
INT01_CHANGED_PATHS = frozenset({
    'cmd/xbsrepo/main.go',
    'internal/config/r05b_source_digest.go',
    'internal/config/r05b_source_digest_test.go',
    'internal/config/r09a_fuzz_test.go',
    'internal/control/control.go',
    'internal/control/r05a_observation.go',
    'internal/control/r05a_observation_test.go',
    'internal/control/r05b_cli_integration_test.go',
    'internal/control/r05b_generation.go',
    'internal/control/r05b_generation_test.go',
    'internal/control/r09a_fuzz_test.go',
    'internal/eventbridge/r09a_fuzz_test.go',
    'internal/module/module_test.go',
    'internal/module/r06a_handoff_test.go',
    'scripts/ci/test_r05a_source_contract.py',
    '.github/workflows/devtool-xbs-ci.yml',
    'config/delivery-required-ci.json',
    'docs/engineering/XBS_AGG_INT01_HANDOFF.md',
    'docs/engineering/XBS_AGG_INT01_EVIDENCE.json',
    'scripts/ci/aggregate_handback.py',
    'scripts/ci/gx05_preflight.py',
    'scripts/ci/aggregate_integration.py',
    'scripts/ci/gx02_ci_verify.py',
    'scripts/ci/gx03_contract_audit.py',
    'scripts/ci/gx03_emulator_host.py',
    'scripts/ci/gx06_stack_gate.py',
    'scripts/ci/r08_r10_acceptance.py',
    'scripts/ci/stack_ancestry.py',
    'scripts/ci/test_aggregate_handback.py',
    'scripts/ci/test_aggregate_integration.py',
    'scripts/ci/test_gx06_stack_gate.py',
    'scripts/ci/test_r08_r10_acceptance.py',
})
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
HF01_CHANGED_PATHS = frozenset({
    'conftest.py',
    'docs/engineering/XBS_AGGREGATE_INTEGRATION_20261118.md',
    'scripts/ci/aggregate_integration.py',
    'scripts/ci/gx02_ci_verify.py',
    'scripts/ci/gx03_contract_audit.py',
    'scripts/ci/gx06_stack_gate.py',
    'scripts/ci/r08_r10_acceptance.py',
    'scripts/ci/stack_ancestry.py',
    'scripts/ci/test_aggregate_integration.py',
    'scripts/ci/test_r08_r10_acceptance.py',
    'scripts/ci/test_gx06_stack_gate.py',
})
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

def reviewed_stage(root: Path, *, head=None) -> str:
    """All consumers share the same exact interval and direct-parent policy."""
    from stack_ancestry import Segment, verify_reviewed_history, HistoryRefused
    segments = (
        Segment('GX06', GX05_HEAD, GX06_HEAD, GX06_CHANGED_PATHS),
        Segment('R08R10', GX06_HEAD, R08R10_HEAD, R08R10_CHANGED_PATHS),
        Segment('AGG', R08R10_HEAD, AGG_HEAD, AGG_CHANGED_PATHS),
        Segment('FIX', AGG_HEAD, FIX_HEAD, FIX_CHANGED_PATHS),
        Segment('HF01', FIX_HEAD, HF01_HEAD, HF01_CHANGED_PATHS),
        Segment('INT01', HF01_HEAD, None, INT01_CHANGED_PATHS),
    )
    try:
        head = head or git(root, 'rev-parse', 'HEAD')
        parents = git(root, 'rev-list', '--parents', '-n', '1', head).split()
        if len(parents) in (2,3) and parents[1] == UI02_MERGED_HEAD:
            need(git(root,'rev-list','--parents','-n','1',UI02_MERGED_HEAD).split()==
                 [UI02_MERGED_HEAD,UI01_MERGED_HEAD,UI02_PUBLISHED_HEAD], 'UI03_PREVIOUS_MERGE_DRIFT')
            need(reviewed_stage(root,head=UI02_MERGED_HEAD)=='UI02_MAIN','UI03_PREVIOUS_STAGE_DRIFT')
            need(git(root,'rev-parse',UI02_MERGED_HEAD+'^{tree}')==UI02_MERGED_TREE,'UI03_PREVIOUS_TREE_DRIFT')
            candidate=head if len(parents)==2 else parents[2]
            need(verify_reviewed_history(root,(Segment('UI03',UI02_MERGED_HEAD,None,UI03_CHANGED_PATHS),),head=candidate)=='UI03','UI03_CANDIDATE_NOT_REVIEWED')
            if len(parents)==3:
                need(git(root,'rev-parse',head+'^{tree}')==git(root,'rev-parse',candidate+'^{tree}'),'UI03_MERGE_TREE_DRIFT')
                return 'UI03_MAIN'
            return 'UI03'
        if len(parents) in (2,3) and parents[1] == UI01_MERGED_HEAD:
            need(git(root,'rev-list','--parents','-n','1',UI01_MERGED_HEAD).split()==
                 [UI01_MERGED_HEAD,INTEGRATED_MAIN_HEAD,UI01_HF01_PUBLISHED_HEAD], 'UI02_PREVIOUS_MERGE_DRIFT')
            need(reviewed_stage(root,head=UI01_MERGED_HEAD)=='UI01_HF01_MAIN','UI02_PREVIOUS_STAGE_DRIFT')
            need(git(root,'rev-parse',UI01_MERGED_HEAD+'^{tree}')==UI01_MERGED_TREE,'UI02_PREVIOUS_TREE_DRIFT')
            candidate=head if len(parents)==2 else parents[2]
            need(verify_reviewed_history(root,(Segment('UI02',UI01_MERGED_HEAD,None,UI02_CHANGED_PATHS),),head=candidate)=='UI02','UI02_CANDIDATE_NOT_REVIEWED')
            if len(parents)==3:
                need(git(root,'rev-parse',head+'^{tree}')==git(root,'rev-parse',candidate+'^{tree}'),'UI02_MERGE_TREE_DRIFT')
                return 'UI02_MAIN'
            return 'UI02'
        if (len(parents) in (2,3) and parents[1] == INTEGRATED_MAIN_HEAD) or (len(parents)==2 and parents[1]==UI01_PUBLISHED_HEAD):
            need(git(root, 'rev-list', '--parents', '-n', '1', INTEGRATED_MAIN_HEAD).split()
                 == [INTEGRATED_MAIN_HEAD, AGG_MAIN_HEAD, INT01_PUBLISHED_HEAD], 'UI01_MAIN_HISTORY_DRIFT')
            need(verify_reviewed_history(root, segments, head=INT01_PUBLISHED_HEAD) == 'INT01',
                 'UI01_INTEGRATED_CANDIDATE_DRIFT')
            need(git(root, 'rev-parse', AGG_MAIN_HEAD+'^{tree}') == MAIN_TREE and
                 git(root, 'rev-parse', INTEGRATED_MAIN_HEAD+'^{tree}') == INTEGRATED_MAIN_TREE and
                 git(root, 'rev-parse', INT01_PUBLISHED_HEAD+'^{tree}') == INTEGRATED_MAIN_TREE,
                 'UI01_INTEGRATED_TREE_DRIFT')
            candidate = head if len(parents)==2 else parents[2]
            candidate_parents = git(root, 'rev-list', '--parents', '-n', '1', candidate).split()
            if candidate_parents == [candidate, UI01_PUBLISHED_HEAD]:
                need(git(root, 'rev-parse', UI01_PUBLISHED_HEAD+'^{tree}') == UI01_PUBLISHED_TREE,
                     'UI01_HF01_PREIMAGE_TREE_DRIFT')
                reviewed = (Segment('UI01', INTEGRATED_MAIN_HEAD, UI01_PUBLISHED_HEAD, UI01_CHANGED_PATHS),
                            Segment('UI01_HF01', UI01_PUBLISHED_HEAD, None, UI01_HF01_CHANGED_PATHS))
                need(verify_reviewed_history(root, reviewed, head=candidate) == 'UI01_HF01',
                     'UI01_HF01_CANDIDATE_NOT_REVIEWED')
                stage = 'UI01_HF01'
            else:
                need(verify_reviewed_history(root, (Segment('UI01', INTEGRATED_MAIN_HEAD, None, UI01_CHANGED_PATHS),), head=candidate) == 'UI01',
                     'UI01_CANDIDATE_NOT_REVIEWED')
                stage = 'UI01'
            if len(parents)==3:
                need(git(root, 'rev-parse', head+'^{tree}') == git(root, 'rev-parse', candidate+'^{tree}'),
                     'UI01_MERGE_TREE_DRIFT')
                return stage + '_MAIN'
            return stage
        if len(parents) == 3:
            need(parents[:2] == [head, AGG_MAIN_HEAD], 'INT01_MAIN_PARENT_DRIFT')
            candidate = parents[2]
            need(verify_reviewed_history(root, segments, head=candidate) == 'INT01',
                 'INT01_MAIN_CANDIDATE_NOT_REVIEWED')
            need(git(root, 'rev-parse', AGG_MAIN_HEAD+'^{tree}') == MAIN_TREE,
                 'INT01_MAIN_TREE_DRIFT')
            need(git(root, 'rev-parse', head+'^{tree}') == git(root, 'rev-parse', candidate+'^{tree}'),
                 'INT01_MAIN_MERGE_TREE_DRIFT')
            return 'INT01_MAIN'
        return verify_reviewed_history(root, segments, head=head)
    except HistoryRefused as exc:
        raise Refused('GX06_REVIEWED_HISTORY_REFUSED ' + str(exc)) from exc


def stage_branch(stage: str) -> str:
    return {'GX06': GX06_BRANCH, 'R08R10': R08R10_BRANCH, 'AGG': AGG_BRANCH,
            'FIX': FIX_BRANCH, 'HF01': FIX_BRANCH, 'INT01': INT01_BRANCH, 'INT01_MAIN': 'main', 'UI01': UI01_BRANCH, 'UI01_MAIN': 'main', 'UI01_HF01': UI01_BRANCH, 'UI01_HF01_MAIN': 'main', 'UI02': UI02_BRANCH, 'UI02_MAIN': 'main', 'UI03': UI03_BRANCH, 'UI03_MAIN': 'main'}[stage]


def stage_base_branch(stage: str) -> str:
    return {'GX06': GX05_BRANCH, 'R08R10': GX06_BRANCH, 'AGG': R08R10_BRANCH,
            'FIX': AGG_BRANCH, 'HF01': AGG_BRANCH, 'INT01': 'main', 'INT01_MAIN': 'main', 'UI01': 'main', 'UI01_MAIN': 'main', 'UI01_HF01': 'main', 'UI01_HF01_MAIN': 'main', 'UI02': 'main', 'UI02_MAIN': 'main', 'UI03': 'main', 'UI03_MAIN': 'main'}[stage]


def hosted_branch(env):
    if env.get('GITHUB_EVENT_NAME') == 'pull_request':
        return env.get('GITHUB_HEAD_REF')
    if env.get('GITHUB_EVENT_NAME') in ('push', 'workflow_dispatch') and env.get('GITHUB_REF') == 'refs/heads/main':
        return 'main'
    return None


def validate_hosted_context(root, env, *, stage=None, head=None):
    """A direct-main merge is observed, never authorized, by this source gate."""
    need(env.get('GITHUB_ACTIONS') == 'true', 'HOSTED_CONTEXT_REQUIRED')
    stage = stage or reviewed_stage(root)
    head = head or git(root, 'rev-parse', 'HEAD')
    event = env.get('GITHUB_EVENT_NAME')
    need(env.get('XBS_EXPECTED_HEAD_SHA') == head, 'GX06_HOSTED_SOURCE_SHA_DRIFT')
    need(hosted_branch(env) == stage_branch(stage), 'GX06_HOSTED_BRANCH_DRIFT')
    if stage in ('INT01_MAIN','UI01_MAIN','UI01_HF01_MAIN','UI02_MAIN','UI03_MAIN'):
        need(event in ('push', 'workflow_dispatch') and env.get('GITHUB_SHA') == head
             and not env.get('GITHUB_HEAD_REF') and not env.get('GITHUB_BASE_REF'),
             'INT01_MAIN_EVENT_REFUSED')
    else:
        need(event == 'pull_request', 'GX06_HOSTED_PR_SOURCE_IDENTITY_REFUSED')
        if stage in ('INT01','UI01','UI01_HF01','UI02','UI03'):
            need(env.get('GITHUB_BASE_REF') == 'main', 'INT01_PR_BASE_DRIFT')
    run, attempt = env.get('GITHUB_RUN_ID', ''), env.get('GITHUB_RUN_ATTEMPT', '')
    need(run.isdigit() and int(run)>0 and attempt.isdigit() and int(attempt)>0,
         'GX06_HOSTED_RUN_ATTEMPT_MISSING')
    return run, attempt


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
    branch=(hosted_branch(env) if env.get('GITHUB_ACTIONS')=='true'
            else git(root,'symbolic-ref','--short','HEAD'))
    stage=reviewed_stage(root)
    need(branch==stage_branch(stage),'GX06_UNREVIEWED_BRANCH')
    aggregate=stage in ('AGG','FIX','HF01','INT01','INT01_MAIN','UI01','UI01_MAIN','UI01_HF01','UI01_HF01_MAIN','UI02','UI02_MAIN','UI03','UI03_MAIN')
    continuation=stage!='GX06'
    need(git(root,'rev-parse',GX05_HEAD+'^{tree}')==GX05_TREE,'GX06_GX05_TREE_DRIFT')
    need(not git(root,'status','--porcelain','--untracked-files=all'),
         'GX06_DIRTY_SOURCE_CHECKOUT')
    if continuation:
        from r08_r10_acceptance import audit_source
        audit_source(root, expected_base=stage_base_branch(stage))
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
    expected_base=stage_base_branch(stage)
    need(policy.get('base_branch')==expected_base and
         policy.get('repository')==REPO and
         policy.get('event')=='pull_request' and policy.get('release_qualification')=='separate' and
         {(v.get('name'),v.get('workflow_id')) for v in policy.get('required_workflows',[])}==required_workflows,
         'GX06_DELIVERY_CI_POLICY_INVALID_FOR_TARGET_BRANCH')
    if env.get('GITHUB_ACTIONS')=='true':
        run,attempt=validate_hosted_context(root,env,stage=stage,head=head)
    else: run=attempt=''
    if stage in ('INT01','INT01_MAIN','UI01','UI01_MAIN','UI01_HF01','UI01_HF01_MAIN','UI02','UI02_MAIN','UI03','UI03_MAIN'):
        from aggregate_handback import validate_documents
        validate_documents(root)
    if stage in ('UI01','UI01_MAIN','UI01_HF01','UI01_HF01_MAIN','UI02','UI02_MAIN','UI03','UI03_MAIN'):
        from gx10_presentation import validate_presentation_source, validate_handoff
        validate_presentation_source(root)
        validate_handoff(root)
        if stage in ('UI01_HF01','UI01_HF01_MAIN','UI02','UI02_MAIN','UI03','UI03_MAIN'):
            from gx10_presentation import validate_layout_repair
            validate_layout_repair(root)
    if stage in ('UI02','UI02_MAIN','UI03','UI03_MAIN'):
        from gx10_editor import validate_editor_source,validate_handoff as validate_editor_handoff
        validate_editor_source(root);validate_editor_handoff(root)
    if stage in ('UI03','UI03_MAIN'):
        from gx10_transaction import validate_transaction_source
        validate_transaction_source(root)
    out=dict(original, schema='xbs-gx06-local-audit/v1', source_sha=head,
             source_tree=tree, base_sha=GX05_HEAD, branch=branch,
             github_run_id=run, github_run_attempt=attempt,
             inspected_prs=[p['pr'] for p in stack],
             prior_hosted_status='HISTORICAL_RUN_IDS_SOURCE_BOUND_LIVE_RECHECK_REQUIRED',
             gx06_hosted_status='CURRENT_WORKFLOW_TESTS_MUST_PASS',
             main_merge_status=('EXACT_MAIN_MERGE_OBSERVED' if stage in ('INT01_MAIN','UI01_MAIN','UI01_HF01_MAIN','UI02_MAIN','UI03_MAIN') else 'NOT_MERGED'),
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

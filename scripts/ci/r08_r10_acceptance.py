#!/usr/bin/env python3
"""Read-only R08/R10 acceptance evidence gate; never infers original case PASS.

Checks real hosted test declarations and exact method mappings. Execution evidence
must later come from the source/run/attempt-bound API36 emulator receipt.
No SDK, Gradle, GitHub mutation, ADB, root, network, or device writes.
"""
from __future__ import annotations
import json
from pathlib import Path
import re

REPO = 'Mikeyphw/xbsctl'
BASE_HEAD = '5287f4a41eedc2b7fa3a33c6af820f117db78b84'
BASE_BRANCH = 'feature/xbs-gx06-stack-integration-20261118'
MAP = 'docs/engineering/XBS_R08_R10_EVIDENCE_MAP.json'
JAVA = 'android-app/app/src/androidTest/java/io/github/mikeceead/xbscompanion/Gx04TilesInstrumentedTest.java'
HOST = 'scripts/ci/gx03_emulator_host.py'
WORKFLOW = '.github/workflows/devtool-xbs-ci.yml'
GRADLE = 'android-app/build.gradle'
POLICY = 'config/delivery-required-ci.json'
METHODS = (
    'r08MatrixForbidsAnyActionDuringPendingFailureOrRecovery',
    'r08EventTilesRequireConfiguredAuthenticatedUnpausedState',
    'r08RecoveryCannotBeAcknowledgedWhileCommandPending',
    'r08RestoreDefaultsAlwaysRequiresSeparateAppConfirmation',
    'r10SettingsFiveTileFallbackSurvivesActivityRecreation',
    'r10SystemUiCallbackCountersCannotAuthenticateRootlessTiles',
)
HISTORICAL_TILE_METHODS = (
    'allFiveTilesHaveSystemOnlyManifestPermission',
    'allTileServicesAreBoundToQuickSettingsAction',
    'unsafeAndUnconfiguredTilesNeverClaimActive',
    'noConfiguredRestoreRootActionAndAllEventActionsAllowlisted',
    'hostileEventNamesRejectedBeforeLaunch',
    'verifiedCompletionRequiresTerminalHistoryAndNewerEvidence',
    'unverifiedTilesCannotLaunchRootCommands',
    'runtimeSettingsActivityRendersAllFiveTileRequests',
    'durablePendingBlocksRepeatedTapAcrossReload',
)
ORIGINAL_TASKS = tuple(f'R08-{i:02d}' for i in range(1,15))
ORIGINAL_CASES = ('EM-019', 'EM-020', 'EM-021')

class Refused(ValueError):
    pass

def need(ok: bool, problem: str) -> None:
    if not ok:
        raise Refused('R08_R10_' + problem)

def read_regular(root: Path, relative: str, max_bytes: int = 500_000) -> bytes:
    target = root / relative
    need(target.is_file() and not target.is_symlink() and 0 < target.stat().st_size <= max_bytes,
         'FILE_UNSAFE_OR_MISSING ' + relative)
    return target.read_bytes()

def validate_mapping(data: dict) -> None:
    need(type(data) is dict and data.get('schema') == 'xbs-r08-r10-acceptance/v1',
         'MAP_SCHEMA_DRIFT')
    need(data.get('base_source_sha') == BASE_HEAD and data.get('repository') == REPO,
         'MAP_SOURCE_DRIFT')
    need(data.get('original_tasks_preserved') == 178 and
         data.get('original_cases_preserved') == 131 and
         data.get('original_findings_preserved') == 26, 'MAP_ORIGINAL_COUNTS_DRIFT')
    need(data.get('original_cases_accepted') == [] and data.get('physical_root_qualified') is False and
         data.get('signed_release_qualified') is False and
         data.get('aggregate_ci_qualified') is False, 'MAP_FALSE_ACCEPTANCE')
    need(data.get('r08_original_tasks') == list(ORIGINAL_TASKS) and
         data.get('emulator_candidate_cases') == list(ORIGINAL_CASES), 'MAP_ORIGINAL_IDS_DRIFT')
    methods = data.get('required_api36_methods')
    need(type(methods) is list and methods == list(METHODS) and len(set(methods)) == len(METHODS),
         'MAP_REQUIRED_METHODS_MISSING')
    claimed = data.get('candidate_assertions')
    need(type(claimed) is dict and set(claimed) == set(ORIGINAL_CASES), 'MAP_CANDIDATE_SET_DRIFT')
    for key, value in claimed.items():
        need(type(value) is list and value and all(method in METHODS for method in value),
             'MAP_UNKNOWN_ASSERTION ' + key)
    need(data.get('qualifications') == {
         'rootless_hosted': 'PENDING_NEW_RUN',
         'physical_oem': 'NOT_RUN',
         'root_effects': 'NOT_RUN',
         'original_case_assertion_mapping': 'CANDIDATES_ONLY_NOT_ACCEPTED'},
         'MAP_QUALIFICATION_OVERCLAIM')

def validate_complete_gx04_inventory(java: str, host: str) -> int:
    """Fail closed on any missing, duplicated or extra real hosted GX04 method.

    The nine existing GX04 assertions and six R08/R10 additions are required.
    We match the *specific* JUnit receipt declaration rather than arbitrary
    method-name substrings in unrelated comments or source files.
    """
    need(type(java) is str and type(host) is str and len(java) < 150_000 and
         len(host) < 150_000, 'INSTRUMENTATION_SOURCE_UNBOUNDED')
    expected = HISTORICAL_TILE_METHODS + METHODS
    need(len(expected) == 15 and len(set(expected)) == 15,
         'EXPECTED_TILE_METHOD_INVENTORY_INVALID')
    actual = re.findall(r'@Test\s+public\s+void\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(\s*\)', java)
    need(len(actual) == len(expected) and len(set(actual)) == len(actual) and
         set(actual) == set(expected), 'REAL_TILE_METHOD_INVENTORY_DRIFT')
    need("gradle_test('io.github.mikeceead.xbscompanion.Gx04TilesInstrumentedTest')" in host,
         'REAL_TILE_CLASS_NOT_EXECUTED')
    receipt = re.search(
        r"report\['gx04_tile_assertions'\]\s*=\s*junit_receipt\(RESULTS,\s*"
        r"\{\s*'Gx04TilesInstrumentedTest'\s*:\s*\{(?P<methods>.*?)\}\s*\}\s*\)",
        host, flags=re.DOTALL,
    )
    need(receipt is not None, 'REAL_TILE_JUNIT_RECEIPT_NOT_REQUIRED')
    required = re.findall(r"'([A-Za-z_][A-Za-z0-9_]*)'", receipt.group('methods'))
    need(len(required) == len(expected) and len(set(required)) == len(required)
         and set(required) == set(expected), 'HOSTED_JUNIT_TILE_METHOD_INVENTORY_DRIFT')
    return len(expected)

def validate_native_test_source(java: str, host: str, workflow: str) -> None:
    # The host must execute the real class with Gradle on GitHub, not merely
    # grep source or fabricate a JUnit test count. All methods are mandatory.
    need("gradle_test('io.github.mikeceead.xbscompanion.Gx04TilesInstrumentedTest')" in host,
         'REAL_HOSTED_TILE_INSTRUMENTATION_NOT_REQUIRED')
    need("report['gx04_tile_assertions']=junit_receipt(RESULTS" in host,
         'HOSTED_JUNIT_RECEIPT_NOT_REQUIRED')
    validate_complete_gx04_inventory(java,host)
    for method in METHODS:
        need(len(re.findall(r'@Test\s+public\s+void\s+' + re.escape(method) + r'\s*\(', java)) == 1,
             'MISSING_OR_DUPLICATE_REAL_JAVA_TEST ' + method)
        need(host.count("'" + method + "'") == 1,
             'HOSTED_JUNIT_METHOD_NOT_REQUIRED ' + method)
    need('python3 -B scripts/ci/test_r08_r10_acceptance.py' in workflow,
         'HOSTED_SOURCE_NEGATIVE_TESTS_NOT_RUN')
    need('continue-on-error' not in workflow and '          fetch-depth: 24' in workflow,
         'HOSTED_WORKFLOW_WEAKENED')

def validate_gradle_merge_compatibility(text: str) -> None:
    # Main S7 P05's exact one-line lookup is retained, so a subsequent
    # merge-tree can combine the independent early guard with main's change.
    canonical = "def devtool = rootProject.file('../.devtool.toml').text"
    guard = "if (!devtoolManifest.isFile()) throw new GradleException('Standalone XBS Devtool manifest missing at repository root: ' + devtoolManifest)"
    need(text.count(canonical) == 1 and text.count(guard) == 1 and
         text.count("def devtoolManifest = rootProject.file('../.devtool.toml')") == 1 and
         text.index('def devtoolManifest =') < text.index('def rootBuild =') and
         text.index('def rootBuild =') < text.index(canonical),
         'S7_P05_GX06_GRADLE_CONFLICT_NOT_RESOLVED')
    need('../../../.devtool.toml' not in text and 'verifyStableToolchainBaseline' in text,
         'GRADLE_STANDALONE_PATH_REGRESSION')

def audit_source(root: Path) -> dict:
    root = Path(root).resolve(strict=True)
    mapping = json.loads(read_regular(root, MAP))
    validate_mapping(mapping)
    validate_native_test_source(read_regular(root, JAVA).decode('utf8'),
                                read_regular(root, HOST).decode('utf8'),
                                read_regular(root, WORKFLOW).decode('utf8'))
    validate_gradle_merge_compatibility(read_regular(root, GRADLE).decode('utf8'))
    policy = json.loads(read_regular(root, POLICY))
    need(policy.get('repository') == REPO and policy.get('base_branch') == BASE_BRANCH and
         policy.get('event') == 'pull_request' and
         policy.get('release_qualification') == 'separate', 'REQUIRED_CI_POLICY_DRIFT')
    need({(x.get('name'),x.get('workflow_id')) for x in policy.get('required_workflows', [])} == {
         ('Devtool XBS Go and Android candidates', 386774409),
         ('XBS GX03 hosted API26 / API34 compatibility', 419253895)},
         'REQUIRED_CI_WORKFLOWS_DRIFT')
    return {'source_base': BASE_HEAD, 'required_instrumentation': len(METHODS),
            'total_tile_instrumentation': len(HISTORICAL_TILE_METHODS)+len(METHODS),
            'original_tasks_preserved': 178, 'original_cases_preserved': 131,
            'original_cases_accepted': 0, 'physical_root_qualified': False,
            'release_qualified': False, 'aggregate_ci_qualified': False}

def main() -> int:
    import argparse
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--repo',default='.')
    args=p.parse_args()
    try:
        print('R08_R10_SOURCE_EVIDENCE_GATE_PASS '+json.dumps(audit_source(Path(args.repo)),sort_keys=True))
        return 0
    except (Refused, ValueError, OSError, KeyError, UnicodeError) as e:
        print('R08_R10_SOURCE_EVIDENCE_REFUSED '+str(e),file=__import__('sys').stderr)
        return 2

if __name__=='__main__':raise SystemExit(main())

#!/usr/bin/env python3
"""Offline physical-readiness checks. Never executes adb, su, install or effects.

Artifact archives are matched to independently obtained historical provider metadata.
A device inventory is an operator observation, not independently authenticated proof.
Plans are review inputs, never consent to execute. No original case is accepted here.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import stat
import sys
import zipfile

BASELINE = 'docs/engineering/XBS_R11A_HOSTED_BASELINE.json'
BASELINE_SHA = '7a19f75dd166bf95a91a55f3e6b36d93f8c45a301cc193786f4fcfba3e7377ac'
SOURCE = '931548d8cf74c4621e37f8f331462a0ddde5251b'
TREE = '6bcc90b26646aced8bf2ccba04d9db0752df3f2a'
MERGE = '74dfdfcc060d4677261cbc572057b173b28cdc08'
REPO = 'Mikeyphw/xbsctl'
PKG = 'io.github.mikeceead.xbscompanion'
ROLES = {'module', 'api36', 'debug', 'api26', 'api34', 's7', 'jvm'}
MAX_ARCHIVE = 64 * 1024 * 1024
MAX_UNPACKED = 128 * 1024 * 1024
HEX = re.compile(r'[0-9a-f]{64}\Z')
PACKAGE = re.compile(r'[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+\Z')
VERSION = re.compile(r'[A-Za-z0-9._+\-]{1,80}\Z')


class Refused(ValueError):
    pass


def need(ok, reason):
    if not ok:
        raise Refused(reason)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def hash_value(value):
    return isinstance(value, str) and HEX.fullmatch(value) is not None


def unique_pairs(items):
    result = {}
    for key, value in items:
        need(key not in result, 'DUPLICATE_JSON_KEY')
        result[key] = value
    return result


def decode_json(raw):
    try:
        return json.loads(raw, object_pairs_hook=unique_pairs)
    except (UnicodeError, json.JSONDecodeError, RecursionError) as exc:
        raise Refused('INVALID_JSON') from exc


def regular_bytes(path, bound):
    """Open regular files only, refuse symlink components and bound the read.

    Open relative to directory descriptors so a replaced parent cannot redirect
    the read; never print device paths, names or file contents on refusal.
    """
    path = Path(os.path.abspath(path))
    # Android app UIDs can search / and /data but cannot list them. Use
    # search-only descriptors when provided by the OS; O_DIRECTORY together
    # with O_NOFOLLOW also rejects a symlink when Linux O_PATH is used.
    directory_flags = (getattr(os, "O_PATH", getattr(os, "O_SEARCH", os.O_RDONLY))
                       | os.O_DIRECTORY | os.O_NOFOLLOW)
    fd = os.open(path.anchor, directory_flags)
    try:
        for part in path.parts[1:-1]:
            nxt = os.open(part, directory_flags, dir_fd=fd)
            os.close(fd)
            fd = nxt
        with os.fdopen(os.open(path.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=fd), 'rb') as handle:
            before = os.fstat(handle.fileno())
            need(stat.S_ISREG(before.st_mode) and 0 < before.st_size <= bound, 'FILE_NOT_BOUNDED_REGULAR')
            data = handle.read(bound + 1)
            after = os.fstat(handle.fileno())
            need(len(data) == before.st_size <= bound and
                 (before.st_size, before.st_mtime_ns, before.st_ctime_ns) ==
                 (after.st_size, after.st_mtime_ns, after.st_ctime_ns), 'FILE_CHANGED_DURING_READ')
            return data
    finally:
        os.close(fd)


def audit_baseline(root):
    raw = regular_bytes(Path(root) / BASELINE, 512_000)
    need(digest(raw) == BASELINE_SHA, 'HISTORICAL_METADATA_DRIFT')
    baseline = decode_json(raw)
    need(baseline['schema'] == 'xbs-r11a-historical-baseline/v1' and baseline['repository'] == REPO and
         baseline['source_sha'] == SOURCE and baseline['source_tree'] == TREE and baseline['merged_sha'] == MERGE,
         'HISTORICAL_IDENTITY_DRIFT')
    need(baseline['physical_qualified'] is False and baseline['release_qualified'] is False and
         baseline['original_cases_accepted'] == [], 'FALSE_ACCEPTANCE')
    rows = baseline['artifact_metadata']
    need(len(rows) == len(ROLES) and {x['role'] for x in rows} == ROLES and
         len({x['id'] for x in rows}) == len(ROLES), 'ARTIFACT_ROSTER_DRIFT')
    matrix = decode_json(regular_bytes(Path(root) / 'docs/engineering/XBS_TEST_MATRIX.json', 4_000_000))['cases']
    ledger = decode_json(regular_bytes(Path(root) / 'docs/engineering/XBS_IMPLEMENTATION_LEDGER.json', 5_000_000))
    need(len(matrix) == len({c['id'] for c in matrix}) == 131 and
         sum(len(p['tasks']) for p in ledger['phases']) == 178 and len(ledger['findings']) == 26,
         'ORIGINAL_INVENTORY_DRIFT')
    source_cases = {c['id']: c for c in matrix if any(p in ('R08', 'R10', 'R11') for p in c['phases'])}
    seen = baseline['case_map']
    need(len(seen) == len(source_cases) and {c['id'] for c in seen} == set(source_cases), 'ORIGINAL_CASE_MAP_DRIFT')
    for c in seen:
        need(c['accepted'] is False and all(c[k] == source_cases[c['id']][k] for k in ('title', 'required_assertion', 'phases', 'lane')),
             'ORIGINAL_CASE_ASSERTION_DRIFT')
    return baseline


def zip_members(raw):
    """Bound every member, refuse ambiguous paths and never extract an archive."""
    need(0 < len(raw) <= MAX_ARCHIVE, 'ZIP_BYTES_OUT_OF_BOUNDS')
    try:
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            infos = archive.infolist()
            need(0 < len(infos) <= 2048, 'ZIP_MEMBER_COUNT')
            names = set()
            total = 0
            files = {}
            for info in infos:
                name = info.filename
                parts = name.rstrip('/').split('/')
                need(name == info.orig_filename and len(name) <= 240 and
                     all(p not in ('', '.', '..') for p in parts) and
                     all(ord(c) >= 32 and ord(c) != 127 for c in name) and
                     not any(c in name for c in ('\\', ':')) and not name.startswith('/'), 'UNSAFE_ZIP_PATH')
                folded = name.rstrip('/').casefold()
                need(folded not in names, 'AMBIGUOUS_ZIP_PATH')
                names.add(folded)
                kind = stat.S_IFMT(info.external_attr >> 16)
                need(kind in (0, stat.S_IFREG, stat.S_IFDIR) and
                     (kind != stat.S_IFDIR or info.is_dir()) and
                     not (kind == stat.S_IFREG and info.is_dir()) and
                     not info.flag_bits & 1, 'UNSAFE_ZIP_MEMBER')
                need(info.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED), 'UNSUPPORTED_ZIP_COMPRESSION')
                total += info.file_size
                need(total <= MAX_UNPACKED and info.file_size <= MAX_ARCHIVE, 'ZIP_EXPANSION_LIMIT')
                if info.is_dir():
                    need(info.file_size == 0, 'DIRECTORY_WITH_CONTENT')
                    continue
                with archive.open(info) as stream:
                    value = stream.read(info.file_size + 1)
                    need(len(value) == info.file_size, 'ZIP_SIZE_DRIFT')
                files[name] = value
            paths = {name.casefold() for name in files}
            for name in paths:
                need(not any('/'.join(name.split('/')[:i]) in paths for i in range(1, len(name.split('/')))), 'ZIP_FILE_DIRECTORY_ALIAS')
            return files
    except (zipfile.BadZipFile, RuntimeError, NotImplementedError, EOFError, OSError) as exc:
        raise Refused('INVALID_ZIP') from exc


def checked_archive(path, record):
    raw = regular_bytes(path, MAX_ARCHIVE)
    need(type(record['size_bytes']) is int and len(raw) == record['size_bytes'] and
         hash_value(record['sha256']) and digest(raw) == record['sha256'], 'HOSTED_ARTIFACT_BYTES_DRIFT')
    return zip_members(raw)


def checksummed_member(files, name):
    need(name in files and 'SHA256SUMS' in files, 'PAYLOAD_OR_CHECKSUM_MISSING')
    expected = re.fullmatch(rb'([0-9a-f]{64}) [ *](?:dist/)?' + re.escape(name.encode()) + rb'\n?', files['SHA256SUMS'])
    need(expected is not None and digest(files[name]) == expected.group(1).decode(), 'PAYLOAD_CHECKSUM_DRIFT')
    return files[name]


def module_properties(raw):
    try:
        pairs = [line.split('=', 1) for line in raw.decode('utf-8').splitlines() if line]
        props = unique_pairs(pairs)
    except (ValueError, UnicodeError) as exc:
        raise Refused('MODULE_PROP_INVALID') from exc
    need(props.get('id') == 'XtremeBS' and isinstance(props.get('version'), str) and VERSION.fullmatch(props['version']), 'MODULE_IDENTITY_DRIFT')
    need(re.fullmatch('[1-9][0-9]{0,8}', props.get('versionCode', '')) is not None, 'MODULE_VERSION_CODE_DRIFT')
    return props


def candidate_from_archives(baseline, module, api36, debug, *, verify_tools=None):
    """Verify three exact historical ZIP byte identities and their payloads.

    Debug APKs are distinct lanes; no claim that the differently built hosted
    test APK and the later non-emulator debug APK share the same bytes.
    """
    records = {x['role']: x for x in baseline['artifact_metadata']}
    m = checked_archive(module, records['module'])
    a = checked_archive(api36, records['api36'])
    b = checked_archive(debug, records['debug'])
    module_raw = checksummed_member(m, 'XtremeBS-go.zip')
    nested = zip_members(module_raw)
    need('module.prop' in nested and 'runtime/arm64-v8a/runtime-manifest.json' in nested, 'MODULE_PROVENANCE_MISSING')
    props = module_properties(nested['module.prop'])
    runtime_names = ('xbs', 'XBSctl', 'XtremeBSd')
    manifest = decode_json(nested['runtime/arm64-v8a/runtime-manifest.json'])
    need(manifest['schema_version'] == 2 and manifest['module']['id'] == 'XtremeBS' and
         set(manifest['runtime']) == set(runtime_names), 'RUNTIME_MANIFEST_IDENTITY_DRIFT')
    runtime_hashes = {}
    for name in runtime_names:
        path = 'runtime/arm64-v8a/' + name
        info = manifest['runtime'][name]
        need(path in nested and info['path'] == path and info['kind'] == 'go-native' and info['goos'] == 'android' and
             info['goarch'] == 'arm64' and info['sha256'] == digest(nested[path]), 'RUNTIME_BINARY_DRIFT')
        runtime_hashes[name] = info['sha256']
    need('evidence.json' in a, 'HOSTED_RECEIPT_MISSING')
    rec = decode_json(a['evidence.json'])
    need(rec['schema'] == 'xbs-gx03-android-evidence/v1' and rec['source_sha'] == baseline['source_sha'] and
         rec['github_run_id'] == str(records['api36']['run_id']) and rec['github_run_attempt'] == str(records['api36']['attempt']) and
         rec['api_level'] == 36 and rec['emulator_only'] is True and rec['physical_root_qualified'] is False and
         rec['original_cases_accepted'] == [], 'HOSTED_RECEIPT_IDENTITY_DRIFT')
    pair = rec['apk_pair']
    for kind in ('app', 'test'):
        name = kind + '-debug.apk'
        expected = PKG + ('.test' if kind == 'test' else '')
        need(name in a and pair[kind]['package'] == expected and hash_value(pair[kind]['cert_sha256']) and
             pair[kind]['size_bytes'] == len(a[name]) and pair[kind]['sha256'] == digest(a[name]), 'HOSTED_APK_PAIR_DRIFT')
    need(pair['app']['cert_sha256'] == pair['test']['cert_sha256'] and pair['app']['sha256'] != pair['test']['sha256'], 'HOSTED_APK_PAIR_SIGNER_DRIFT')
    debug_raw = checksummed_member(b, 'xbs-companion-r05c-api36-debug.apk')
    need(b.get('SOURCE_SHA', b'').decode('ascii').strip() == baseline['source_sha'], 'DEBUG_SOURCE_DRIFT')
    debug_signer = None
    if verify_tools is not None:
        debug_signer = verify_tools(module_raw, a['app-debug.apk'], a['test-debug.apk'], debug_raw, pair['app']['cert_sha256'])
        need(hash_value(debug_signer), 'DEBUG_SIGNER_VERIFICATION_MISSING')
    return {'schema': 'xbs-r11a-candidate/v1', 'source_sha': baseline['source_sha'], 'source_tree': baseline['source_tree'],
            'run_id': records['module']['run_id'], 'attempt': records['module']['attempt'],
            'module_sha256': digest(module_raw), 'module_prop_sha256': digest(nested['module.prop']),
            'runtime_manifest_sha256': digest(nested['runtime/arm64-v8a/runtime-manifest.json']), 'runtime_sha256': runtime_hashes,
            'module_id': props['id'], 'module_version': props['version'], 'module_version_code': int(props['versionCode']),
            'hosted_app_sha256': digest(a['app-debug.apk']), 'hosted_test_sha256': digest(a['test-debug.apk']),
            'hosted_signer_sha256': pair['app']['cert_sha256'], 'debug_apk_sha256': digest(debug_raw),
            'debug_signer_sha256': debug_signer, 'local_native_and_apk_tools_verified': debug_signer is not None,
            'scope': 'LIVE_PROVIDER_BYTES_ONLY' if baseline['schema'] == 'xbs-r11a-live-provider/v1' else 'HISTORICAL_BYTES_ONLY', 'physical_qualified': False, 'release_qualified': False,
            'requires_native_module_validation_before_install': debug_signer is None, 'requires_independent_device_signer_verification': True}



def verify_candidate_tools(root, module, app, test, debug, hosted_signer):
    """Run only local artifact validators on private copies of already hashed bytes."""
    import subprocess
    import tempfile
    from gx06_stack_gate import git, reviewed_stage
    root = Path(root).resolve(strict=True)
    need(not git(root, 'status', '--porcelain', '--untracked-files=all') and reviewed_stage(root) in ('R11A', 'R11A_MAIN'), 'UNREVIEWED_VALIDATOR_SOURCE')
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    need(bool(sdk), 'ANDROID_ARTIFACT_TOOLS_UNAVAILABLE')
    tools = Path(sdk) / 'build-tools' / '36.0.0'
    signer, aapt = tools / 'apksigner', tools / 'aapt'
    need(signer.is_file() and aapt.is_file(), 'ANDROID_ARTIFACT_TOOLS_UNAVAILABLE')

    def run(argv):
        try:
            result = subprocess.run(argv, cwd=root, capture_output=True, text=True, timeout=180,
                                    env={**os.environ, 'GOPROXY': 'off', 'GOSUMDB': 'off', 'GOTOOLCHAIN': 'local',
                                         'GOFLAGS': '', 'GOWORK': 'off'})
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise Refused('LOCAL_ARTIFACT_VALIDATOR_UNAVAILABLE') from exc
        need(result.returncode == 0 and len(result.stdout) <= 2_000_000, 'LOCAL_ARTIFACT_VALIDATOR_REFUSED')
        return result.stdout

    with tempfile.TemporaryDirectory(prefix='xbs-r11a-verify-') as directory:
        work = Path(directory)
        module_file = work / 'XtremeBS-go.zip'
        module_file.write_bytes(module)
        run(['go', 'run', './cmd/xbsrepo', 'verify-module-zip', '--zip', str(module_file), '--sha256', digest(module)])
        results = {}
        for name, raw, package in (('app', app, PKG), ('test', test, PKG + '.test'), ('debug', debug, PKG)):
            apk = work / (name + '.apk')
            apk.write_bytes(raw)
            signatures = re.findall(r'Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]{64})',
                                    run([str(signer), 'verify', '--print-certs', str(apk)]))
            badging = re.search(r"(?m)^package: name='([^']+)'", run([str(aapt), 'dump', 'badging', str(apk)]))
            need(len(signatures) == 1 and badging is not None and badging.group(1) == package, 'APK_PACKAGE_OR_SIGNER_DRIFT')
            need(digest(regular_bytes(apk, MAX_ARCHIVE)) == digest(raw), 'LOCAL_VALIDATOR_CHANGED_APK')
            results[name] = signatures[0].lower()
        need(results['app'] == results['test'] == hosted_signer, 'HOSTED_SIGNER_RECHECK_DRIFT')
        need(digest(regular_bytes(module_file, MAX_ARCHIVE)) == digest(module), 'LOCAL_VALIDATOR_CHANGED_MODULE')
        return results['debug']

INVENTORY_KEYS = {'schema', 'source_sha', 'source_tree', 'api', 'abi', 'is_emulator', 'device_is_disposable', 'read_only_consent',
                  'manager', 'manager_package', 'manager_version', 'manager_signer_sha256', 'boot_nonce_sha256',
                  'module_present', 'companion_present', 'module_id', 'module_version', 'module_version_code', 'module_prop_sha256', 'runtime_manifest_sha256', 'runtime_sha256',
                  'installed_companion_sha256', 'installed_companion_signer_sha256', 'state_snapshot_sha256'}


def validate_inventory(info, candidate):
    need(type(info) is dict and set(info) == INVENTORY_KEYS and info['schema'] == 'xbs-r11a-inventory/v1', 'INVENTORY_SCHEMA_DRIFT')
    need(info['source_sha'] == candidate['source_sha'] and info['source_tree'] == candidate['source_tree'], 'INVENTORY_SOURCE_DRIFT')
    need(type(info['api']) is int and info['api'] == 36 and info['abi'] == 'arm64-v8a' and info['is_emulator'] is False,
         'PHYSICAL_API36_ARM64_REQUIRED')
    need(info['device_is_disposable'] is True and info['read_only_consent'] is True, 'DISPOSABLE_READ_ONLY_CONSENT_REQUIRED')
    need(info['manager'] in ('kernelsu', 'magisk') and isinstance(info['manager_package'], str) and PACKAGE.fullmatch(info['manager_package']) and
         isinstance(info['manager_version'], str) and VERSION.fullmatch(info['manager_version']), 'MANAGER_IDENTITY_INVALID')
    need(candidate.get('local_native_and_apk_tools_verified') is True and hash_value(candidate.get('debug_signer_sha256')),
         'INDEPENDENT_NATIVE_AND_APK_VERIFICATION_REQUIRED')
    for key in ('manager_signer_sha256', 'boot_nonce_sha256', 'state_snapshot_sha256'):
        need(hash_value(info[key]), 'INVENTORY_DIGEST_INVALID')
    need(type(info['module_present']) is bool and type(info['companion_present']) is bool, 'PRESENCE_FLAGS_INVALID')
    keys = ('module_id', 'module_version', 'module_version_code', 'module_prop_sha256', 'runtime_manifest_sha256', 'runtime_sha256')
    if info['module_present']:
        need(info['module_id'] == 'XtremeBS' and isinstance(info['module_version'], str) and VERSION.fullmatch(info['module_version']) and
             type(info['module_version_code']) is int and info['module_version_code'] > 0 and
             all(hash_value(info[k]) for k in ('module_prop_sha256', 'runtime_manifest_sha256')) and
             type(info['runtime_sha256']) is dict and set(info['runtime_sha256']) == {'xbs', 'XBSctl', 'XtremeBSd'} and
             all(hash_value(v) for v in info['runtime_sha256'].values()), 'OBSERVED_MODULE_IDENTITY_INVALID')
    else:
        need(all(info[key] is None for key in keys), 'ABSENT_MODULE_HAS_IDENTITY')
    module_matches = info['module_present'] and all(info[k] == candidate[k] for k in keys)
    appkeys = ('installed_companion_sha256', 'installed_companion_signer_sha256')
    if info['companion_present']:
        need(all(hash_value(info[key]) for key in appkeys), 'OBSERVED_COMPANION_IDENTITY_INVALID')
    else:
        need(all(info[key] is None for key in appkeys), 'ABSENT_COMPANION_HAS_IDENTITY')
    # The normal debug APK is the physical candidate; the emulator instrumentation
    # build is an independent artifact and never substituted for it.
    app_matches = info['companion_present'] and info['installed_companion_sha256'] == candidate['debug_apk_sha256'] and info['installed_companion_signer_sha256'] == candidate['debug_signer_sha256']
    return {'inventory_sha256': digest(json.dumps(info, sort_keys=True, separators=(',', ':')).encode()),
            'candidate_source': candidate['source_sha'], 'candidate_tree': candidate['source_tree'],
            'module_sha256': candidate['module_sha256'], 'observations_consistent': True,
            'module_matches_candidate': module_matches, 'companion_matches_candidate': app_matches,
            'device_access_performed': False, 'consent_authenticated': False,
            'physical_qualified': False, 'release_qualified': False}


# Review roster only. Every effect stays separately consented and externally executed.
# RD-012 explicitly never enters the ordinary reversible plan; PF-008 is a separate study.
CASES = {
    'RD-001': (60, 'read-only inventory', 'no writes; compare inventory to approved candidate'),
    'RD-002': (600, 'install or update exact candidate', 'restore previous verified module and config; require independent recovery'),
    'RD-003': (180, 'observe daemon boot', 'verify journal and prior module state; no automatic reboot'),
    'RD-004': (120, 'observe charging transitions', 'restore prior charging and profile state'),
    'RD-005': (120, 'observe screen transitions', 'restore previous screen/profile state'),
    'RD-006': (120, 'CLI and app state observation', 'restore only owned command effects'),
    'RD-007': (120, 'Tasker synchronization observation', 'restore owned profile and disable test trigger'),
    'RD-008': (120, 'QS and lockscreen interaction', 'restore previous tile layout and lockscreen settings'),
    'RD-009': (120, 'owned daemon recovery', 'restart verified daemon and reconcile journal'),
    'RD-010': (60, 'owned disposable child process cancellation', 'terminate fixture tree only; verify unrelated processes'),
    'RD-011': (120, 'bounded reversible effect', 'compare per-effect pre/post/restored state hashes'),
    'RD-013': (600, 'explicit uninstall or upgrade', 'restore verified previous module, settings and config'),
}


def plan_case(case, inventory):
    need(case in CASES, 'CASE_NOT_IN_ORDINARY_REVERSIBLE_PLAN')
    need(case in ('RD-001', 'RD-002') or (inventory.get('module_matches_candidate') is True and
         inventory.get('companion_matches_candidate') is True), 'EXACT_INSTALLED_CANDIDATE_REQUIRED')
    timeout, effect, recovery = CASES[case]
    return {'schema': 'xbs-r11a-case-plan/v1', 'case': case, 'max_seconds': timeout, 'effect': effect, 'recovery': recovery,
            'inventory_sha256': inventory['inventory_sha256'], 'source_sha': inventory['candidate_source'],
            'source_tree': inventory['candidate_tree'], 'module_sha256': inventory['module_sha256'],
            'required_before': ['fresh inventory', 'case-specific explicit approval', 'before-state hash', 'independent recovery access'],
            'required_after': ['UTC start/end', 'exit and observed assertions', 'after-state hash', 'restored-state hash', 'cleanup and independent review'],
            'effect_authorized': False, 'effect_executed': False, 'physical_qualified': False}


# Independently inspect current provider metadata for the reviewed R11A PR head.
WORKFLOWS = {
    386774409: ('go-contract', 'android-companion-jvm', 'android-companion-api36'),
    419253895: ('android-api26', 'android-api34'),
    418896455: ('Canonical Devtool Android audit and exact-tree scaffold (non-release)',
                'XBS Android API36 debug APK and unit tests (non-release)'),
}
ARTIFACTS = {
    386774409: {'module': 'xbs-android-module', 'api36': 'xbs-gx03-api36',
                'debug': 'xbs-companion-r05c-api36', 'jvm': 'xbs-android-jvm-counts'},
    419253895: {'api26': 'xbs-gx03-api26', 'api34': 'xbs-gx03-api34'},
    418896455: {'s7': 'xbs-s7-p05-debug'},
}


def provider_get(path):
    import subprocess
    need(path.startswith('/repos/' + REPO + '/') and '\n' not in path, 'PROVIDER_ENDPOINT_REFUSED')
    try:
        completed = subprocess.run(['gh', 'api', '--hostname', 'github.com', '--method', 'GET', path],
                                   capture_output=True, timeout=45, check=False,
                                   env={**os.environ, 'GH_DEBUG': '', 'GH_PROMPT_DISABLED': '1'})
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise Refused('PROVIDER_READ_UNAVAILABLE') from exc
    need(completed.returncode == 0 and 0 < len(completed.stdout) <= 8_000_000, 'PROVIDER_READ_UNAVAILABLE')
    return decode_json(completed.stdout)


def verified_local_source(root, source):
    from gx06_stack_gate import reviewed_stage, git
    need(isinstance(source, str) and re.fullmatch('[0-9a-f]{40}', source), 'CANDIDATE_SOURCE_INVALID')
    need(not git(root, 'status', '--porcelain', '--untracked-files=all'), 'DIRTY_SOURCE')
    need(reviewed_stage(root, head=source) == 'R11A' and reviewed_stage(root) in ('R11A', 'R11A_MAIN'), 'UNREVIEWED_R11A_SOURCE')
    tree = git(root, 'rev-parse', source + '^{tree}')
    need(tree == git(root, 'rev-parse', 'HEAD^{tree}'), 'CANDIDATE_CHECKOUT_TREE_DRIFT')
    return tree


def live_metadata(root, source, run_ids, *, get=provider_get):
    """Three actual successful runs, exact tree/head, attempts, jobs and artifacts.

    The provider GET is the authority. An offline user-supplied JSON manifest
    cannot promote a fresh candidate. Do not reuse historical metadata here.
    """
    tree = verified_local_source(root, source)
    need(len(run_ids) == len(set(run_ids)) == 3 and all(type(x) is int and x > 0 for x in run_ids), 'THREE_DISTINCT_RUN_IDS_REQUIRED')
    prefix = '/repos/' + REPO
    commit = get(prefix + '/git/commits/' + source)
    need(commit['sha'] == source and commit['tree']['sha'] == tree, 'PROVIDER_SOURCE_TREE_DRIFT')
    workflows = set()
    records = []
    for run_id in run_ids:
        run = get(prefix + '/actions/runs/' + str(run_id))
        wf = run['workflow_id']
        attempt = run['run_attempt']
        need(run['id'] == run_id and run['head_sha'] == source and run['event'] == 'pull_request' and
             run['status'] == 'completed' and run['conclusion'] == 'success' and
             run['repository']['id'] == 1422199806 and run['head_repository']['id'] == 1422199806 and
             run['head_branch'] == 'feature/xbs-r11a-physical-readiness-20261127' and
             wf in WORKFLOWS and wf not in workflows and type(attempt) is int and attempt > 0, 'REQUIRED_RUN_IDENTITY_OR_STATUS_DRIFT')
        workflows.add(wf)
        jobs = get(prefix + f'/actions/runs/{run_id}/attempts/{attempt}/jobs?per_page=100')
        need(jobs['total_count'] == len(jobs['jobs']) <= 100, 'INCOMPLETE_JOB_INVENTORY')
        rows = {j['name']: j for j in jobs['jobs']}
        need(len(rows) == len(jobs['jobs']) and set(rows) == set(WORKFLOWS[wf]) | ({'android-companion-debug'} if wf == 386774409 else set()), 'REQUIRED_JOB_ROSTER_DRIFT')
        for name, row in rows.items():
            need(row['run_id'] == run_id and row['run_attempt'] == attempt and row['head_sha'] == source and row['status'] == 'completed' and
                 row['conclusion'] == ('skipped' if name == 'android-companion-debug' else 'success'), 'REQUIRED_JOB_NOT_SUCCESSFUL')
        inventory = get(prefix + f'/actions/runs/{run_id}/artifacts?per_page=100')
        need(inventory['total_count'] == len(inventory['artifacts']) <= 100, 'INCOMPLETE_ARTIFACT_INVENTORY')
        for role, name in ARTIFACTS[wf].items():
            expected = f'{name}-{run_id}-{attempt}'
            matches = [a for a in inventory['artifacts'] if a['name'] == expected]
            need(len(matches) == 1, 'REQUIRED_ARTIFACT_MISSING_OR_DUPLICATE')
            artifact = matches[0]
            att = artifact['workflow_run']
            checksum = artifact['digest']
            need(artifact['expired'] is False and type(artifact['id']) is int and artifact['id'] > 0 and
                 type(artifact['size_in_bytes']) is int and 0 < artifact['size_in_bytes'] <= MAX_ARCHIVE and
                 isinstance(checksum, str) and checksum.startswith('sha256:') and hash_value(checksum[7:]) and
                 att['id'] == run_id and att['head_sha'] == source and att['repository_id'] == 1422199806 and
                 att['head_repository_id'] == 1422199806 and att['head_branch'] == run['head_branch'], 'ARTIFACT_PROVIDER_IDENTITY_DRIFT')
            records.append({'role': role, 'name': expected, 'id': artifact['id'], 'run_id': run_id, 'attempt': attempt,
                            'workflow_id': wf, 'size_bytes': artifact['size_in_bytes'], 'sha256': checksum[7:]})
    need(workflows == set(WORKFLOWS) and len(records) == len(ROLES) and
         {x['role'] for x in records} == ROLES and len({x['id'] for x in records}) == len(ROLES), 'INCOMPLETE_PROVIDER_EVIDENCE')
    return {'schema': 'xbs-r11a-live-provider/v1', 'source_sha': source, 'source_tree': tree,
            'artifact_metadata': records, 'physical_qualified': False, 'release_qualified': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--candidate-source', help='Explicit reviewed R11A PR head for fresh read-only GitHub verification')
    parser.add_argument('--run', type=int, action='append', default=[], help='Three exact successful R11A PR run IDs; read-only GETs')
    parser.add_argument('--module-artifact', type=Path)
    parser.add_argument('--api36-artifact', type=Path)
    parser.add_argument('--debug-artifact', type=Path)
    parser.add_argument('--verify-local-tools', action='store_true', help='Run local R04 verifier and APK identity/signature checks; no device or Gradle')
    parser.add_argument('--inventory', type=Path)
    parser.add_argument('--plan-case', choices=tuple(CASES))
    args = parser.parse_args()
    try:
        baseline = audit_baseline(args.repo)
        need(bool(args.candidate_source) == bool(args.run), 'FRESH_SOURCE_AND_RUN_IDS_REQUIRED_TOGETHER')
        if args.candidate_source:
            baseline = live_metadata(args.repo, args.candidate_source, args.run)
        supplied = (args.module_artifact, args.api36_artifact, args.debug_artifact)
        result = {'candidate_source': baseline['source_sha'], 'original_case_map_count': 46, 'provider_metadata': baseline['artifact_metadata'], 'candidate_metadata_scope': baseline['schema'],
                  'artifact_bytes_verified': False, 'physical_qualified': False, 'release_qualified': False}
        if any(supplied):
            need(all(supplied), 'THREE_ARTIFACTS_REQUIRED')
            candidate = candidate_from_archives(baseline, *supplied, verify_tools=(lambda *payloads: verify_candidate_tools(args.repo, *payloads)) if args.verify_local_tools else None)
            result.update(artifact_bytes_verified=True, candidate=candidate)
            if args.inventory:
                inventory = validate_inventory(decode_json(regular_bytes(args.inventory, 8192)), candidate)
                result['inventory'] = inventory
                if args.plan_case:
                    need(candidate['scope'] == 'LIVE_PROVIDER_BYTES_ONLY' or args.plan_case == 'RD-001', 'FRESH_PROVIDER_EVIDENCE_REQUIRED_FOR_EFFECT_PLAN')
                    result['plan'] = plan_case(args.plan_case, inventory)
            else:
                need(not args.plan_case, 'INVENTORY_REQUIRED_FOR_PLAN')
        else:
            need(not args.inventory and not args.plan_case and not args.verify_local_tools, 'ARTIFACTS_REQUIRED_FOR_INVENTORY')
        print(json.dumps(result, sort_keys=True))
        return 0
    except (Refused, OSError, KeyError, TypeError, ValueError, UnicodeError, OverflowError) as exc:
        # Fixed reasons only. Do not emit paths, JSON contents or arbitrary exception strings.
        print('R11A_REFUSED ' + (str(exc) if isinstance(exc, Refused) else 'INVALID_OR_UNAVAILABLE_INPUT'), file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())

#!/usr/bin/env python3
"""Offline R11A fixtures. Never touches a live provider, Android device or root."""
import copy
import io
import json
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import r11a_readiness as q

ROOT = Path(__file__).resolve().parents[2]


def archive(items):
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED) as z:
        for key, value in items:
            z.writestr(key, value)
    return output.getvalue()


def sums(name, value):
    return (q.digest(value) + '  ' + name + '\n').encode()


class ReadinessTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.metadata = copy.deepcopy(q.audit_baseline(ROOT))
        runtimes = {name: name.encode() for name in ('xbs', 'XBSctl', 'XtremeBSd')}
        manifest = {'schema_version': 2, 'module': {'id': 'XtremeBS'}, 'runtime': {name: {'path': 'runtime/arm64-v8a/' + name, 'kind': 'go-native', 'goos': 'android', 'goarch': 'arm64', 'sha256': q.digest(raw)} for name,raw in runtimes.items()}}
        module = archive([('module.prop', b'id=XtremeBS\nversion=v2.1.6\nversionCode=216\n'),
                          ('runtime/arm64-v8a/runtime-manifest.json', json.dumps(manifest)), *[('runtime/arm64-v8a/' + name, raw) for name, raw in runtimes.items()]])
        self.app = b'APK FIXTURE APP'
        test = b'APK FIXTURE TEST'
        debug = b'APK FIXTURE DEBUG'
        self.receipt = {'schema': 'xbs-gx03-android-evidence/v1', 'source_sha': q.SOURCE,
                        'github_run_id': '46049889390', 'github_run_attempt': '1', 'api_level': 36,
                        'emulator_only': True, 'physical_root_qualified': False, 'original_cases_accepted': [],
                        'apk_pair': {kind: {'package': q.PKG + ('.test' if kind == 'test' else ''),
                                             'cert_sha256': 'b' * 64, 'sha256': q.digest(raw), 'size_bytes': len(raw)}
                                     for kind, raw in (('app', self.app), ('test', test))}}
        self.parts = {'module': [('XtremeBS-go.zip', module), ('SHA256SUMS', sums('dist/XtremeBS-go.zip', module))],
                      'api36': [('app-debug.apk', self.app), ('test-debug.apk', test), ('evidence.json', json.dumps(self.receipt))],
                      'debug': [('xbs-companion-r05c-api36-debug.apk', debug),
                                ('SHA256SUMS', sums('xbs-companion-r05c-api36-debug.apk', debug)), ('SOURCE_SHA', q.SOURCE + '\n')]}
        self.repack()

    def repack(self):
        self.paths = []
        for role in ('module', 'api36', 'debug'):
            raw = archive(self.parts[role])
            path = self.root / (role + '.zip')
            path.write_bytes(raw)
            rec = next(r for r in self.metadata['artifact_metadata'] if r['role'] == role)
            rec.update(size_bytes=len(raw), sha256=q.digest(raw))
            self.paths.append(path)

    def candidate(self, verified=False):
        return q.candidate_from_archives(self.metadata, *self.paths, verify_tools=(lambda *raw: 'f' * 64) if verified else None)

    def inventory(self, c):
        return {'schema': 'xbs-r11a-inventory/v1', 'source_sha': c['source_sha'], 'source_tree': c['source_tree'],
                'api': 36, 'abi': 'arm64-v8a', 'is_emulator': False, 'device_is_disposable': True, 'read_only_consent': True,
                'manager': 'kernelsu', 'manager_package': 'me.weishu.kernelsu', 'manager_version': 'v1.0.0',
                'manager_signer_sha256': 'a' * 64, 'boot_nonce_sha256': 'c' * 64, 'state_snapshot_sha256': 'd' * 64,
                'module_present': True, 'companion_present': True,
                'installed_companion_sha256': c['debug_apk_sha256'], 'installed_companion_signer_sha256': c['debug_signer_sha256'],
                **{key: c[key] for key in ('module_id', 'module_version', 'module_version_code', 'module_prop_sha256', 'runtime_manifest_sha256', 'runtime_sha256')}}

    def test_historical_roster(self):
        self.assertEqual(len(self.metadata['case_map']), 46)
        self.assertEqual(len([x for x in self.metadata['case_map'] if x['scope'] == 'PHYSICAL']), 14)
        self.assertFalse(any(x['accepted'] for x in self.metadata['case_map']))
        self.assertEqual({x['role'] for x in self.metadata['artifact_metadata']}, q.ROLES)

    def test_exact_outer_and_inner_bytes(self):
        c = self.candidate()
        self.assertEqual(c['hosted_app_sha256'], q.digest(self.app))
        self.assertNotEqual(c['hosted_app_sha256'], c['debug_apk_sha256'])
        self.assertFalse(c['physical_qualified'])
        self.assertFalse(c['release_qualified'])
        self.assertTrue(c['requires_native_module_validation_before_install'])

    def test_outer_digest_mismatch(self):
        self.paths[0].write_bytes(b'wrong')
        with self.assertRaisesRegex(q.Refused, 'HOSTED_ARTIFACT_BYTES_DRIFT'):
            self.candidate()

    def test_inner_module_checksum_mismatch(self):
        self.parts['module'][1] = ('SHA256SUMS', '0' * 64 + '  dist/XtremeBS-go.zip\n')
        self.repack()
        with self.assertRaisesRegex(q.Refused, 'PAYLOAD_CHECKSUM_DRIFT'):
            self.candidate()

    def test_wrong_hosted_identity_or_acceptance(self):
        for key, value in [('source_sha', '0' * 40), ('github_run_id', '3'), ('github_run_attempt', '2'), ('api_level', 34),
                           ('emulator_only', False), ('physical_root_qualified', True), ('original_cases_accepted', ['EM-019'])]:
            with self.subTest(key=key):
                rec = {**self.receipt, key: value}
                self.parts['api36'][-1] = ('evidence.json', json.dumps(rec))
                self.repack()
                with self.assertRaisesRegex(q.Refused, 'HOSTED_RECEIPT_IDENTITY_DRIFT'):
                    self.candidate()

    def test_wrong_apk(self):
        self.parts['api36'][0] = ('app-debug.apk', b'changed')
        self.repack()
        with self.assertRaisesRegex(q.Refused, 'HOSTED_APK_PAIR_DRIFT'):
            self.candidate()

    def test_wrong_signer(self):
        self.receipt['apk_pair']['test']['cert_sha256'] = 'e' * 64
        self.parts['api36'][-1] = ('evidence.json', json.dumps(self.receipt))
        self.repack()
        with self.assertRaisesRegex(q.Refused, 'HOSTED_APK_PAIR_SIGNER_DRIFT'):
            self.candidate()

    def test_wrong_debug_source(self):
        self.parts['debug'][-1] = ('SOURCE_SHA', '0' * 40)
        self.repack()
        with self.assertRaisesRegex(q.Refused, 'DEBUG_SOURCE_DRIFT'):
            self.candidate()

    def test_unsafe_zip_members(self):
        for name in ('../escape', 'dir/../escape', '/escape', 'C:drive', 'dir\\file', 'dir//file', 'dir/./file', 'bad\x01'):
            with self.subTest(name=name), self.assertRaises(q.Refused):
                q.zip_members(archive([(name, b'bad')]))
        with self.assertRaisesRegex(q.Refused, 'AMBIGUOUS_ZIP_PATH'):
            q.zip_members(archive([('Foo', b'a'), ('foo', b'b')]))
        with self.assertRaisesRegex(q.Refused, 'ZIP_FILE_DIRECTORY_ALIAS'):
            q.zip_members(archive([('dir', b'a'), ('dir/file', b'b')]))
        member = zipfile.ZipInfo('link')
        member.external_attr = (stat.S_IFLNK | 0o777) << 16
        with self.assertRaisesRegex(q.Refused, 'UNSAFE_ZIP_MEMBER'):
            q.zip_members(archive([(member, b'../../other')]))

    def test_zip_bounds(self):
        raw = archive([('large', b'x' * 8192)])
        with patch.object(q, 'MAX_UNPACKED', 1024), self.assertRaisesRegex(q.Refused, 'ZIP_EXPANSION_LIMIT'):
            q.zip_members(raw)

    def test_file_refuses_symlink_and_fifo(self):
        f = self.root / 'link.zip'
        f.symlink_to(self.paths[0])
        with self.assertRaises(OSError):
            q.regular_bytes(f, q.MAX_ARCHIVE)
        p = self.root / 'parent'
        p.symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(OSError):
            q.regular_bytes(p / 'module.zip', q.MAX_ARCHIVE)
        import os
        fifo = self.root / 'pipe'
        os.mkfifo(fifo)
        with self.assertRaisesRegex(q.Refused, 'FILE_NOT_BOUNDED_REGULAR'):
            q.regular_bytes(fifo, 100)

    def test_search_only_ancestor_real_permissions(self):
        import subprocess
        import sys
        # Android/Termux already runs as an unprivileged app UID. If the
        # qualification host runs as root, strip capabilities in the child
        # without changing ownership of any private qualification directory.
        parent = self.root / 'search-only'
        parent.mkdir()
        payload = parent / 'payload'
        payload.write_bytes(b'readiness evidence')
        payload.chmod(0o644)
        (parent / 'link').symlink_to(payload)
        (self.root / 'parent-link').symlink_to(parent, target_is_directory=True)
        self.root.chmod(0o711)
        parent.chmod(0o111)
        self.addCleanup(parent.chmod, 0o700)
        child = """
import os, sys
from pathlib import Path
if os.geteuid() == 0:
    import ctypes
    class Header(ctypes.Structure):
        _fields_ = [('version', ctypes.c_uint32), ('pid', ctypes.c_int)]
    class Data(ctypes.Structure):
        _fields_ = [('effective', ctypes.c_uint32), ('permitted', ctypes.c_uint32), ('inheritable', ctypes.c_uint32)]
    header = Header(0x20080522, 0)
    data = (Data * 2)()
    libc = ctypes.CDLL(None, use_errno=True)
    if libc.capset(ctypes.byref(header), ctypes.byref(data)) != 0:
        raise OSError(ctypes.get_errno(), 'cannot remove child capabilities')
sys.path.insert(0, sys.argv[1])
import r11a_readiness as q
root = Path(sys.argv[2]); parent = root / 'search-only' 
try:
    fd = os.open(parent, os.O_RDONLY | os.O_DIRECTORY)
except PermissionError:
    pass
else:
    os.close(fd)
    raise AssertionError('fixture did not restrict directory read')
assert q.regular_bytes(parent / 'payload', 100) == b'readiness evidence'
for bad in (parent / 'link', root / 'parent-link' / 'payload'):
    try:
        q.regular_bytes(bad, 100)
    except OSError:
        pass
    else:
        raise AssertionError('symlink was accepted')
"""
        result = subprocess.run([sys.executable, '-B', '-c', child, str(Path(q.__file__).parent), str(self.root)],
                                capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_duplicate_json_refused(self):
        with self.assertRaisesRegex(q.Refused, 'DUPLICATE_JSON_KEY'):
            q.decode_json(b'{"source":"a","source":"b"}')

    def test_read_only_inventory_and_plan(self):
        c = self.candidate(verified=True)
        result = q.validate_inventory(self.inventory(c), c)
        self.assertTrue(result['observations_consistent'])
        self.assertFalse(result['consent_authenticated'])
        self.assertFalse(result['device_access_performed'])
        for case in q.CASES:
            plan = q.plan_case(case, result)
            self.assertFalse(plan['effect_authorized'])
            self.assertFalse(plan['effect_executed'])
            self.assertEqual(plan['source_sha'], q.SOURCE)
            self.assertIn('restored-state hash', plan['required_after'])
        for case in ('RD-012', 'PF-008', 'fake'):
            with self.assertRaises(q.Refused):
                q.plan_case(case, result)

    def test_inventory_negative_controls(self):
        c = self.candidate(verified=True)
        for k, v in [('api', 35), ('api', True), ('abi', 'x86_64'), ('is_emulator', True), ('device_is_disposable', False),
                     ('read_only_consent', 1), ('manager', 'unknown'), ('manager_package', 'x;su'), ('manager_signer_sha256', 'bad'),
                     ('source_sha', '0' * 40), ('source_tree', '0' * 40), ('module_version_code', True),
                     ('runtime_manifest_sha256', 'bad'), ('installed_companion_sha256', 'bad'), ('installed_companion_signer_sha256', 'bad')]:
            with self.subTest(key=k), self.assertRaises(q.Refused):
                q.validate_inventory({**self.inventory(c), k: v}, c)
        with self.assertRaises(q.Refused):
            q.validate_inventory({**self.inventory(c), 'imei': 'private'}, c)

    def test_unverified_toolchain_refuses_inventory(self):
        c = self.candidate()
        with self.assertRaisesRegex(q.Refused, 'INDEPENDENT_NATIVE_AND_APK_VERIFICATION_REQUIRED'):
            q.validate_inventory(self.inventory(c), c)

    def test_preinstall_inventory_and_nonmatching_candidate(self):
        c = self.candidate(verified=True)
        info = self.inventory(c)
        info['runtime_manifest_sha256'] = 'e' * 64
        result = q.validate_inventory(info, c)
        self.assertFalse(result['module_matches_candidate'])
        self.assertTrue(result['companion_matches_candidate'])
        with self.assertRaisesRegex(q.Refused, 'EXACT_INSTALLED_CANDIDATE_REQUIRED'):
            q.plan_case('RD-006', result)
        self.assertEqual(q.plan_case('RD-002', result)['case'], 'RD-002')
        info = self.inventory(c)
        info['installed_companion_sha256'] = 'e' * 64
        result = q.validate_inventory(info, c)
        self.assertTrue(result['module_matches_candidate'])
        self.assertFalse(result['companion_matches_candidate'])
        with self.assertRaisesRegex(q.Refused, 'EXACT_INSTALLED_CANDIDATE_REQUIRED'):
            q.plan_case('RD-006', result)
        info.update(module_present=False, companion_present=False)
        for key in ('module_id', 'module_version', 'module_version_code', 'module_prop_sha256', 'runtime_manifest_sha256', 'runtime_sha256', 'installed_companion_sha256', 'installed_companion_signer_sha256'):
            info[key] = None
        result = q.validate_inventory(info, c)
        self.assertFalse(result['module_matches_candidate'])
        self.assertFalse(result['companion_matches_candidate'])
        self.assertEqual(q.plan_case('RD-001', result)['case'], 'RD-001')
        with self.assertRaises(q.Refused):
            q.validate_inventory({**info, 'module_id': 'XtremeBS'}, c)


class LiveProviderTests(unittest.TestCase):
    def setUp(self):
        self.head = 'a' * 40
        self.tree = 'b' * 40
        self.ids = [101, 102, 103]
        prefix = '/repos/' + q.REPO
        self.responses = {prefix + '/git/commits/' + self.head: {'sha': self.head, 'tree': {'sha': self.tree}}}
        for rid, (wf, names) in zip(self.ids, q.WORKFLOWS.items()):
            branch = 'feature/xbs-r11a-physical-readiness-20261127'
            run = {'id': rid, 'head_sha': self.head, 'event': 'pull_request', 'status': 'completed', 'conclusion': 'success',
                   'repository': {'id': 1422199806}, 'head_repository': {'id': 1422199806}, 'head_branch': branch, 'workflow_id': wf, 'run_attempt': 1}
            self.responses[prefix + f'/actions/runs/{rid}'] = run
            jobs = [{'name': name, 'run_id': rid, 'run_attempt': 1, 'head_sha': self.head, 'status': 'completed', 'conclusion': 'success'} for name in names]
            if wf == 386774409:
                jobs.append({**jobs[0], 'name': 'android-companion-debug', 'conclusion': 'skipped'})
            self.responses[prefix + f'/actions/runs/{rid}/attempts/1/jobs?per_page=100'] = {'total_count': len(jobs), 'jobs': jobs}
            artifacts = [{'name': f'{name}-{rid}-1', 'id': rid * 100 + n, 'size_in_bytes': 123, 'digest': 'sha256:' + 'a' * 64, 'expired': False,
                          'workflow_run': {'id': rid, 'head_sha': self.head, 'repository_id': 1422199806,
                                           'head_repository_id': 1422199806, 'head_branch': branch}}
                         for n, name in enumerate(q.ARTIFACTS[wf].values())]
            self.responses[prefix + f'/actions/runs/{rid}/artifacts?per_page=100'] = {'total_count': len(artifacts), 'artifacts': artifacts}
        self.prefix = prefix

    def verify(self):
        with patch.object(q, 'verified_local_source', return_value=self.tree):
            return q.live_metadata(ROOT, self.head, self.ids, get=lambda p: copy.deepcopy(self.responses[p]))

    def test_current_three_runs_and_seven_artifacts(self):
        result = self.verify()
        self.assertEqual(result['schema'], 'xbs-r11a-live-provider/v1')
        self.assertEqual({x['role'] for x in result['artifact_metadata']}, q.ROLES)
        self.assertFalse(result['physical_qualified'])

    def test_foreign_or_failed_run(self):
        row = self.responses[self.prefix + '/actions/runs/101']
        before = copy.deepcopy(row)
        for k, v in [('head_sha', '0' * 40), ('event', 'push'), ('conclusion', 'failure'), ('status', 'in_progress'),
                     ('head_repository', {'id': 1}), ('workflow_id', 123), ('run_attempt', True)]:
            with self.subTest(key=k):
                row.clear(); row.update(before); row[k] = v
                with self.assertRaises(q.Refused):
                    self.verify()

    def test_wrong_attempt_job_or_artifact(self):
        jobs = self.responses[self.prefix + '/actions/runs/101/attempts/1/jobs?per_page=100']
        jobs['jobs'][0]['conclusion'] = 'skipped'
        with self.assertRaisesRegex(q.Refused, 'REQUIRED_JOB_NOT_SUCCESSFUL'):
            self.verify()
        jobs['jobs'][0]['conclusion'] = 'success'
        a = self.responses[self.prefix + '/actions/runs/101/artifacts?per_page=100']['artifacts'][0]
        before = copy.deepcopy(a)
        for k, v in [('expired', True), ('digest', 'wrong'), ('name', 'xbs-android-module-101-0'), ('workflow_run', {**a['workflow_run'], 'head_sha': '0' * 40})]:
            with self.subTest(key=k):
                a.clear(); a.update(before); a[k] = v
                with self.assertRaises(q.Refused):
                    self.verify()

    def test_wrong_tree_and_incomplete_pagination(self):
        self.responses[self.prefix + '/git/commits/' + self.head]['tree']['sha'] = '0' * 40
        with self.assertRaisesRegex(q.Refused, 'PROVIDER_SOURCE_TREE_DRIFT'):
            self.verify()
        self.responses[self.prefix + '/git/commits/' + self.head]['tree']['sha'] = self.tree
        self.responses[self.prefix + '/actions/runs/101/artifacts?per_page=100']['total_count'] += 1
        with self.assertRaisesRegex(q.Refused, 'INCOMPLETE_ARTIFACT_INVENTORY'):
            self.verify()


if __name__ == '__main__':
    unittest.main(verbosity=2)

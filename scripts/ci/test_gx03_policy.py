#!/usr/bin/env python3
"""GX03 HF01: source-pinned R07A dual-workflow audit, with hostile mutations.

Pure Python, disposable fixture only. Does not run GitHub, Gradle or ADB.
"""
from __future__ import annotations
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]
CANONICAL='.github/workflows/devtool-xbs-ci.yml'
COMPAT='.github/workflows/gx03-hosted-compat.yml'
POLICY=ROOT/'scripts/ci/r07a_policy.py'
spec=importlib.util.spec_from_file_location('r07a_policy_gx03_hf01',POLICY)
assert spec is not None and spec.loader is not None
policy=importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)

class GX03R07APolicy(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='gx03-r07a-policy-')
        self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        for p in (CANONICAL,COMPAT):
            out=self.root/p
            out.parent.mkdir(parents=True,exist_ok=True)
            out.write_bytes((ROOT/p).read_bytes())

    def test_01_actual_reviewed_two_workflows(self):
        self.assertEqual(policy.audit(self.root)['reviewed_workflows'],2)
    def test_02_actual_compat_digest_pinned(self):
        from hashlib import sha256
        self.assertEqual(sha256((self.root/COMPAT).read_bytes()).hexdigest(),policy.GX03_COMPAT_SHA256)
    def test_03_source_policy_preserves_canonical_guard(self):
        self.assertEqual(policy.guard((self.root/CANONICAL).read_text())['checkout_head_pins'],4)
    def test_04_extra_unreviewed_workflow_refused(self):
        (self.root/'.github/workflows/rogue.yml').write_text('name: rogue\n')
        with self.assertRaisesRegex(policy.Refused,'UNREVIEWED_EXTRA_WORKFLOW'):
            policy.audit(self.root)
    def test_05_compat_workflow_absent_refused(self):
        (self.root/COMPAT).unlink()
        with self.assertRaisesRegex(policy.Refused,'UNREVIEWED_EXTRA_WORKFLOW'):
            policy.audit(self.root)
    def test_06_compat_workflow_modified_refused(self):
        path=self.root/COMPAT
        path.write_bytes(path.read_bytes()+b'\n# attacker\n')
        with self.assertRaisesRegex(policy.Refused,'GX03_COMPAT_WORKFLOW_SHA256_DRIFT'):
            policy.audit(self.root)
    def test_07_compat_unpinned_action_refused(self):
        path=self.root/COMPAT
        text=path.read_text().replace('actions/checkout@11d5960a326750d5838078e36cf38b85af677262','actions/checkout@v4')
        path.write_text(text)
        with self.assertRaisesRegex(policy.Refused,'GX03_COMPAT_WORKFLOW_SHA256_DRIFT'):
            policy.audit(self.root)
    def test_08_compat_artifact_skipped_refused(self):
        path=self.root/COMPAT
        text=path.read_text().replace('if-no-files-found: error','if-no-files-found: warn',1)
        path.write_text(text)
        with self.assertRaisesRegex(policy.Refused,'GX03_COMPAT_WORKFLOW_SHA256_DRIFT'):
            policy.audit(self.root)
    def test_09_compat_symlink_refused(self):
        path=self.root/COMPAT
        real=path.with_suffix('.bak');path.rename(real)
        path.symlink_to(real.name)
        with self.assertRaisesRegex(policy.Refused,'UNREVIEWED_EXTRA_WORKFLOW'):
            policy.audit(self.root)
    def test_10_canonical_tamper_refused(self):
        path=self.root/CANONICAL
        path.write_text(path.read_text().replace('  contents: read\n','  contents: write\n',1))
        with self.assertRaisesRegex(policy.Refused,'LEAST_PRIVILEGE_MISSING'):
            policy.audit(self.root)
    def test_11_third_yaml_workflow_refused(self):
        (self.root/'.github/workflows/rogue.yaml').write_text('name: rogue\n')
        with self.assertRaisesRegex(policy.Refused,'UNREVIEWED_EXTRA_WORKFLOW'):
            policy.audit(self.root)

if __name__=='__main__':unittest.main(verbosity=2)

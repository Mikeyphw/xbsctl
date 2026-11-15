"""Read-only S7 P05 XBS Android adoption acceptance; never builds or installs APK."""
from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import tomllib
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = "Mikeyphw/xbsctl"
DEVTOOL_SOURCE = "416d24140c7f7a4b9833d7cb19df50ca7e7363b8"


def git(root: Path, *args: str) -> str:
    return subprocess.run(["git", "-C", str(root), *args], check=True,
                          capture_output=True, text=True, timeout=30).stdout.strip()


def devtool(root: Path, *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    env = {**os.environ, "PYTHONDONTWRITEBYTECODE": "1", "PYTHONUNBUFFERED": "1"}
    result = subprocess.run([sys.executable, "-B", "-m", "devtool", "--repo", str(root), *args],
                            capture_output=True, text=True, timeout=120, env=env)
    if check and result.returncode:
        raise AssertionError("Devtool refused: " + result.stdout[-1200:] + result.stderr[-1200:])
    return result


class AndroidConsumerPilot(unittest.TestCase):
    def test_go_and_android_product_boundaries_retained(self):
        config = tomllib.loads((ROOT / ".devtool.toml").read_text(encoding="utf8"))
        self.assertIn("xbs", config["targets"])
        android = config["targets"]["xbs_android"]
        self.assertEqual(android["root"], "android-app")
        self.assertEqual(android["gradle"]["version"], "9.7.1")
        self.assertEqual(android["android"]["variant"], "debug")
        self.assertIn(":app:assembleDebug", android["tasks"]["build"])
        self.assertIn(":app:testDebugUnitTest", android["tasks"]["test"])
        self.assertTrue((ROOT / "devtoolw").is_file())
        original = (ROOT / ".github/workflows/devtool-xbs-ci.yml").read_text(encoding="utf8")
        self.assertIn("workflow_dispatch:", original)
        self.assertIn("android_debug", original)
        self.assertIn("go-contract", original)

    def test_hosted_workflow_source_bound_and_nonrelease(self):
        yaml = (ROOT / ".github/workflows/devtool-s7-p05-android-consumer.yml").read_text(encoding="utf8")
        self.assertIn("pull_request:", yaml)
        self.assertIn("workflow_dispatch:", yaml)
        self.assertIn("contents: read", yaml)
        self.assertIn(DEVTOOL_SOURCE, yaml)
        self.assertIn("github.event.pull_request.head.sha || github.sha", yaml)
        self.assertIn("-B -m unittest discover", yaml)
        self.assertIn("android-adoption-contract:", yaml)
        self.assertIn("android-hosted-debug:", yaml)
        self.assertIn("needs: android-adoption-contract", yaml)
        self.assertIn("9.7.1", yaml)
        self.assertIn("android-36", yaml)
        self.assertIn("build-tools;36.0.0", yaml)
        self.assertIn(":app:assembleDebug :app:testDebugUnitTest", yaml)
        self.assertIn("apksigner", yaml)
        self.assertIn("SHA256SUMS", yaml)
        self.assertNotIn("assembleRelease", yaml)
        self.assertNotIn("workflow_run:", yaml)
        self.assertNotIn("permissions: write-all", yaml)
        self.assertNotIn("android-actions/setup-android@v3", yaml)
        self.assertIn("android-actions/setup-android@be39fa834029ff78f1a44aa3bb0819b8fc2bd8fd", yaml)
        self.assertIn("packages: 'platform-tools'", yaml)
        self.assertIn("cmdline-tools-version: '15859902'", yaml)

    def test_sdk_setup_is_pinned_tools_free_and_preserves_hosted_android_gate(self):
        workflow = (ROOT / ".github/workflows/devtool-s7-p05-android-consumer.yml").read_text(encoding="utf8")
        # Older action revisions attempted `sdkmanager tools`, a removed SDK package.
        sdk_block = workflow.split("      - name: Configure hosted Android SDK (tools-free v4.0.4)\n", 1)[1].split("      - name: Provision API 36", 1)[0]
        self.assertIn("android-actions/setup-android@be39fa834029ff78f1a44aa3bb0819b8fc2bd8fd", sdk_block)
        self.assertIn("packages: 'platform-tools'", sdk_block)
        self.assertIn("cmdline-tools-version: '15859902'", sdk_block)
        self.assertNotRegex(sdk_block, r"(?m)^\s*packages:\s*['\"]?tools(?:\s|['\"]|$)")
        self.assertNotIn("sdkmanager tools", sdk_block)
        self.assertNotIn("setup-android@v3", workflow)
        self.assertIn("sdkmanager 'platforms;android-36' 'build-tools;36.0.0'", workflow)
        self.assertIn("gradle-version: '9.7.1'", workflow)
        self.assertIn(":app:assembleDebug :app:testDebugUnitTest", workflow)
        self.assertIn("apksigner", workflow)
        self.assertIn("SOURCE_SHA", workflow)


    def test_gradle_toolchain_seal_resolves_repo_devtool_config(self):
        # rootProject.file() resolves from android-app, not from the CI shell cwd.
        import re
        android_root = ROOT / "android-app"
        script = (android_root / "build.gradle").read_text(encoding="utf8")
        matches = re.findall(
            r"def devtool\s*=\s*rootProject\.file\('([^']+)'\)\.text", script
        )
        self.assertEqual(matches, ["../.devtool.toml"])
        resolved = (android_root / matches[0]).resolve(strict=True)
        self.assertEqual(resolved, (ROOT / ".devtool.toml").resolve(strict=True))
        self.assertNotIn("../../../.devtool.toml", script)
        self.assertIn("[targets.xbs_android.gradle]", resolved.read_text(encoding="utf8"))
        self.assertEqual(tomllib.loads(resolved.read_text(encoding="utf8"))["targets"]["xbs_android"]["gradle"]["version"], "9.7.1")

    def test_gradle_root_path_is_independent_of_runner_directory(self):
        # Relocate an isolated repository-shaped tree; resolve exactly as Gradle would.
        import re
        script = (ROOT / "android-app/build.gradle").read_text(encoding="utf8")
        match = re.search(r"def devtool\s*=\s*rootProject\.file\('([^']+)'\)\.text",script)
        self.assertIsNotNone(match)
        with tempfile.TemporaryDirectory(prefix="s7-p05-hf02-path-") as td:
            copied_root = Path(td) / "different" / "checkout"
            android = copied_root / "android-app"
            android.mkdir(parents=True)
            config = copied_root / ".devtool.toml"
            config.write_text('[targets.xbs_android.gradle]\nversion = "9.7.1"\n', encoding="utf8")
            self.assertEqual((android / match.group(1)).resolve(strict=True), config.resolve(strict=True))

    def test_canonical_android_adoption_audit_does_not_write(self):
        before = git(ROOT, "status", "--porcelain", "--untracked-files=all")
        output = devtool(ROOT, "delivery", "adoption", "audit", "--json")
        data = json.loads(output.stdout)
        data = data.get("data", data)
        self.assertEqual(data["schema"], "devtool.delivery-adoption-audit.v1")
        self.assertEqual(data["repository"], REPOSITORY)
        self.assertEqual(data["source_sha"], git(ROOT, "rev-parse", "HEAD"))
        self.assertTrue(data["scaffolding_ready"])
        self.assertTrue(data["worktree_clean"])
        self.assertTrue(data["devtool_config_present"])
        self.assertFalse(data["ci_qualified"])
        self.assertFalse(data["release_qualified"])
        self.assertEqual(before, git(ROOT, "status", "--porcelain", "--untracked-files=all"))

    def test_native_scaffold_uses_exact_tree_and_rejects_new_head(self):
        with tempfile.TemporaryDirectory(prefix="xbs-s7-p05-test-") as td:
            root = Path(td)
            source, payload = root / "consumer", root / "payload"
            source.mkdir(); payload.mkdir()
            git(source, "init", "-b", "main")
            git(source, "config", "user.name", "S7 P05 Pilot")
            git(source, "config", "user.email", "s7-p05@example.invalid")
            git(source, "remote", "add", "origin", "https://github.com/Mikeyphw/xbsctl.git")
            (source / ".gitignore").write_text(".devtool/\n", encoding="utf8")
            (source / "README.md").write_text("baseline\n", encoding="utf8")
            (source / ".devtool.toml").write_text(
                '[project]\nrunner = "command"\nplatform = "cli"\n'
                '[targets.xbs_android]\nroot = "."\nrunner = "android"\nplatform = "android"\n', encoding="utf8")
            git(source, "add", ".")
            git(source, "commit", "-m", "base")
            head = git(source, "rev-parse", "HEAD")
            (payload / "p05.txt").write_text("canonical Devtool Android pilot\n", encoding="utf8")
            plan = root / "plan.json"
            plan.write_text(json.dumps({"schema_version": 1,
                "changes": [{"path": "p05.txt", "action": "add"}]}), encoding="utf8")
            output = root / "xbs-s7-p05.zip"
            receipt = devtool(source, "delivery", "adoption", "scaffold", "--payload", str(payload),
                              "--plan", str(plan), "--output", str(output),
                              "--expected-head", head, "--json")
            result = json.loads(receipt.stdout)
            result = result.get("data", result)
            self.assertEqual(result["source_sha"], head)
            self.assertEqual(git(source, "status", "--porcelain"), "")
            with zipfile.ZipFile(output) as bundle:
                manifest = json.loads(bundle.read(".devtool-artifact.json"))
                self.assertEqual(manifest["base_commit"], head)
                self.assertEqual(manifest["base_tree"], git(source, "rev-parse", "HEAD^{tree}"))
                self.assertEqual(manifest["base_contract_mode"], "exact-tree")
                self.assertIsNone(bundle.testzip())
            devtool(source, "--target", "xbs_android", "validate", "overlay", str(output))
            (source / "README.md").write_text("new clean commit\n", encoding="utf8")
            git(source, "add", "README.md")
            git(source, "commit", "-m", "drift")
            failed = devtool(source, "--target", "xbs_android", "validate", "overlay", str(output), check=False)
            self.assertNotEqual(failed.returncode, 0)
            self.assertFalse((source / "p05.txt").exists())
            self.assertEqual(git(source, "status", "--porcelain"), "")


if __name__ == "__main__":
    unittest.main()

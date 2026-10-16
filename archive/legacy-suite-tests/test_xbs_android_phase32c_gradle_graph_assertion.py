from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "apps/xbs/android-app/gradle/phase31-release-seal.gradle"


def phase31_assert_graph_block() -> str:
    source = SCRIPT.read_text()
    start = source.index("tasks.register('phase31AssertGraph')")
    end = source.index("tasks.register('phase31GraphContract')", start)
    return source[start:end]


def test_phase32c_assert_graph_uses_configuration_safe_declaration_snapshot():
    source = SCRIPT.read_text()
    block = phase31_assert_graph_block()
    assert "def phase31GraphSnapshot = [" in source
    assert "ext.phase31GraphSnapshot = phase31GraphSnapshot" in source
    assert "candidateGateOrder" in source
    assert "Gradle 9.x cannot safely resolve TaskDependency objects" in source
    assert "Phase 31 graph snapshot" in block
    assert "def snapshot = phase31GraphSnapshot" in block
    assert "def snapshot = ext.phase31GraphSnapshot" not in block
    assert "taskDependencies.getDependencies" not in source
    assert "mustRunAfter.getDependencies" not in source
    assert "phase31CandidateBuild must execute clean then phase31CandidateReceipt" in block
    assert "phase31FinalBuild must rerun the final receipt/assembly pass" in block
    assert (ROOT / "apps/xbs/android-app/PHASE32B_R4_GRADLE_TESTKIT_LIFECYCLE_HOTFIX.md").is_file()

"""Shared, fail-closed source-history verification for the XBS acceptance stack."""
from __future__ import annotations
from dataclasses import dataclass
from pathlib import Path
import re
import subprocess

class HistoryRefused(ValueError):
    pass

@dataclass(frozen=True)
class Segment:
    name: str
    parent: str
    pinned_tip: str | None
    paths: frozenset[str]


def git(root: Path, *args: str) -> bytes:
    try:
        return subprocess.run(['git', '-C', str(root), *args], capture_output=True, check=True, timeout=25).stdout
    except (OSError, subprocess.SubprocessError) as exc:
        raise HistoryRefused('GIT_SOURCE_VERIFICATION_FAILED ' + repr(args)) from exc


def verify_reviewed_history(root: Path, segments: tuple[Segment, ...]) -> str:
    """Check exact direct-parent and path inventories, one historical interval at a time."""
    head = git(root, 'rev-parse', 'HEAD').decode('ascii').strip()
    lineage = git(root, 'rev-list', '--parents', '-n', '1', head).decode('ascii').split()
    if not re.fullmatch(r'[0-9a-f]{40}', head) or len(lineage) != 2 or lineage[0] != head:
        raise HistoryRefused('CURRENT_COMMIT_NOT_SINGLE_PARENT')
    current_parent = lineage[1]
    parents = [s.parent for s in segments]
    if len(set(parents)) != len(parents) or current_parent not in parents:
        raise HistoryRefused('UNREVIEWED_PARENT')
    index = parents.index(current_parent)
    for n, segment in enumerate(segments[:index + 1]):
        tip = head if n == index else segment.pinned_tip
        if tip is None or not re.fullmatch(r'[0-9a-f]{40}', tip):
            raise HistoryRefused('UNPINNED_HISTORICAL_TIP ' + segment.name)
        if n < index and segments[n + 1].parent != tip:
            raise HistoryRefused('DISCONTINUOUS_PIN_CHAIN ' + segment.name)
        observed = git(root, 'rev-list', '--parents', '-n', '1', tip).decode('ascii').split()
        if observed != [tip, segment.parent]:
            raise HistoryRefused('PARENT_DRIFT ' + segment.name)
        delta = git(root, 'diff', '--name-only', '--no-renames', '-z', segment.parent, tip)
        paths = frozenset(p.decode('utf-8', errors='surrogateescape') for p in delta.split(b'\0') if p)
        if paths != segment.paths:
            raise HistoryRefused('CHANGED_PATHS_DRIFT ' + segment.name + ' ' + repr(sorted(paths ^ segment.paths)))
    return segments[index].name

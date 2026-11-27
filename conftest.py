"""Discover active standalone XBS tests, not retired monorepo fixtures.

See archive/README.md: the archived tests reference removed Python modules
and old apps/xbs paths. Keep active scripts/ci and tests discovery intact.
"""

collect_ignore = ["archive/legacy-suite-tests"]

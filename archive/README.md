# Historical XBS sources (not part of active builds)

`legacy-suite-tests/` contains the XBS-named monorepo tests from the snapshot.
Some reference removed Python code or `apps/xbs` paths and are **not** production validation.
`legacy-monorepo-app/` preserves the old root-level Android source fragment.
The authoritative Android app is `android-app/`; the authoritative tests are `go test ./...` and Android Gradle tests.

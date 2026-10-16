# XBS (standalone xbsctl repository)

The Go-native XtremeBS runtime, Magisk/KernelSU module builder, and XBS Companion Android app have moved from `mike-cli-suite/apps/xbs` into this standalone repository.
The former Python/Textual `xbsctl` prototype is **replaced**, not retained as production code. Its earlier Git history remains accessible in a **local backup branch**; it is intentionally absent from the new GitHub main history.

## CLI and module (Go 1.23+)

```sh
go test ./...
go run ./cmd/xbs --help
go run ./cmd/XBSctl --help
go run ./cmd/xbsrepo build-module --out dist/XtremeBS-go.zip
go run ./cmd/xbsrepo final-seal
```

`XBSctl` is a Go-based Android module command; it is **not** the old Python `xbsctl` command.
Build binaries with `GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build ./cmd/xbs`, similarly for `cmd/XBSctl` and `cmd/XtremeBSd`.

## Android Companion

The native Android project is in `android-app/`. Its Gradle version requirement is 9.7.1, Java 21, compileSdk 36.
Use `devtool -r "$PWD" --target xbs_android build` or the hosted `devtool-xbs-ci.yml` Android debug option.

## Devtool orchestration

`devtoolw` is generated from `.devtool.toml` via `devtool wrapper init`. Targets: `xbs` and `xbs_android`. Jobs and dependency-aware workflows run through Devtool/EXO.
The hosted GitHub workflow produces candidates, not a signed/device-qualified release.

## Source migration

See `MIGRATION.md` for lineage, preserved history, and the separate `mike-cli-suite` changes.

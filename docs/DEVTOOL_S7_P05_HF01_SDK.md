# S7 P05 HF01 — repair hosted Android SDK provisioning

**Repository:** `Mikeyphw/xbsctl`  
**Existing draft PR:** #29, branch `feature/s7-p05-xbsctl-android-adoption-20261115`  
**Required source HEAD:** `938a463a060dff86d31b8b26710d0b44386eee65`.

The first P05 hosted attempt failed at `android-actions/setup-android@v3` during
`Configure hosted Android SDK` because that action invoked the removed package
`tools` (`sdkmanager tools`: `Failed to find package 'tools'`). The preceding
canonical Devtool audit and exact-tree tests passed. APK compilation never ran.

This correction modifies **only** the P05 GitHub Actions workflow and its
own regression test, plus this additive explanation. The SDK action is pinned
to `be39fa834029ff78f1a44aa3bb0819b8fc2bd8fd` (v4.0.4); the action's
input `packages` explicitly requests `platform-tools`, not the deprecated
`tools` package, and commandline tools are set to release `15859902`.
Existing `sdkmanager 'platforms;android-36' 'build-tools;36.0.0'`, Gradle 9.7.1,
`:app:assembleDebug :app:testDebugUnitTest`, APK signature verification, SHA-256
provenance, and source SHA verification remain unchanged.

**No result is pre-qualified:** hosted SDK initialization, Gradle compilation,
unit tests, and APK verification must all be observed on the new exact PR HEAD.
The original `devtool-xbs-ci.yml` and Go/module delivery remain untouched.
Release/signing and physical-device acceptance are outside this non-release gate.
No local Gradle or device writes are part of this change.

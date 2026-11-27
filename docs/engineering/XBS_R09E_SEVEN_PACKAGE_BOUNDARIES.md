# XBS R09E — Seven-package boundary tests and measured coverage guard

Stacked on R09D PR #24 at SHA `f8218222993940688df715d0ee32b353ff8092b5`, which completed Go/JVM/API36 in GitHub Actions run `43646824836`. R09D's measured full-internal-package statement coverage was **71.29%**, with all seven risk packages above the 50% working target. The earlier R09A 19.9% refers to a **selected-test scope** and cannot be compared to R09D's full-suite 71.29% as an improvement.

## Implementation

Seven named root Go tests, one for each risk package, exercise concrete boundary conditions: CLI JSON/argument conversion; config event grammar, nested reads and scalar updates; durable queue acknowledgement identifier path refusal; daemon argument and event guard; bridge numeric/hex/digest parsing; R04 candidate source and package identity refusals; and Android package/Wi-Fi probe output parsing. All are rootless, read-only tests with no real Android effects. Each test has multiple negative subcases.

The `go-contract` GitHub Actions job runs `scripts/ci/r09e_boundary_coverage.py --hosted --repo .`, after R09D's full-suite run, so its authoritative R09D coverage receipt already includes R09E tests. R09E separately requires actual named Go test run/pass events, **7 fresh tests + 21 repeat pass events + 7 race pass events** across all seven packages. It imports and revalidates R09D's actual, source/run/attempt-bound coverage receipt and raw profile bytes rather than assuming coverage from test names.

## Coverage gate

The R09E receipt records exact current seven-package percentages, total statement coverage, a SHA-256 of R09D's actual coverprofile, and measured delta from the **observed R09D full-suite baseline 71.29%**. It rejects regression below that baseline and refuses any seven-package value below 50%. A positive coverage delta is reported only if supported by the real hosted profile; no specific improvement is presupposed. R09D's full coverage threshold flag must be true. All previous R07/R09A–D source-bound evidence and required Go/JVM/API36 jobs still must pass.

## Security and release scope

No Android root access, ADB, module install, local Gradle, release signing, device mutation, or automatic release. CI and evidence are intentionally hosted-only. Separate physical root qualification and final release signoff remain pending, as do randomized multi-effect crash-fault schedules, daemon cross-process restart testing, and R09F final adversarial audit.

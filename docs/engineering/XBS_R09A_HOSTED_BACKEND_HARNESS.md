# R09A — Hosted Go fuzz, repeat, queue and risk-coverage qualification

**Source base**: XBS R07A `0e3de1e2f2281c404743574058d44d826d7c55eb`.

R09A is an executable starter for R09-02 (type and path validation), R09-05
(concurrent durable queue ID/ack behavior), R09-06 (five actual Go fuzz targets),
R09-08 (bounded fresh repetitions, existing hosted race detector), R09-10
(actual Go coverage profile), and R09-11 (no root integration ACK). It does
**not** complete R09-01/03/04/07/09 or prove root/device safety.

Hosted `go-contract` runs this lane at the exact PR head before Devtool/module
candidates. It uses Go 1.23 `go test -json` seed cases, three fresh
repeat passes over explicit R09A test cases, five real fuzz runs for two
seconds each with bounded `-parallel=2`, and `go test -covermode=atomic`.
A receipt holds real Go test IDs and counts, per-target fuzz execution
counts, total statement coverage percentage, SHA256 of the coverage profile,
source SHA, GitHub run/attempt, and false device/physical/release claims.
It is uploaded into the existing module artifact and independently checked
against live GitHub logs and uploaded bytes.

The CI remains mandatory: no local Gradle or ADB, no release or installation.
The previous R07A policy, Go race/vet, Android JVM and API36 gates are
unchanged and must pass on the new stacked PR. Coverage percentage is a
baseline measurement, **not** a blanket backend coverage sufficiency claim.

### Remaining R09 work

- Deterministic fake Android effects backend (R09-01).
- Full path schema/property matrix and every effect-family restore invariant.
- Fault-injection crash-point matrix, queue restart/dedup, fuzz across bridge
  signed envelopes, module ZIP/ELF negatives, golden exit codes.
- Per-risk coverage thresholds after source-instrumented risk mapping.
- Final R09 and R07 signoff dependent on the remaining mandatory gates.

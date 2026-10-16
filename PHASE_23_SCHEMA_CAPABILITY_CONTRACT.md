# Phase 23: Schema compatibility and capability negotiation

## Contract producer

`xbs --json capabilities` is the canonical cross-layer compatibility document. It publishes:

- module ID, version, and version code;
- CLI, command-schema, and machine-output schema versions;
- minimum and maximum supported Android contract versions;
- minimum Android app version code;
- active and maximum config versions;
- writable event fields discovered from `schema.json`;
- executable XBS and XBSctl command IDs and literal paths;
- canonical SHA-256 config-schema and command-contract digests.

## Android consumer

`XbsCompatibilityProbe` retrieves only the fixed allowlisted capabilities command. `XbsCompatibilityStore` persists bounded evidence, and `XbsCapabilityRegistry` is the single decision surface for Command Center, Quick Actions, event editing, diagnostics, guided fixes, release readiness, and support export.

Live advertised capabilities are authoritative. The static catalog is retained only as a clearly labeled offline fallback. A command omitted by a live contract is blocked. An event field omitted by a live contract is not sent, while an existing unsupported property is preserved unchanged.

## Evolution rules

- Older contracts may encode command IDs as strings or objects.
- Unknown future JSON fields are ignored.
- Android contract, app version, and config version incompatibility block live execution.
- Digest drift is visible as attention evidence rather than hidden.
- Missing capabilities never masquerade as negotiated live support.

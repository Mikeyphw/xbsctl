# XBS Python → Go migration fixtures

This directory freezes the current Python/shell/Android XBS contracts before the
Go backend becomes production-authoritative.

- `contracts/v1/` captures command, config, compatibility, event-bridge,
  XBSctl, and Android catalog surfaces.
- `golden/v1/python_cli/` captures representative machine-output envelopes from
  the legacy Python CLI with volatile paths normalized to placeholders.

The XGO01 Go binary is intentionally a scaffold: it may inspect these contracts,
but it must refuse production commands until later parity phases are implemented.

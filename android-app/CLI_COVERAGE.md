# XBS CLI and Tasker coverage

Version 4.2.0 provides literal GUI coverage for every supported parser command in `xbs` and every command in `XBSctl`.

## xbs

- Global flags: `--json`, `--quiet`, `--no-interactive`
- General: help, status, init, paths, validate, capabilities
- Config: get, set, validate, edit (`--editor`, `--print-path`), schema
- Runtime: reload, pause, resume, safe
- Events: list, run, stop
- Schema: commands, command, output, config
- Compatibility: `xbs --json capabilities` publishes command IDs, event fields, version ranges, and SHA-256 schema evidence
- Completion: zsh, install, hidden complete-zsh (`--words`, `--index`)
- Manpage: show, install

## XBSctl

- help, start, stop, reload, pause, resume, safe, active, status, detail, validate
- `--json`, optional event defaulting to manual, `--wait`, and `--timeout`

## Tasker

Tasker Action uses the same typed command catalog as the in-app Command Center. Every literal xbs and XBSctl command and flag is selectable; no arbitrary shell input is exposed. Existing Tasker Event and State integrations remain available.

## Negotiated availability

The static catalog remains the complete offline reference, but live execution availability is negotiated from `xbs --json capabilities`. A live contract that omits a command disables that action. Unsupported event fields are not sent by the editor, and existing unknown properties are preserved.

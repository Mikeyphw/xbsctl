# Phase 15: Event Editor Validation Polish

Phase 15 strengthens the existing event editor without turning it into the later staged-editor redesign.

## Validation model

`XbsEventDraftValidator` is a pure Java contract that checks the complete editor draft before any root work begins. It reports field-addressable blocking errors and reviewable warnings for:

- missing, malformed, protected, duplicate, and case-colliding event identities;
- malformed or duplicate package entries and allowlist/denylist conflicts;
- process-rule column shape, unsafe shell punctuation, duplicate patterns, and Linux nice range;
- enabled process handling without an effective process rule;
- malformed, duplicate, or overlapping custom CPU-core actions;
- risky `cpu0` disable requests and behavior combinations that deserve review.

## Editor behavior

Validation runs as fields, switches, and choices change. Blocking issues appear through the relevant input's inline error channel, the footer reports the current error/warning count, and the save action stays disabled until blocking errors are gone. Warnings are shown together before save and can be returned to for editing.

The editor captures one immutable `EditorState` on the UI thread before dispatching root work. Background save code reads only that snapshot, clones the loaded JSON, preserves unknown event keys, and commits through the Phase 14 transaction journal. Failed saves therefore cannot leave the in-memory editor model half-mutated.

Comment-only lines are removed from managed lists during normalization because the XBS runtime readers consume list files directly rather than parsing comments.

## Validation gate

- `XbsEventDraftValidatorTest` covers seven validation families.
- Repository contracts ensure inline validation, warning review, immutable capture, and transaction-backed save remain wired.
- No Android resource or manifest change is introduced.

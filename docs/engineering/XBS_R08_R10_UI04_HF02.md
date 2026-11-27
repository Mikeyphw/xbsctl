# UI04-HF02 — Adaptive RTL evidence repair

PR 42, published HF01 source `76ea8cd00549b6c1bea5d149ce70a39f1efb8330`, tree `66ad5866e3843ce6d82e0a7084b66d5731fd018c`.

Hosted run 46040106111, job 140646228090, attempt 1: the five real SystemUI callbacks passed, process-death stages passed, GX05 rootless security/performance passed; API36 stopped at `GX10_RTL_NAVIGATION_NOT_MIRRORED`. Both other required workflows passed. The validator had assumed horizontal navigation even for the expanded vertical rail. No failure of the app's actual RTL rail position is established by this refusal.

Verify all five measured controls: compact horizontal semantic order (LTR or RTL), expanded top-to-bottom order with shared horizontal alignment and the correct start edge relative to the content title, not just two endpoints. Apply the same rail validation when reconciling the landscape evidence metadata. Require the existing localized labels, real focusability, measured targets, redacted images and exact source identities. Never publish raw XML. Preserve all prior tile, Tasker, editor, process-death and required workflow gates. Original acceptance, TalkBack, device and release remain separate. This fix is not a claim that the new hosted run has passed.

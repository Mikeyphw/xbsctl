# UI04-HF01 — active tile lifecycle evidence

PR #42: exact failed head `0b8cf021e94be00154dd72a1ed50b3c08dc12cc2` (tree `ed54d3190d63bac8cceccbf79337816b0b54f8f2`). API36 workflow 46033311712, job 140623444905, exited 2 at `GX04_SYSTEMUI_START_LISTENING_CALLBACK_MISSING`. Go, JVM, adoption and API26/API34 passed; later API36 journeys did not complete.

Active-mode tiles do not promise a listening callback on panel expansion. See https://developer.android.com/reference/android/service/quicksettings/TileService and AOSP CustomTile.handleClick. The host now snapshots immediately before one genuine SystemUI click, requires fresh listening/click/stop deltas, requires fresh add/removal callbacks and refuses duplicate dispatch. No production permission or callback policy is relaxed. Tests model asynchronous callbacks, stale counts, duplicate callbacks and cleanup.

The reviewed path inventory binds this repair to the existing published UI04 parent. Native delivery must retain PR #42 and its original main base; all three required workflows must qualify the new exact head. Android runtime, physical device and release acceptance remain pending until actually observed.

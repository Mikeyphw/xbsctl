from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "apps" / "xbs" / "android-app" / "app" / "src" / "main" / "java" / "io" / "github" / "mikeceead" / "xbscompanion"
MANIFEST = ROOT / "apps" / "xbs" / "android-app" / "app" / "src" / "main" / "AndroidManifest.xml"
BUILD = ROOT / "apps" / "xbs" / "android-app" / "app" / "build.gradle"


def read(name: str) -> str:
    return (JAVA / name).read_text(encoding="utf-8")


def test_default_events_are_protected_in_ui_and_save_layer():
    api = read("XbsConfigApi.java")
    editor = read("XbsEventEditorActivity.java")
    main = read("MainActivity.java")
    assert all(name in api for name in ("template", "boot", "charging", "screen_off", "low_power", "manual"))
    assert "Built-in events cannot be deleted" in api
    assert 'String target=template?"template":(!clone&&builtIn?originalName:requestedName);' in editor
    assert "Protected default event" in main


def test_essentials_and_list_pickers_are_exposed():
    manifest = MANIFEST.read_text(encoding="utf-8")
    config = read("XbsConfigActivity.java")
    editor = read("XbsEventEditorActivity.java")
    assert ".XbsEssentialsActivity" in manifest
    assert ".AppPickerActivity" in manifest
    assert "Edit default essentials list" in config
    assert editor.count("Select installed apps") >= 2
    assert "Select live processes" in editor


def test_live_process_monitor_has_cpu_ram_and_app_mapping():
    manifest = MANIFEST.read_text(encoding="utf-8")
    repo = read("ProcessRepository.java")
    monitor = read("ProcessMonitorActivity.java")
    assert ".ProcessMonitorActivity" in manifest
    assert "/proc/stat" in repo and "/proc/[0-9]*" in repo
    assert "cpuPercent" in repo and "rssKb" in repo
    assert "findOwner" in repo and "AppCatalog" in repo
    assert "CPU %.1f%%" in monitor and "RAM %s" in monitor
    assert "XBS rules:" in monitor


def test_multi_active_event_state_is_preserved_everywhere():
    snapshot = read("XbsSnapshot.java")
    processor = read("XbsStateProcessor.java")
    notification = read("NotificationController.java")
    assert "LinkedHashSet<String> active" in snapshot
    assert "Active simultaneously:" in snapshot
    assert "for (String name : current.active)" in processor
    assert "for (String name : previous.active)" in processor
    assert "snapshot.active.size() == 1" in notification
    assert "Active events (" in notification


def test_root_check_and_layered_caches_are_present():
    root_api = read("XbsRootApi.java")
    cache = read("XbsCache.java")
    app_catalog = read("AppCatalog.java")
    process_repo = read("ProcessRepository.java")
    assert "checkRoot" in root_api
    assert "KernelSU / KernelSU Next" in root_api
    assert "APatch" in root_api and "Magisk" in root_api
    assert "xbs_companion_cache" in cache
    assert "30L * 60L * 1000L" in app_catalog
    assert "CACHE_AGE = 1500L" in process_repo


def test_release_version_and_package_visibility_permission():
    build = BUILD.read_text(encoding="utf-8")
    assert "xbsReleaseVersionName = '4.2.0'" in build
    assert "xbsReleaseVersionCode = 15" in build
    assert "versionName xbsReleaseVersionName" in build
    assert "versionCode xbsReleaseVersionCode" in build
    assert "android.permission.QUERY_ALL_PACKAGES" in MANIFEST.read_text(encoding="utf-8")


def test_process_name_sort_uses_explicit_generic_lambda_types():
    monitor = read("ProcessMonitorActivity.java")
    assert "Comparator.comparing((XbsProcess process) -> process.appLabel.toLowerCase(Locale.ROOT))" in monitor
    assert "thenComparing((XbsProcess process) -> process.processName)" in monitor
    assert "Comparator.comparing(p->p.appLabel" not in monitor


def test_event_editor_exposes_all_runtime_properties_and_managed_paths():
    editor = read("XbsEventEditorActivity.java")
    for key in (
        "keep_on_charge", "quit", "handle_cores", "disable_cores", "handle_apps",
        "handle_gms", "handle_proc", "low_ram", "doze", "kill_wifi",
    ):
        assert key in editor
    assert "List paths are app-managed and cannot be edited" in editor
    assert "Managed list path" in editor


def test_app_picker_filters_user_and_system_apps():
    picker = read("AppPickerActivity.java")
    assert 'ui.toggle("User apps", true)' in picker
    assert 'ui.toggle("System apps", true)' in picker
    assert "app.system && !showSystem.isChecked()" in picker
    assert "!app.system && !showUser.isChecked()" in picker


def test_process_sampler_is_root_checked_and_uses_shell_builtins():
    repo = read("ProcessRepository.java")
    assert "XbsRootApi.checkRoot(context, false)" in repo
    assert "while IFS= read -r LINE" in repo
    assert "ProcessSampleParser.parse(output.stdout)" in repo
    assert "ProcessSnapshot.error(parsed.error, diagnostics)" in repo
    assert "runRootLarge(buildScanShell(), 45)" in repo


def test_results_are_humanized_before_notifications():
    formatter = read("ResultFormatter.java")
    notification = read("NotificationController.java")
    runs = read("RunStore.java")
    assert "commandRejection" in formatter
    assert "looksBinary" in formatter
    assert "ResultFormatter.notificationText(record.message)" in notification
    assert "ResultFormatter.summary" in runs


def test_built_in_event_edits_do_not_trigger_rename_guard_and_save_is_fixed():
    editor = read("XbsEventEditorActivity.java")
    assert 'String target=template?"template":(!clone&&builtIn?originalName:requestedName);' in editor
    assert 'if(!clone&&builtIn&&!target.equals(originalName))' not in editor
    assert 'saveButton=ui.button(template?"Validate and save template":"Validate and save event"' in editor
    assert 'foot.addView(saveButton,saveParams)' in editor
    assert 'Use the fixed Validate and save button below.' in editor

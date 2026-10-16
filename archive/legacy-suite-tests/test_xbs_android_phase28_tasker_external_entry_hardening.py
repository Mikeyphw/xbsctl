from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/'apps/xbs/android-app/app/src/main/java/io/github/mikeceead/xbscompanion'
def read(p): return (ROOT/p).read_text()
def test_manifest_external_surfaces_are_explicit_and_boot_private():
 m=read(Path('apps/xbs/android-app/app/src/main/AndroidManifest.xml'))
 assert '<receiver android:name=".BootReceiver" android:exported="false">' in m
 assert m.count('android:name=".TaskerPluginReceiver"')==1 and m.count('android:name=".TaskerConditionReceiver"')==1
 assert 'android:exported="true"' in m
def test_tasker_configs_are_signed_pinned_bounded_and_destructive_opt_in():
 auth=read(Path('apps/xbs/android-app/app/src/main/java/io/github/mikeceead/xbscompanion/TaskerAuthorization.java'))
 policy=read(Path('apps/xbs/android-app/app/src/main/java/io/github/mikeceead/xbscompanion/TaskerRequestPolicy.java'))
 assert 'HmacSHA256' in policy and 'host_cert_sha256' in policy and 'allow_destructive' in policy
 assert 'AndroidKeyStore' in auth and 'KeyGenParameterSpec' in auth and 'host_identity_changed' in auth
 assert 'authorization_token' not in auth
def test_receivers_use_go_async_and_validate_sender_and_signature():
 for name in ['TaskerPluginReceiver.java','TaskerConditionReceiver.java']:
  src=(JAVA/name).read_text(); assert 'goAsync()' in src and 'TaskerAuthorization.verify' in src and 'senderMatches' in src and 'pending.finish()' in src and 'senderUid' in src
def test_completion_intents_fail_closed_to_tasker_package_without_uris():
 src=(JAVA/'TaskerCompletionPolicy.java').read_text()
 for token in ['getData()!=null','getClipData()!=null','getSelector()!=null','URI_GRANTS','implicit_or_cross_package_completion']:
  assert token in src
def test_cli_json_is_bounded_flat_and_allowlisted():
 src=(JAVA/'TaskerConfigCodec.java').read_text()
 assert 'nested_or_non_string_cli_value' in src and 'validateCliValues' in src and 'MAX_VALUE_CHARS' in src and 'validTypes' in src
def test_support_and_release_contract_uses_final_v18_format():
 support=(JAVA/'SupportBundleBuilder.java').read_text(); release=(JAVA/'ReleaseReadinessPolicy.java').read_text()
 assert 'FORMAT_VERSION = 18' in support and '[External entry hardening]' in support
 assert 'REQUIRED_SUPPORT_BUNDLE_FORMAT = 18' in release

def test_signature_binds_exact_literal_values_and_variable_replacement_is_not_emitted():
 policy=(JAVA/'TaskerRequestPolicy.java').read_text(); protocol=(JAVA/'TaskerProtocol.java').read_text(); activity=(JAVA/'TaskerPluginActivity.java').read_text()
 assert 'raw(entry.getValue())' in policy
 assert 'c.putString(REPLACE' not in protocol
 assert 'TaskerProtocol.KEY_REPLACE' not in activity
 assert 'VARIABLE_REPLACE_KEYS' not in policy
def test_condition_pass_through_is_allowlisted_and_bounded():
 src=(JAVA/'TaskerConditionProtocol.java').read_text(); receiver=(JAVA/'TaskerConditionReceiver.java').read_text()
 assert 'sanitizePassThrough' in src and 'total > 8192' in src and 'allowed.contains(key)' in src
 assert 'sanitizePassThrough' in receiver

def test_tasker_editor_only_loads_verified_saved_configuration():
 src=(JAVA/'TaskerPluginActivity.java').read_text(); assert 'TaskerAuthorization.verify(this,b,false)' in src
def test_completion_input_and_semantics_are_bounded():
 src=(JAVA/'TaskerCompletionPolicy.java').read_text(); policy=(JAVA/'TaskerRequestPolicy.java').read_text()
 assert 'completion_intent_too_large' in src and 'boundedExtras' in src
 assert 'invalid_module' in policy and 'invalid_backend' in policy and 'invalid_condition_kind' in policy


def test_outbound_tasker_events_require_the_pinned_current_signer():
 auth=(JAVA/'TaskerAuthorization.java').read_text(); host=(JAVA/'TaskerHostIdentity.java').read_text(); dispatcher=(JAVA/'TaskerEventDispatcher.java').read_text()
 assert 'tasker_host_pin_v2' in auth and 'trustedHost' in auth and 'rememberHost' in auth
 assert 'getApkContentsSigners()' in host and 'getSigningCertificateHistory()' not in host
 assert 'TaskerAuthorization.trustedHost' in dispatcher

def test_completion_variables_are_bounded_after_machine_expansion():
 policy=(JAVA/'TaskerCompletionPolicy.java').read_text(); protocol=(JAVA/'TaskerProtocol.java').read_text()
 assert 'sanitizeVariables' in policy and '49_152' in policy and '8192' in policy
 assert 'unsafe_result_variables' in protocol and 'safeVariables' in protocol


def test_exported_receiver_workers_are_bounded_and_finish_on_queue_rejection():
 executor=(JAVA/'ExternalEntryExecutor.java').read_text()
 assert 'ArrayBlockingQueue<>(16)' in executor and 'AbortPolicy' in executor
 for name in ['TaskerPluginReceiver.java','TaskerConditionReceiver.java']:
  src=(JAVA/name).read_text()
  assert 'ExternalEntryExecutor.create' in src and 'catch (RuntimeException rejected)' in src
  assert src.count('pending.finish()') >= 2


def test_release_gate_fingerprints_external_entry_security_sources():
 gradle=(ROOT/'apps/xbs/android-app/app/build.gradle').read_text()
 for name in ['ExternalEntryExecutor.java','TaskerHostIdentity.java','TaskerRequestPolicy.java','TaskerAuthorization.java','TaskerConfigCodec.java','TaskerCompletionPolicy.java','TaskerPluginReceiver.java','TaskerConditionReceiver.java','TaskerEventDispatcher.java']:
  assert name in gradle


def test_settings_describes_active_phase28_security_not_a_future_plan():
 src=(JAVA/'MoreSettingsActivity.java').read_text()
 assert 'External entry hardening is scheduled for Phase 28' not in src
 assert 'literal HMAC-signed configurations' in src and 'Re-save older Tasker configurations' in src


def test_tasker_exported_receivers_reject_sender_unavailable_mode():
    codec=(JAVA/'TaskerConfigCodec.java').read_text()
    assert 'identityUnavailable) return false' in codec
    for name in ['TaskerPluginReceiver.java','TaskerConditionReceiver.java']:
        src=(JAVA/name).read_text()
        assert 'sender_mismatch' in src and 'senderVerified' in src

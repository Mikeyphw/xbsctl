"""Source-bound editor fixture coverage; root/device/Tasker acceptance is separate."""
import json
import re
from pathlib import Path
from gx03_evidence import need, sha

CLASS='Gx10EditorInstrumentedTest'
METHODS=frozenset({'configurationDraftSurvivesResumeRecreationAndFailedSave',
 'pendingSaveRecreationDoesNotDispatchTwice','essentialsRejectsProtectedPathsAndRetainsDraftOnFailure',
 'customEventDraftSurvivesRecreationAndCancelDoesNotWrite',
 'privateDraftRestoresLargeTextAndRefusesUnknownWriteOutcome'})
CASE_IDS=frozenset({'EM-005','EM-006','EM-007','EM-011','EM-015','EM-016','EM-017','EM-018'})
PACKAGE='io/github/mikeceead/xbscompanion'
APP=Path('android-app/app/src')
EVIDENCE=Path('docs/engineering/XBS_R08_R10_UI02_EVIDENCE.json')

def validate_editor_source(root):
    java=(root/APP/'androidTest/java'/PACKAGE/(CLASS+'.java')).read_text()
    methods=re.findall(r'@Test\s+public\s+void\s+(\w+)\s*\(',java)
    need(len(methods)==len(METHODS) and set(methods)==METHODS,'UI02_TEST_INVENTORY_DRIFT')
    host=(root/'scripts/ci/gx03_emulator_host.py').read_text()
    need("gradle_test('io.github.mikeceead.xbscompanion.Gx10EditorInstrumentedTest')" in host and
         "report['gx10_editor_assertions']=junit_receipt(RESULTS" in host and
         "==len(editor_methods)" in host,'UI02_HOSTED_ASSERTIONS_MISSING')
    release=(root/APP/'release/java'/PACKAGE/'EditorBackendProvider.java').read_text()
    need('return EditorBackend.ROOT;' in release and 'installForInstrumentation' not in release,
         'UI02_RELEASE_FIXTURE_LEAK')
    main=root/APP/'main/java'/PACKAGE
    need(not (main/'EditorBackendProvider.java').exists(),'UI02_MAIN_BACKEND_OVERRIDE')
    session=(main/'EditorSession.java').read_text()
    need('if (!persist(true)) return false;' in session and 'if (running || uncertain || completed != null) return false;' in session and
         'getNoBackupFilesDir()' in session and 'MAX_BYTES = 8 * 1024 * 1024' in session,'UI02_DURABLE_MUTATION_GUARD_DRIFT')
    for screen in ('XbsConfigActivity','XbsEventEditorActivity','XbsEssentialsActivity'):
        src=(main/(screen+'.java')).read_text()
        need('session.start(' in src and 'session.save(out,' in src and 'session.detach()' in src,'UI02_EDITOR_LIFECYCLE_DRIFT '+screen)
    for screen in ('TaskerPluginActivity','TaskerEventEditActivity','TaskerStateEditActivity'):
        src=(main/(screen+'.java')).read_text()
        need('TaskerAuthorization.editorIdentity(this)' in src and 'TaskerAuthorization.sign(this,' in src and
             'session.save(out,' in src,'UI02_TASKER_AUTHORIZATION_OR_DRAFT_DRIFT '+screen)
    need('python3 -B scripts/ci/test_gx10_editor.py' in (root/'.github/workflows/devtool-xbs-ci.yml').read_text(),'UI02_LOCAL_REGRESSIONS_NOT_REQUIRED')

def validate_handoff(root):
    path=root/EVIDENCE
    need(path.is_file() and not path.is_symlink() and path.stat().st_size<100000,'UI02_HANDOFF_MISSING')
    data=json.loads(path.read_bytes())
    need(data.get('schema')=='xbs-r08r10-ui02-handoff/v1' and data.get('source_commit')=='f98ae063f6b20a6a4b6cac86342dbacaefe873b0' and data.get('source_tree')=='cad6c6abbb0eeff73b888c8f1b67ed2bc7abe01f','UI02_PRIOR_SOURCE_DRIFT')
    need(data.get('main_workflow')=={'id':386774409,'run_id':45998645628,'attempt':1,'conclusion':'success'},'UI02_PRIOR_RUN_DRIFT')
    expected={15198160605:'f54cee0d761ec3ba72f56e10b6a9014fc5ce2eb96407fc4ce4d7f3026d0685e2',15197712301:'e1c51361e119001138519696188c445792d449c2301cead9f53ee18263f772f8',15196823544:'3f7f7e4e1046a60fe7826be1ff143a463eeaa6dc995fd8ce4471ccef03b6802d',15196608246:'4470e3c4e314b0a4645baf63580fcab0554f9833c755ec1e9bc1cb6c85d5eb58'}
    need(len(data.get('artifacts',[]))==4 and {a['artifact_id']:a['sha256'] for a in data['artifacts']}==expected,'UI02_PRIOR_ARTIFACT_DRIFT')
    matrix=json.loads((root/'docs/engineering/XBS_TEST_MATRIX.json').read_bytes())
    original={x['id']:x for x in matrix['cases']}
    need(len(original)==131 and set(data['case_links'])==CASE_IDS and data['original_cases_accepted']==[] and not data['physical_qualified'] and not data['release_qualified'] and all(original[k]['required_assertion']==v['required_assertion'] and v['acceptance']=='PARTIAL_CANDIDATE_ONLY' for k,v in data['case_links'].items()),'UI02_ORIGINAL_MAPPING_DRIFT')

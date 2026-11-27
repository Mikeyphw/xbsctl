"""UI03 source and immutable predecessor evidence gate."""
import json
from gx03_evidence import need
from gx10_editor import APP,PACKAGE,CASE_IDS

def validate_transaction_source(root):
    main=root/APP/'main/java'/PACKAGE
    source=(main/'XbsConfigTransaction.java').read_text()
    need('acquire_lock; guard_previous; verify_preimages;' in source and "write_status prepared 'All files staged and validated'; verify_preimages;" in source and 'verify_journal_files; verify_committed' in source and 'mktemp' in source,'UI03_GENERATION_GUARD_DRIFT')
    need('expectedConfig, expectedLists' in (main/'XbsConfigApi.java').read_text() and 'XBS_SNAPSHOT_V1:' in (main/'RootFileSnapshot.java').read_text(),'UI03_EXACT_SNAPSHOT_DRIFT')
    need('python3 -B scripts/ci/test_gx10_transaction.py' in (root/'.github/workflows/devtool-xbs-ci.yml').read_text(),'UI03_EXECUTED_TESTS_NOT_REQUIRED')
    data=json.loads((root/'docs/engineering/XBS_R08_R10_UI03_EVIDENCE.json').read_bytes())
    need(data['schema']=='xbs-r08r10-ui03-handoff/v1' and data['source_commit']=='043b1851d31fef0601e76443f47b88e59f3ca373' and data['source_tree']=='5638a149d150991d3b5fc93c6070bacc8106c35e','UI03_PRIOR_SOURCE_DRIFT')
    need(data['main_workflow']=={'id':386774409,'run_id':46005931468,'attempt':1,'conclusion':'success'},'UI03_PRIOR_RUN_DRIFT')
    need({a['artifact_id']:a['sha256'] for a in data['artifacts']}=={15202451004: '20d3bb605f186797a627420232827c5e524e821c1a82d0753cfb2bc66da245d1', 15202272821: '0c426df0edbc1a21e3f7cd2430bc984fdeee55c45fe2a37395f79bd8249a9551', 15202006312: 'b008fa1570b18b7869e1b5065e63e9d9510c9d705cb286c63da302ce2f8e9a2e', 15201943086: '521eee2093f5adf8c2ee6810d45a084ab22acba4ff9844beafb31f561ce52fc7'},'UI03_PRIOR_ARTIFACT_DRIFT')
    original={x['id']:x for x in json.loads((root/'docs/engineering/XBS_TEST_MATRIX.json').read_bytes())['cases']}
    need(len(original)==131 and set(data['case_links'])==CASE_IDS and not data['original_cases_accepted'] and not data['physical_qualified'] and not data['release_qualified'] and all(original[k]['required_assertion']==v['required_assertion'] and v['acceptance']=='PARTIAL_CANDIDATE_ONLY' for k,v in data['case_links'].items()),'UI03_ORIGINAL_MAPPING_DRIFT')

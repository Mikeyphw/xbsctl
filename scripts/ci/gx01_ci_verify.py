#!/usr/bin/env python3
"""Independent fail-closed GX01 hosted content auditor; no network/device/Gradle."""
from __future__ import annotations
import sys,json,hashlib,pathlib,subprocess
from gx01_core import Refusal,require,original_invariants,assert_reconciled,jload,validate_observation

def audit(repo):
    repo=pathlib.Path(repo)
    handoffroot=repo/'docs/engineering/gx01_handoff'
    handoff={str(p.relative_to(handoffroot)):p.read_bytes() for p in handoffroot.rglob('*') if p.is_file()}
    old,new,oldm,newm,snapshot,gx=original_invariants(handoff)
    obs=jload((repo/'docs/engineering/XBS_GX01_GITHUB_OBSERVATION.json').read_bytes())
    candidates=jload((repo/'docs/engineering/XBS_GX01_METHOD_CANDIDATES.json').read_bytes())
    validate_observation(obs,snapshot,candidates)
    result_l=jload((repo/'docs/engineering/XBS_IMPLEMENTATION_LEDGER.json').read_bytes())
    result_m=jload((repo/'docs/engineering/XBS_TEST_MATRIX.json').read_bytes())
    assert_reconciled(result_l,result_m,old,oldm,obs)
    rec=jload((repo/'docs/engineering/XBS_GX01_RECONCILIATION_RECEIPT.json').read_bytes())
    arch=jload((repo/'docs/engineering/XBS_GX01_ARCHIVED_LEGACY_EXCLUSIONS.json').read_bytes())
    trk=jload((repo/'docs/engineering/XBS_GX_TRACKER.json').read_bytes())
    require(rec['original_tasks']==178 and rec['original_cases']==131 and rec['original_findings']==26,'RECEIPT_COUNTS_FALSE')
    require(rec['tasks_unaccepted']==178 and rec['cases_unmapped']==131 and rec['original_gates_accepted']==0,'RECEIPT_FALSE_CLAIM')
    require(rec['current_full_ci']=='NOT_RUN_FOR_GX01_HEAD','GX01_HOSTED_FALSE_CLAIM')
    require(rec['case_candidate_links']==len(candidates['case_links']),'CASE_LINK_COUNT_FALSE')
    require(arch['phase31']['credit_for_original_cases']==0 and arch['phase31']['credit_for_signed_release']==0 and
            arch['phase31']['state']=='ARCHIVED_LEGACY_NOT_NEW_RELEASE_PROOF','STALE_PHASE31_RELEASE_CREDIT')
    require(trk['side_milestones'][0]['status']=='IMPLEMENTED_LOCAL_HOSTED_PENDING' and
          all(x['status']=='PLANNED' for x in trk['side_milestones'][1:]),'SIDE_ROADMAP_INAPPROPRIATELY_ADVANCED')
    require(not rec['physical_root'] and not rec['signed_release'],'FALSE_ROOT_RELEASE')
    require(rec['source']['stack_sha']==obs['stack_head_sha'],'RECEIPT_SOURCE_DRIFT')
    # Verify exact Git blob IDs of the six inspected named methods, against source at checkout.
    for item in candidates['methods']:
        proc=subprocess.run(['git','-C',str(repo),'rev-parse','HEAD:'+item['path']],capture_output=True,text=True)
        if proc.returncode==0:
            require(proc.stdout.strip()==item['blob_sha'],'METHOD_SOURCE_BLOB_CHANGED '+item['path'])
        else:
            # No fake success when invoked on exported source lacking Git history.
            require(False,'METHOD_BLOB_UNVERIFIABLE '+item['path'])
    # Insertion of explicit mandatory workflow gate must exist exactly once.
    wf=(repo/'.github/workflows/devtool-xbs-ci.yml').read_text()
    needle='- name: GX01 independent ledger audit and adversarial regressions'
    require(wf.count(needle)==1,'GX01_HOSTED_JOB_MISSING_OR_DUPLICATE')
    require('python3 -B scripts/ci/test_gx01_reconciliation.py' in wf,'GX01_HOSTED_ACTUAL_TEST_MISSING')
    return rec

if __name__=='__main__':
    try:
        rec=audit(sys.argv[1] if len(sys.argv)>1 else '.')
        print('XBS_GX01_INDEPENDENT_AUDIT_PASS tasks=178 cases=131 findings=26 original_PASS=0')
    except (Refusal,OSError,ValueError,KeyError) as e:
        print('XBS_GX01_INDEPENDENT_AUDIT_REFUSED',str(e),file=sys.stderr)
        sys.exit(2)

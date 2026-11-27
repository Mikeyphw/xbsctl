"""XBSctl GX01: conservative, source-pinned, append-only evidence reconciliation.

Pure stdlib core, shared by the zipapp and independent hosted CI auditor.
Never performs Git, network, Gradle, ADB, installs, or root writes.
"""
from __future__ import annotations
import collections
import copy
import hashlib
import json
import re
from pathlib import Path

class Refusal(RuntimeError):
    pass

def require(condition, reason):
    if not condition:
        raise Refusal(reason)

def sha(b):
    return hashlib.sha256(b).hexdigest()

def jload(raw):
    return json.loads(raw)

def canonical(data):
    return (json.dumps(data, ensure_ascii=False, indent=2, sort_keys=False) + '\n').encode()

def original_tasks(ledger):
    return {t['id']:t for p in ledger['phases'] for t in p['tasks']}

def case_index(matrix):
    return {c['id']:c for c in matrix['cases']}

def original_invariants(handoff):
    manifest=jload(handoff['MANIFEST_SHA256.json'])
    require(set(manifest['file_sha256'])==set(handoff)-{'MANIFEST_SHA256.json'},'HANDOFF_FILE_SET_DRIFT')
    for name,expected in manifest['file_sha256'].items():
        require(sha(handoff[name])==expected,'HANDOFF_HASH_MISMATCH '+name)
    old=jload(handoff['archive/v2_original/XBS_IMPLEMENTATION_LEDGER.json'])
    new=jload(handoff['XBS_IMPLEMENTATION_LEDGER_v3.json'])
    oldm=jload(handoff['archive/v2_original/XBS_TEST_MATRIX.json'])
    newm=jload(handoff['XBS_TEST_MATRIX_v3.json'])
    original_roadmap=handoff['archive/v2_original/XBS_FINAL_ROADMAP_v2.md']
    snapshot=jload(handoff['XBS_PROGRESS_SNAPSHOT_20261114.json'])
    gx=jload(handoff['XBS_GX_TRACKER_v1.json'])
    require(sha(original_roadmap)==snapshot['original_roadmap_sha256'],'ORIGINAL_ROADMAP_MUTATED')
    require(len(old['phases'])==len(new['phases'])==14,'PHASE_COUNT_DRIFT')
    require([p['id'] for p in new['phases']]==['R%02d'%n for n in range(14)],'PHASE_IDS_DRIFT')
    ot,nt=original_tasks(old),original_tasks(new)
    require(len(ot)==len(nt)==178,'ORIGINAL_TASK_ID_COUNT_DRIFT')
    require(set(ot)==set(nt),'ORIGINAL_TASK_ID_SET_DRIFT')
    for k in ot:
        require((ot[k]['title'],ot[k]['acceptance'],ot[k]['acceptance_gate'])==
                (nt[k]['title'],nt[k]['acceptance'],nt[k]['acceptance_gate']),'TASK_CRITERION_DRIFT '+k)
    for p,q in zip(old['phases'],new['phases']):
        require((p['id'],p['gate'],p['depends_on'])==(q['id'],q['gate'],q['depends_on']), 'PHASE_GATE_DRIFT '+p['id'])
    oc,nc=case_index(oldm),case_index(newm)
    require(len(oc)==len(nc)==131 and set(oc)==set(nc),'ORIGINAL_CASES_DRIFT')
    for k in oc:
        keys=('suite','title','fixture','required_assertion','phases','lane')
        require(all(oc[k][key]==nc[k][key] for key in keys),'ORIGINAL_CASE_ASSERTION_DRIFT '+k)
    require(len(old['findings'])==len(new['findings'])==26,'ORIGINAL_26_FINDINGS_DRIFT')
    require([x['id'] for x in old['findings']]==[x['id'] for x in new['findings']],'FINDING_IDS_DRIFT')
    require(len(gx['side_milestones'])==6 and sum(len(x['tasks']) for x in gx['side_milestones'])==40, 'GX_INVENTORY_DRIFT')
    require(all(x['id']=='GX%02d'%(i+1) for i,x in enumerate(gx['side_milestones'])),'GX_ORDER_DRIFT')
    require(all(t['status']!='ACCEPTED' for t in nt.values()),'UNSUPPORTED_TASK_ACCEPTANCE')
    require(all(c['status']=='NOT_RUN' for c in nc.values()),'UNSUPPORTED_CASE_PASS')
    return old,new,oldm,newm,snapshot,gx

def validate_observation(obs, snapshot, candidates):
    require(obs['repository']=='Mikeyphw/xbsctl','REPO_DRIFT')
    require(obs['stack_head_sha']==snapshot['implementation_stack_sha'],'STACK_HEAD_DRIFT')
    require(obs['main_sha']==snapshot['main_sha'],'MAIN_HEAD_DRIFT')
    history=obs['history']; require(len(history)==10,'HISTORICAL_RUN_COUNT_DRIFT')
    expected=snapshot['hosted_runs_exact_sha']
    for n,(h,e) in enumerate(zip(history,expected)):
        require(h['pr_number']==e['pr_number']==n+17 and h['run_id']==e['run_id'] and
                h['run_attempt']==e['attempt'] and h['head_sha']==e['source_sha'] and
                h['run_conclusion']=='success' and h['run_status']=='completed', 'HISTORICAL_SOURCE_RUN_DRIFT')
    run=obs['latest_run']; require(run['head_sha']==obs['stack_head_sha'] and run['run_id']==43650947189 and
        run['attempt']==1 and run['status']=='completed' and run['conclusion']=='success','LATEST_RUN_DRIFT')
    jobs={x['name']:x for x in run['jobs']}
    require(all(jobs[n]['conclusion']=='success' for n in ['go-contract','android-companion-jvm','android-companion-api36']), 'MISSING_REQUIRED_GREEN_JOBS')
    require(jobs['android-companion-debug']['conclusion']=='skipped','SKIPPED_JOB_STATUS_CHANGED')
    for a in run['artifacts']:
        require(re.fullmatch('[0-9a-f]{64}',a['reported_digest_sha256']) is not None,'ARTIFACT_DIGEST_METADATA_BAD')
    methods={x['name']:x for x in candidates['methods']}
    require(len(methods)==len(candidates['methods']) and len(methods)>=6,'METHOD_INVENTORY_DUPLICATE_OR_MISSING')
    verified_r09f=[x for x in methods.values() if x.get('subtests') is not None]
    require(len(verified_r09f)==6 and sum(x['subtests'] for x in verified_r09f)==88,'R09F_METHOD_INVENTORY_DRIFT')
    require(all(re.fullmatch('[0-9a-f]{40}',x['blob_sha']) and x['path'] and x.get('job') in
       ('go-contract','android-companion-api36','android-companion-jvm') for x in methods.values()),'SOURCE_METHOD_PIN_INVALID')
    assertnames={c['method'] for c in candidates['case_links']}
    require(assertnames.issubset(methods),'CANDIDATE_METHOD_UNKNOWN')
    for c in candidates['case_links']:
        m=methods[c['method']]
        require(c['source_blob_sha']==m['blob_sha'] and c['source_path']==m['path'] and
             c['source_head_sha']==obs['stack_head_sha'] and c['run_id']==run['run_id'] and
             c['job']==m['job'] and c['test_id']==m['test_id'] and
             jobs[c['job']]['conclusion']=='success' and c['verdict']=='UNMAPPED_NOT_QUALIFIED',
             'CANDIDATE_PIN_OR_QUALIFICATION_DRIFT')

def _unique_merge(old,extra):
    out=copy.deepcopy(old)
    for v in extra:
        if v not in out:out.append(copy.deepcopy(v))
    return out

def _ensure_live_identity(live_l, live_m, old_l, old_m):
    old=original_tasks(old_l); live=original_tasks(live_l)
    require(set(old)==set(live),'LIVE_TASK_ID_DRIFT')
    for key in old:
        require(all(old[key][f]==live[key][f] for f in ('title','acceptance','acceptance_gate')),'LIVE_TASK_CRITERION_DRIFT '+key)
    for p,q in zip(old_l['phases'],live_l['phases']):
        require((p['id'],p['gate'])==(q['id'],q['gate']),'LIVE_GATE_DRIFT')
    oc,lc=case_index(old_m),case_index(live_m)
    require(set(oc)==set(lc),'LIVE_CASE_IDS_DRIFT')
    for k in oc:
        require(all(oc[k][f]==lc[k][f] for f in ('title','required_assertion','fixture','lane','phases')),'LIVE_CASE_ASSERTION_DRIFT '+k)
    require([f['id'] for f in old_l['findings']]==[f['id'] for f in live_l['findings']],'LIVE_FINDINGS_DRIFT')

def reconcile(handoff, observation, candidates, live_ledger_bytes, live_matrix_bytes):
    old_l,new_l,old_m,new_m,snapshot,gx=original_invariants(handoff)
    validate_observation(observation,snapshot,candidates)
    live_l=jload(live_ledger_bytes);live_m=jload(live_matrix_bytes)
    _ensure_live_identity(live_l,live_m,old_l,old_m)
    baseline_tasks=original_tasks(new_l);livemap=original_tasks(live_l)
    baseline_cases=case_index(new_m);livecases=case_index(live_m)
    # All source authority fields in the base live copy survive. Snapshot supplies only conservative tracking updates.
    result_l=copy.deepcopy(live_l)
    result_m=copy.deepcopy(live_m)
    result_tasks=original_tasks(result_l);result_cases=case_index(result_m)
    for task_id,t in result_tasks.items():
        history=baseline_tasks[task_id];orig=livemap[task_id]
        require(orig['status']!='ACCEPTED','LIVE_ACCEPTED_CONFLICT_REQUIRES_MANUAL_REVIEW '+task_id)
        require(orig.get('implementation_sha') in (None,history.get('implementation_sha')),
                'LIVE_INTRODUCING_SHA_CONFLICT '+task_id)
        # Only update if corroborated by snapshot progress (does NOT accept original task).
        t['status']=history['status']
        if t['status']=='HOSTED_VERIFIED' and not history.get('evidence') and not history.get('test_refs'):
            t['status']='IN_PROGRESS'  # GX01 truth downgrade: no source-bound per-task test receipt
        t['evidence']=_unique_merge(orig.get('evidence',[]),history.get('evidence',[]))
        t['blockers']=_unique_merge(orig.get('blockers',[]),history.get('blockers',[]))
        t['test_refs']=_unique_merge(orig.get('test_refs',[]),history.get('test_refs',[]))
        for key,val in history.items():
            if key not in t:t[key]=copy.deepcopy(val)
        t['implementation_sha']=orig.get('implementation_sha') or history.get('implementation_sha')
        t['observed_cumulative_sha']=observation['stack_head_sha'] if t['status']!='NOT_STARTED' else None
        t['acceptance_status']='OPEN_PENDING_ORIGINAL_GATE_AND_ASSERTION_MAPPING'
        t['gx01_reconciliation']={
            'status':'OPEN_EVIDENCE_GAP',
            'handoff_prior_tracking_status':history['status'],
            'tracking_status':t['status'],
            'downgraded_unmapped_hosted_claim':history['status']=='HOSTED_VERIFIED' and t['status']!='HOSTED_VERIFIED',
            'introducing_commit_sha':t['implementation_sha'],
            'observed_cumulative_sha':t['observed_cumulative_sha'],
            'evidence_lane':'HOSTED_GO_JVM_API36_CUMULATIVE_ONLY' if t['status']!='NOT_STARTED' else 'UNVERIFIED',
            'source_bound_task_test_evidence':[],
            'blockers':(['INTRODUCING_SHA_UNMAPPED'] if not t['implementation_sha'] else []) +
                ['CASE_ASSERTION_MAPPING_INCOMPLETE','ORIGINAL_PHASE_GATE_NOT_ACCEPTED'],
            'accepted':False,
        }
    links_by_case=collections.defaultdict(list)
    for link in candidates['case_links']:links_by_case[link['case_id']].append(link)
    require(all(k in result_cases for k in links_by_case),'UNKNOWN_CANDIDATE_CASE')
    for cid,c in result_cases.items():
        orig=livecases[cid]; b=baseline_cases[cid]
        require(orig['status'] not in ('PASS_AT_EXACT_SHA','ACCEPTED'),'LIVE_TEST_PASS_CONFLICT_REQUIRES_MANUAL_REVIEW '+cid)
        c['status']='NOT_RUN'
        c['evidence']=_unique_merge(orig.get('evidence',[]),b.get('evidence',[]))
        c['run_id']=None;c['attempt']=None;c['source_sha']=None
        c['observed_assertions']=None;c['observed_test_count']=None
        c['reconciliation_status']='UNMAPPED_ORIGINAL_ASSERTIONS_NOT_YET_QUALIFIED'
        c['acceptance_note']='NOT_RUN is original-case mapping status, not a denial that hosted tests executed.'
        c['gx01_reconciliation']={
            'mapping_status':'UNMAPPED',
            'assertion_qualified':False,
            'required_assertion':c['required_assertion'],
            'candidate_named_executions':copy.deepcopy(links_by_case.get(cid,[])),
            'exclusion':'Partial/rejected candidate evidence cannot satisfy the complete original assertion; no case PASS or acceptance.',
            'missing':['COMPLETE_ASSERTION_TO_METHOD_TRACE','EXACT_EXECUTION_ASSERTION_RECEIPT','INDEPENDENT_CASE_AUDIT'],
        }
    for phase, snapshot_phase in zip(result_l['phases'],new_l['phases']):
        require(phase['id']==snapshot_phase['id'],'LIVE_PHASE_ID_CHANGED')
        phase['status']=snapshot_phase['status']
        phase['evidence']=_unique_merge(phase.get('evidence',[]),snapshot_phase.get('evidence',[]))
        phase['original_gate_status']='NOT_ACCEPTED'
    result_l['handoff_revision']=copy.deepcopy(new_l['handoff_revision'])
    result_l['gx01_reconciliation']={
        'schema':'xbs-gx01-living-ledger/v1','state':'IMPLEMENTED_LOCAL_HOSTED_GATE_PENDING',
        'source':copy.deepcopy(observation),'original_task_count':178,'original_case_count':131,
        'task_status_counts':dict(collections.Counter(t['status'] for t in result_tasks.values())),
        'original_task_accepted':0,'original_case_pass':0,'original_gate_accepted':0,
        'introducing_sha_missing':sum(t['implementation_sha'] is None for t in result_tasks.values()),
        'prior_handoff_hosted_verified_workstreams_downgraded_to_in_progress':sum(
           t['gx01_reconciliation']['downgraded_unmapped_hosted_claim'] for t in result_tasks.values()),
        'rule':'Unlinked HOSTED_VERIFIED handoff status downgraded to IN_PROGRESS, with prior status retained; no physical or release qualification',
    }
    result_m['handoff_revision']=copy.deepcopy(new_m['handoff_revision'])
    result_m['status']='GX01_RECONCILED_OPEN_CASES'
    result_m['gx01_reconciliation']={'schema':'xbs-gx01-test-evidence/v1','original_cases':131,
        'accepted_cases':0,'unmapped_cases':131,'candidate_links':len(candidates['case_links']),
        'candidate_cases':len(links_by_case),'source_sha':observation['stack_head_sha'],
        'original_matrix_preserved':True}
    result_l['findings']=copy.deepcopy(live_l['findings'])
    bfind={f['id']:f for f in new_l['findings']}
    for finding in result_l['findings']:
        fid=finding['id'];base=bfind[fid]
        require(finding['finding']==base['finding'] and finding['severity']==base['severity'],
                'FINDING_CONTENT_DRIFT '+fid)
        # No direct exact finding closure proving the whole original issue was supplied.
        require(finding['resolution'] not in ('CLOSED','ACCEPTED','FIXED_VERIFIED'),
                'LIVE_FINDING_RESOLUTION_CONFLICT '+fid)
        finding['closing_evidence']=_unique_merge(finding.get('closing_evidence',[]),base.get('closing_evidence',[]))
        finding['gx01_review']={'classification':'OPEN','review_result':'NO_FULLY_TRACED_CLOSURE',
              'source_path':finding.get('source_path'), 'observed_cumulative_sha':observation['stack_head_sha'],
              'missing':'introducing/remediation commit, dedicated named assertion, independent closure receipt',
              'historical_resolution_preserved':finding['resolution'],'not_accepted':True}
    result_l['retired_audit_claims']=_unique_merge(result_l.get('retired_audit_claims',[]),new_l.get('retired_audit_claims',[]))
    result_l['change_history']=_unique_merge(result_l.get('change_history',[]),new_l.get('change_history',[]))
    result_l['change_history'].append({'date':'2026-11-14','actor':'GX01 deterministic overlay',
         'change':'Reconciled 178 original tasks, 131 unmapped original test cases, 26 OPEN closure audits. No invented PASS/physical/release; exact source pins from 2026-11-14/15 GitHub observation.'})
    assert_reconciled(result_l,result_m,old_l,old_m,observation)
    return result_l,result_m

def assert_reconciled(l,m,old_l,old_m,obs):
    ot=original_tasks(old_l); tasks=original_tasks(l)
    oc=case_index(old_m);cases=case_index(m)
    require(set(tasks)==set(ot) and len(tasks)==178,'VALIDATOR_TASK_MISSING_OR_DUPLICATE')
    require(set(cases)==set(oc) and len(cases)==131,'VALIDATOR_CASE_MISSING_OR_DUPLICATE')
    for k,t in tasks.items():
        require((t['title'],t['acceptance'],t['acceptance_gate'])==
                (ot[k]['title'],ot[k]['acceptance'],ot[k]['acceptance_gate']),'VALIDATOR_TASK_CRITERIA_DRIFT '+k)
        require(t['status']!='ACCEPTED' and not t['gx01_reconciliation']['accepted'],'VALIDATOR_FABRICATED_ACCEPTANCE '+k)
        require(t['implementation_sha'] is None or re.fullmatch('[0-9a-f]{40}',t['implementation_sha']),
                'INTRODUCING_SHA_INVALID '+k)
        require(not t['gx01_reconciliation']['source_bound_task_test_evidence'],'UNCHECKED_TASK_EVIDENCE '+k)
    for k,c in cases.items():
        require(all(c[v]==oc[k][v] for v in ('title','fixture','required_assertion','lane','phases')),
                'VALIDATOR_CASE_REQUIREMENT_DRIFT '+k)
        require(c['status']=='NOT_RUN' and c['gx01_reconciliation']['mapping_status']=='UNMAPPED',
                'VALIDATOR_FABRICATED_CASE_PASS '+k)
        require(not c['gx01_reconciliation']['assertion_qualified'],'VALIDATOR_FALSE_ASSERTION '+k)
        for x in c['gx01_reconciliation']['candidate_named_executions']:
            require(x['run_id']==43650947189 and x['source_head_sha']==obs['stack_head_sha'] and
                    x['verdict']=='UNMAPPED_NOT_QUALIFIED','VALIDATOR_CANDIDATE_DRIFT '+k)
    require(len(l['findings'])==26 and [f['id'] for f in l['findings']]==[f['id'] for f in old_l['findings']]
            and all(f['gx01_review']['classification']=='OPEN' for f in l['findings']),
            'VALIDATOR_FINDING_CLOSURE_INVENTED')
    require(all(p['original_gate_status']=='NOT_ACCEPTED' for p in l['phases']),'ORIGINAL_GATE_FALSE_ACCEPT')
    require(l['gx01_reconciliation']['original_task_accepted']==0 and
            l['gx01_reconciliation']['original_case_pass']==0,'ACCEPTED_COUNTER_MISMATCH')
    require(m['gx01_reconciliation']['unmapped_cases']==131,'UNMAPPED_COUNTER_DRIFT')

def render(ledger,matrix,handoff,obs,candidates):
    old_l=jload(handoff['archive/v2_original/XBS_IMPLEMENTATION_LEDGER.json'])
    old_m=jload(handoff['archive/v2_original/XBS_TEST_MATRIX.json'])
    assert_reconciled(ledger,matrix,old_l,old_m,obs)
    tasks=original_tasks(ledger);cases=case_index(matrix)
    ccounts=collections.Counter(t['status'] for t in tasks.values())
    report={'schema':'xbs-gx01-reconciliation-receipt/v1','classification':'LOCAL_IMPLEMENTATION_VERIFIED_NOT_HOSTED_GX01_ACCEPTED',
        'source':{'repo':obs['repository'],'main_sha':obs['main_sha'],'stack_sha':obs['stack_head_sha'],
           'stack_branch':obs['stack_branch'],'stack_base_sha':obs['stack_base_sha'],'pr_number':26,
           'prior_verified_run':43650947189,'prior_verified_attempt':1,'prior_workflow':'Devtool XBS Go and Android candidates'},
        'original_tasks':178,'original_cases':131,'original_findings':26,'side_milestones':6,
        'task_status_counts':dict(ccounts),'tasks_unaccepted':178,'cases_unmapped':131,
        'case_candidate_links':len(candidates['case_links']),
        'case_candidate_ids':sorted({x['case_id'] for x in candidates['case_links']}),
        'findings_open_closure':26,'original_gates_accepted':0,'physical_root':False,'signed_release':False,
        'historical_phase31_proof_credit':0,'current_full_ci':'NOT_RUN_FOR_GX01_HEAD',
        'artifact_digests':'METADATA_ONLY_NOT_BYTE_REHASH','claim':'This receipt reports local evidence reconciliation, not completed GX01 hosted qualification.'}
    out={
      'docs/engineering/XBS_IMPLEMENTATION_LEDGER.json':canonical(ledger),
      'docs/engineering/XBS_TEST_MATRIX.json':canonical(matrix),
      'docs/engineering/XBS_GX01_RECONCILIATION_RECEIPT.json':canonical(report),
      'docs/engineering/XBS_GX01_GITHUB_OBSERVATION.json':canonical(obs),
      'docs/engineering/XBS_GX01_METHOD_CANDIDATES.json':canonical(candidates),
    }
    header=['# GX01 — original roadmap task reconciliation','',
       '**Local status:** implemented and independently validated; new GX01 hosted CI not yet executed.','',
       f'Original tasks: **178** · status counts: `{dict(ccounts)}` · accepted: **0**.',
       'Original phase acceptance gates remain independent; observed cumulative HEAD is not an introducing commit.',
       'The seven formerly HOSTED_VERIFIED original workstreams have no original-task-level named receipt in the handoff: downgraded to IN_PROGRESS, with history preserved.',
       '', '| Task | Original acceptance requirement | Tracking | Introducing commit | Original gate | GX01 closure |',
       '|---|---|---|---|---|---|']
    for id,t in sorted(tasks.items()):
        header.append(f"| {id} | {t['acceptance'].replace('|','/')} | {t['status']} | {t['implementation_sha'] or 'UNMAPPED'} | {t['acceptance_gate']} | OPEN |")
    out['docs/engineering/XBS_GX01_TASK_LEDGER.md']='\n'.join(header).encode()+b'\n'
    lines=['# GX01 — original 131 test cases: assertion-level inventory','',
      '**Important:** successful hosted workflow jobs do not establish the whole original assertions. All 131 remain **UNMAPPED/NOT_RUN** at case level.','',
      f"Verified R09F Go method inventory: six named roots / 88 subtests; {len(candidates['methods'])} source-pinned methods across jobs; {len(candidates['case_links'])} partial candidate references for {len(report['case_candidate_ids'])} cases.",
      'Method source traces are tied to PR #26 exact SHA, green Go/JVM/API36 jobs and run 43650947189 attempt 1; only R09F has independent named root/subtest count. Individual other-method XML was not inspected, and original cases are not marked PASS.',
      '', '| Case | Required assertion | Lane | Case status | Named candidate methods (partial only) |',
      '|---|---|---|---|---|']
    for id,c in sorted(cases.items()):
        links=c['gx01_reconciliation']['candidate_named_executions']
        methods=', '.join(x['method'] for x in links) if links else 'UNMAPPED'
        lines.append(f"| {id} | {c['required_assertion'].replace('|','/')} | {c['lane']} | NOT_RUN | {methods} |")
    out['docs/engineering/XBS_GX01_TEST_CASE_EVIDENCE.md']='\n'.join(lines).encode()+b'\n'
    findings=['# GX01 — 26 audited findings','',
      'No finding was promoted to FIXED or ACCEPTED without exact implementing commit, named assertion and independent closure receipt. Historical resolutions remain unmodified.',
      '', '| Finding | Severity | Original issue | Source path | Closure |', '|---|---|---|---|---|']
    for f in ledger['findings']:
        findings.append(f"| {f['id']} | {f['severity']} | {f['finding'].replace('|','/')} | `{f.get('source_path') or 'UNVERIFIED'}` | OPEN |")
    out['docs/engineering/XBS_GX01_AUDIT_FINDINGS.md']='\n'.join(findings).encode()+b'\n'
    archive={'schema':'xbs-gx01-legacy-exclusions/v1','phase31':{
       'historic_reported_files':918,'historic_reported_contracts':237,'state':'ARCHIVED_LEGACY_NOT_NEW_RELEASE_PROOF',
       'original_source':'archive/v2_original/', 'credit_for_original_cases':0,
       'credit_for_physical':0,'credit_for_signed_release':0,'counts_are_historical_claims_not_revalidated':True},
       'retired_audit_claims':ledger['retired_audit_claims']}
    out['docs/engineering/XBS_GX01_ARCHIVED_LEGACY_EXCLUSIONS.json']=canonical(archive)
    tracker=jload(handoff['XBS_GX_TRACKER_v1.json'])
    tracker['status']='GX01_LOCAL_IMPLEMENTED_HOSTED_PENDING'
    tracker['side_milestones'][0]['status']='IMPLEMENTED_LOCAL_HOSTED_PENDING'
    for t in tracker['side_milestones'][0]['tasks']:
        t['status']='IN_PROGRESS'
        t['gx01_note']='Source-pinned local reconciler implemented; full CI exit gate and complete original evidence still pending.'
    out['docs/engineering/XBS_GX_TRACKER.json']=canonical(tracker)
    handback=['# GX01 evidence-first handoff — preserve GX02–GX06 and original R00–R13','',
      '**Current:** GX01 source-pinned local overlay validates 178/131/26; actual new hosted CI pending. No original phase accepted.','',
      '**Next:** close exact per-case gaps through newly executed, assertion-mapped test receipts; proceed GX02 only after GX01 acceptance evidence.','',
      '## Remaining side roadmap (authoritative, not renumbered)','',
      handoff['XBS_GAP_CLOSURE_SIDE_ROADMAP_v1.md'].decode(),'',
      '## Original canonical roadmap (unchanged archived bytes)','',
      handoff['archive/v2_original/XBS_FINAL_ROADMAP_v2.md'].decode(),'',
      '## Ledger reconciliation protocol','',handoff['XBS_LEDGER_RECONCILIATION_PROTOCOL.md'].decode()]
    out['docs/engineering/XBS_GX01_REMAINING_ROADMAP_HANDOFF.md']='\n'.join(handback).encode()+b'\n'
    return out

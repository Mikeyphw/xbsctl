#!/usr/bin/env python3
"""Read-only handoff validation: 14/178/131, source evidence, checksums."""
import argparse, collections, hashlib, json, pathlib, sys, zipfile


def report(ok, label):
    print(('PASS ' if ok else 'REFUSED ') + label)
    if not ok: raise SystemExit(2)

def main():
    parser=argparse.ArgumentParser(description='XBS handoff v3 integrity + progress validation (read-only)')
    group=parser.add_mutually_exclusive_group()
    group.add_argument('--check',action='store_true',help='validate every manifest hash and invariant')
    group.add_argument('--overview',action='store_true',help='print side-roadmap and progress counts')
    args=parser.parse_args()
    src=pathlib.Path(sys.argv[0]).resolve()
    if src.suffix=='.zip':
        with zipfile.ZipFile(src) as z: data={x.filename:z.read(x) for x in z.infolist() if not x.is_dir()}
    else:
        root=pathlib.Path(__file__).resolve().parent
        data={str(x.relative_to(root)):x.read_bytes() for x in root.rglob('*') if x.is_file()}
    needed=['README_FIRST.md','MANIFEST_SHA256.json','XBS_GAP_CLOSURE_SIDE_ROADMAP_v1.md','XBS_FINAL_ROADMAP_v3.md',
      'XBS_IMPLEMENTATION_LEDGER_v3.json','XBS_TEST_MATRIX_v3.json','XBS_GX_TRACKER_v1.json',
      'XBS_PROGRESS_SNAPSHOT_20261114.json','archive/v2_original/XBS_FINAL_ROADMAP_v2.md']
    report(all(x in data for x in needed),'required files present')
    manifest=json.loads(data['MANIFEST_SHA256.json'])
    report(set(manifest['file_sha256'])==set(data)-{'MANIFEST_SHA256.json'},'manifest has exact file inventory')
    report(all(hashlib.sha256(data[p]).hexdigest()==v for p,v in manifest['file_sha256'].items()),'all content hashes match')
    L=json.loads(data['XBS_IMPLEMENTATION_LEDGER_v3.json'])
    M=json.loads(data['XBS_TEST_MATRIX_v3.json'])
    G=json.loads(data['XBS_GX_TRACKER_v1.json'])
    S=json.loads(data['XBS_PROGRESS_SNAPSHOT_20261114.json'])
    tasks=[t for phase in L['phases'] for t in phase['tasks']]
    report(len(L['phases'])==14 and [p['id'] for p in L['phases']]==['R%02d'%i for i in range(14)],'original 14 phases unchanged')
    report(len(tasks)==178 and len(set(t['id'] for t in tasks))==178,'all 178 unique original tasks retained')
    original_L=json.loads(data['archive/v2_original/XBS_IMPLEMENTATION_LEDGER.json'])
    oldtasks={t['id']:(t['title'],t['acceptance']) for p in original_L['phases'] for t in p['tasks']}
    newtasks={t['id']:(t['title'],t['acceptance']) for p in L['phases'] for t in p['tasks']}
    report(oldtasks==newtasks and all((o['id'],o['gate'])==(n['id'],n['gate']) for o,n in zip(original_L['phases'],L['phases'])),'all original task criteria and gates unchanged')
    oldmatrix=json.loads(data['archive/v2_original/XBS_TEST_MATRIX.json'])
    oldcases={t['id']:(t['suite'],t['required_assertion'],t['fixture'],t['lane'],t['phases']) for t in oldmatrix['cases']}
    newcases={t['id']:(t['suite'],t['required_assertion'],t['fixture'],t['lane'],t['phases']) for t in M['cases']}
    report(oldcases==newcases,'all 131 original case assertions, fixtures and lanes unchanged')
    report(hashlib.sha256(data['archive/v2_original/XBS_FINAL_ROADMAP_v2.md']).hexdigest()==S['original_roadmap_sha256'],'original roadmap bytes match snapshot pin')
    report(len(M['cases'])==131 and len({t['id'] for t in M['cases']})==131,'all 131 original test cases retained')
    report(len(G['side_milestones'])==6 and sum(len(p['tasks']) for p in G['side_milestones'])==40,'GX01-GX06 side task inventory')
    report(all(t['status']=='NOT_RUN' for t in M['cases']),'no fabricated original test case PASS')
    report(all(t['status'] != 'ACCEPTED' for t in tasks),'no fabricated original task ACCEPTED')
    report(all(p['original_gate_status']=='NOT_ACCEPTED' for p in L['phases']),'no fabricated phase acceptance')
    report(S['r09f_hosted']['source_sha'] if False else S['r09f_hosted']['sha']==L['handoff_revision']['source_stack_head_sha'],'exact R09F snapshot source match')
    report(not S['r09f_hosted']['physical_root_verified'] and not S['r09f_hosted']['release_signed'],'physical/release claims remain false')
    if args.overview:
        print('TRACK: GX01->GX02->GX03->GX04->GX05->GX06, then original R00-R13 residual gates')
        print('Original tasks:',dict(collections.Counter(t['status'] for t in tasks)))
        print('Original cases: 131 unmapped, 0 original case PASS')
        print('R09F run',S['r09f_hosted']['run_id'],'Go coverage',S['r09f_hosted']['full_internal_go_coverage_percent'])
    print('XBS_HANDOFF_V3_READ_ONLY_VERIFIED')

if __name__=='__main__':main()

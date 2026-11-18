#!/usr/bin/env python3
"""Read-only GH provenance and merge-review negative fixtures."""
import copy,json,unittest
from pathlib import Path
from gx06_live_audit import (REPO,CANONICAL,COMPAT,BASE,Refused,require_pr,require_run,
    require_jobs,require_artifacts,verify_historical_stack)

HERE=Path(__file__).resolve().parents[2]
LOCK=json.loads((HERE/'docs/engineering/XBS_GX06_STACK_LOCK.json').read_text())

class FakeClient:
    def __init__(self,lock):
        self.entries={}
        for p in lock['stack']:
            n=p['pr'];
            self.entries[BASE+'/pulls/'+str(n)]={'number':n,'state':'open','merged':False,'draft':n==32,'mergeable':True,
              'head':{'ref':p['branch'],'sha':p['head'],'repo':{'full_name':REPO}},
              'base':{'ref':p['base_branch'],'sha':p['base_sha']}}
            for row in p['runs']:
                rid=row['id'];attempt=1
                self.entries[BASE+'/actions/runs/'+str(rid)]={'id':rid,'head_sha':p['head'],
                    'head_branch':p['branch'],'workflow_id':row['workflow_id'],
                    'name':row['workflow'],'event':'pull_request','run_attempt':attempt,
                    'status':'completed','conclusion':'success'}
                jobs=({'go-contract','android-companion-jvm','android-companion-api36'}
                      if row['workflow']==CANONICAL else {'android-api26','android-api34'})
                self.entries[BASE+'/actions/runs/'+str(rid)+'/attempts/1/jobs?per_page=100']={'jobs':[
                    {'name':name,'status':'completed','conclusion':'success'} for name in sorted(jobs)]}
                self.entries[BASE+'/actions/runs/'+str(rid)+'/artifacts?per_page=100']={
                    'total_count':1,'artifacts':[{'id':rid,'name':'evidence-'+str(rid)+'-1','expired':False,
                             'size_in_bytes':1200,'digest':'sha256:'+format(rid,'064x')}]}
    def get(self,path):
        if path not in self.entries:raise Refused('MISSING_FAKE '+path)
        return copy.deepcopy(self.entries[path])

class LiveTests(unittest.TestCase):
    def setUp(self):
        self.lock=copy.deepcopy(LOCK);self.client=FakeClient(self.lock)
    def change_pr(self,number,key,value):
        self.client.entries[BASE+'/pulls/'+str(number)][key]=value
    def test_valid_historical(self):
        x=verify_historical_stack(self.lock,self.client)
        self.assertEqual([p['pr'] for p in x],[27,28,30,31,32]);self.assertEqual(len(x[-1]['runs']),2)
        self.assertFalse(x[-1]['runs'][0]['artifacts'][0]['download_sha256_verified'])
    def test_pr_closed(self):
        self.change_pr(32,'state','closed')
        with self.assertRaises(Refused):verify_historical_stack(self.lock,self.client)
    def test_pr_merged(self):
        self.change_pr(32,'merged',True)
        with self.assertRaises(Refused):verify_historical_stack(self.lock,self.client)
    def test_pr_provider_conflict(self):
        self.change_pr(32,'mergeable',False)
        with self.assertRaises(Refused):verify_historical_stack(self.lock,self.client)
    def test_pr_wrong_head(self):
        self.client.entries[BASE+'/pulls/31']['head']['sha']='f'*40
        with self.assertRaises(Refused):verify_historical_stack(self.lock,self.client)
    def test_pr_wrong_base(self):
        self.client.entries[BASE+'/pulls/31']['base']['sha']='f'*40
        with self.assertRaises(Refused):verify_historical_stack(self.lock,self.client)
    def test_pr_forked(self):
        self.client.entries[BASE+'/pulls/31']['head']['repo']['full_name']='bad/fork'
        with self.assertRaises(Refused):verify_historical_stack(self.lock,self.client)
    def test_wrong_pr_number(self):
        self.client.entries[BASE+'/pulls/32']['number']=31
        with self.assertRaises(Refused):verify_historical_stack(self.lock,self.client)
    def test_pr_not_open(self):
        self.change_pr(27,'state','closed')
        with self.assertRaises(Refused):verify_historical_stack(self.lock,self.client)
    def test_run_wrong_head(self):
        self.client.entries[BASE+'/actions/runs/43943686673']['head_sha']='0'*40
        with self.assertRaises(Refused):verify_historical_stack(self.lock,self.client)
    def test_run_fail(self):
        self.client.entries[BASE+'/actions/runs/43943686673']['conclusion']='failure'
        with self.assertRaises(Refused):verify_historical_stack(self.lock,self.client)
    def test_run_skipped(self):
        self.client.entries[BASE+'/actions/runs/43943686673']['conclusion']='skipped'
        with self.assertRaises(Refused):verify_historical_stack(self.lock,self.client)
    def test_run_wrong_attempt(self):
        self.client.entries[BASE+'/actions/runs/43943686673']['run_attempt']=0
        with self.assertRaises(Refused):verify_historical_stack(self.lock,self.client)
    def test_run_wrong_workflow_id(self):
        self.client.entries[BASE+'/actions/runs/43943686673']['workflow_id']=123
        with self.assertRaises(Refused):verify_historical_stack(self.lock,self.client)
    def test_run_wrong_event(self):
        self.client.entries[BASE+'/actions/runs/43943686673']['event']='workflow_dispatch'
        with self.assertRaises(Refused):verify_historical_stack(self.lock,self.client)
    def test_required_job_missing(self):
        with self.assertRaises(Refused):require_jobs({'jobs':[{'name':'go-contract','status':'completed','conclusion':'success'}]},
             {'go-contract','android-companion-jvm'})
    def test_required_job_failed(self):
        with self.assertRaises(Refused):require_jobs({'jobs':[{'name':'go-contract','status':'completed','conclusion':'failure'}]},
             {'go-contract'})
    def test_optional_debug_skipped_allowed(self):
        self.assertEqual(require_jobs({'jobs':[{'name':'go-contract','status':'completed','conclusion':'success'},
            {'name':'android-companion-debug','status':'completed','conclusion':'skipped'}]}, {'go-contract'}),['go-contract'])
    def test_duplicate_job(self):
        with self.assertRaises(Refused):require_jobs({'jobs':[{'name':'go-contract'}, {'name':'go-contract'}]}, {'go-contract'})
    def test_empty_artifacts(self):
        with self.assertRaises(Refused):require_artifacts({'total_count':0,'artifacts':[]},100,1)
    def test_missing_digest(self):
        d=self.client.entries[BASE+'/actions/runs/43943686673/artifacts?per_page=100'];d['artifacts'][0]['digest']=''
        with self.assertRaises(Refused):verify_historical_stack(self.lock,self.client)
    def test_expired_artifact(self):
        d=self.client.entries[BASE+'/actions/runs/43943686673/artifacts?per_page=100'];d['artifacts'][0]['expired']=True
        with self.assertRaises(Refused):verify_historical_stack(self.lock,self.client)
    def test_wrong_artifact_run(self):
        d=self.client.entries[BASE+'/actions/runs/43943686673/artifacts?per_page=100'];d['artifacts'][0]['name']='unbound-artifact'
        with self.assertRaises(Refused):verify_historical_stack(self.lock,self.client)
    def test_duplicate_artifact(self):
        d=self.client.entries[BASE+'/actions/runs/43943686673/artifacts?per_page=100'];d['artifacts'].append(copy.deepcopy(d['artifacts'][0]));d['total_count']=2
        with self.assertRaises(Refused):verify_historical_stack(self.lock,self.client)
    def test_zero_artifact_size(self):
        d=self.client.entries[BASE+'/actions/runs/43943686673/artifacts?per_page=100'];d['artifacts'][0]['size_in_bytes']=0
        with self.assertRaises(Refused):verify_historical_stack(self.lock,self.client)
    def test_paginated_artifacts(self):
        d=self.client.entries[BASE+'/actions/runs/43943686673/artifacts?per_page=100'];d['total_count']=3
        with self.assertRaises(Refused):verify_historical_stack(self.lock,self.client)
    def test_no_real_download_claim(self):
        x=verify_historical_stack(self.lock,self.client)
        self.assertFalse(any(a['download_sha256_verified'] for p in x for r in p['runs'] for a in r['artifacts']))

if __name__=='__main__':unittest.main()

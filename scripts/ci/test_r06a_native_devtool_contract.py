#!/usr/bin/env python3
"""R06A: enforce real native Go DAG, artifact safety and hosted Gradle boundary.

Host-only TOML and source policy tests. No Devtool installation, Gradle, ADB,
root, network, or physical device required. This is source-policy evidence;
actual native Devtool plan/preflight remain independently required for R06 signoff.
"""
from __future__ import annotations
from pathlib import Path
import re
import tomllib
import unittest

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / '.devtool.toml'
GO = ROOT / 'internal/module/module.go'
WORKFLOW = ROOT / '.github/workflows/devtool-xbs-ci.yml'

class ContractError(ValueError): pass

def require(ok: bool, msg: str) -> None:
    if not ok: raise ContractError(msg)

def audit(manifest: str, go_source: str, workflow: str) -> None:
    cfg = tomllib.loads(manifest)
    proj = cfg['project']; jobs = cfg['jobs']; targets = cfg['targets']; xbs = targets['xbs']
    require(proj['runner'] == 'go' and xbs['runner'] == 'go', 'GO_TARGET_NOT_NATIVE')
    require(proj['root'] == xbs['root'] == '.', 'GO_TARGET_ROOT_DRIFT')
    for key, action in (('xbs-go-test','test'), ('xbs-go-vet','lint')):
        job=jobs[key]
        require(job['runner'] == 'go' and job['root'] == '.' and job.get('action') == action,
                'NATIVE_GO_ACTION_MISSING_'+key)
        require(not any(k in job for k in ('command','shell_command','gradle_tasks')),
                'DUPLICATE_OR_LEGACY_GO_COMMAND_'+key)
    for key, cmd in (
        ('xbs-contract-check', ['go','run','./cmd/xbs','contracts','check']),
        ('xbs-final-seal', ['go','run','./cmd/xbsrepo','final-seal']),
        ('xbs-build-module', ['go','run','./cmd/xbsrepo','build-module','--out','dist/XtremeBS-go.zip']),
    ):
        job=jobs[key]
        require(job['runner']=='command' and job.get('command')==cmd and job.get('root')=='.',
                'XBS_CUSTOM_JOB_DRIFT_'+key)
    require(jobs['xbs-build-module'].get('outputs')==['dist/XtremeBS-go.zip'], 'MODULE_OUTPUT_DRIFT')
    workflows=xbs['workflows']
    module_steps = {'source':('job:xbs-go-mod-present',()),
                    'tests':('job:xbs-go-test',('source',)),
                    'vet':('job:xbs-go-vet',('source',)),
                    'contracts':('job:xbs-contract-check',('tests','vet')),
                    'seal':('job:xbs-final-seal',('contracts',)),
                    'module':('job:xbs-build-module',('seal',))}
    def graph(name, required):
        steps=workflows[name]
        require(isinstance(steps,list) and all(isinstance(x,dict) for x in steps), 'WORKFLOW_NOT_DAG_'+name)
        actual={}
        for entry in steps:
            nid=entry['id']
            require(nid not in actual, 'DUPLICATE_GRAPH_ID_'+name)
            require('depends_on' in entry and isinstance(entry['depends_on'],list), 'GRAPH_DEPENDENCY_MISSING_'+name)
            actual[nid]=(entry['ref'],tuple(entry['depends_on']))
        require(actual == required, 'DAG_WEAKENED_'+name)
        # Explicitly reject cycles even when missing/wrong steps would otherwise refuse.
        for node,(_,edges) in actual.items():
            for dep in edges: require(dep in actual and dep!=node, 'GRAPH_DEPENDENCY_INVALID_'+name)
    graph('ci-module',module_steps)
    graph('build',module_steps)
    graph('validate',{k:v for k,v in module_steps.items() if k!='module'})
    graph('ci-local',{k:v for k,v in module_steps.items() if k not in ('seal','module')})
    require('job:xbs-final-seal' in workflows['build'][4]['ref'], 'SEAL_NOT_REQUIRED')
    android=targets['xbs_android']; art=android['artifacts']
    require(android['root']=='android-app' and android['runner']=='android', 'ANDROID_TARGET_ROOT_DRIFT')
    require(art.get('required') is True and art.get('verify') is True,'ANDROID_ARTIFACT_OPTIONAL')
    require(art.get('paths')==['app/build/outputs/apk/debug/app-debug.apk'], 'ANDROID_APK_RELATIVE_PATH_WRONG')
    require(art.get('output_directory')=='.devtool/build-artifacts/xbs-companion/debug', 'ANDROID_ARTIFACT_DIRECTORY_DRIFT')
    inputs=android['inputs'];outputs=android['outputs']
    require('../.devtool.toml' in inputs and '../go.mod' in inputs and '../internal/**' in inputs,
            'ANDROID_CROSS_TARGET_OWNERSHIP_MISSING')
    require(all(not p.startswith('android-app/') for p in inputs+outputs+art['paths']),
            'ANDROID_DOUBLE_PREFIX')
    require('app/build/**' in outputs and 'benchmark/build/**' in outputs,
            'ANDROID_GENERATED_OUTPUTS_MISSING')
    require(xbs['inputs'] and xbs['outputs'] and 'module/**' in xbs['inputs'], 'GO_OWNERSHIP_MISSING')
    require('hasNativeGoTarget' in go_source and 'hasNativeGoTest' in go_source and
            'hasNativeGoVet' in go_source and 'hasNativeGoSeal' in go_source,
            'NATIVE_FINAL_SEAL_HANDOFF_MISSING')
    require('hasLegacyGoTest' in go_source and 'hasWorkflowGoTest' in go_source,
            'LEGACY_HANDOFF_COMPATIBILITY_REMOVED')
    for text in ('go test -race ./internal/config ./internal/control ./internal/daemon',
                 'r05c_full_ci_precommit.py --run','connectedDebugAndroidTest',
                 'r05c_verify_instrumentation.py','test "$HEAD_SHA" =',
                 'if-no-files-found: error'):
        require(text in workflow,'HOSTED_GATE_REMOVED_'+text)
    require(workflow.count('ref: ${{ github.event.pull_request.head.sha || github.sha }}')==4,
            'HOSTED_SOURCE_PINS_MISSING')
    require('continue-on-error' not in workflow and '|| true' not in workflow,
            'HOSTED_FAIL_OPEN')

class NativeDevtoolContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest=MANIFEST.read_text(encoding='utf-8')
        cls.go=GO.read_text(encoding='utf-8')
        cls.workflow=WORKFLOW.read_text(encoding='utf-8')
        audit(cls.manifest,cls.go,cls.workflow)

    def test_exact_native_target_and_complete_graph(self):
        audit(self.manifest,self.go,self.workflow)

    def test_all_module_workflows_reject_missing_vet_or_seal(self):
        for mutation in (
            'job:xbs-go-vet', 'job:xbs-final-seal', 'job:xbs-contract-check',
        ):
            changed=self.manifest.replace(mutation,'job:missing',1)
            self.assertNotEqual(changed,self.manifest)
            with self.assertRaises(ContractError): audit(changed,self.go,self.workflow)

    def test_native_runner_downgrade_rejected(self):
        for old,new in [('action = "test"','command = ["go", "test", "./..."]'),
                        ('action = "lint"','command = ["go", "vet", "./..."]')]:
            changed=self.manifest.replace(old,new,1)
            with self.assertRaises(ContractError):audit(changed,self.go,self.workflow)

    def test_module_cannot_bypass_dag_by_build_alias(self):
        m=self.manifest.replace('build = [\n  { id = "source"','build = ["job:xbs-build-module"]\n# deleted [\n  { id = "source"',1)
        with self.assertRaises((ContractError,tomllib.TOMLDecodeError)):audit(m,self.go,self.workflow)

    def test_android_artifact_verification_refusals(self):
        for old,new in [('required = true','required = false'), ('verify = true','verify = false'),
                        ('paths = ["app/build/outputs/apk/debug/app-debug.apk"]',
                         'paths = ["android-app/app/build/outputs/apk/debug/app-debug.apk"]')]:
            altered=self.manifest.replace(old,new,1)
            self.assertNotEqual(altered,self.manifest)
            with self.assertRaises(ContractError):audit(altered,self.go,self.workflow)

    def test_wrong_target_relative_inputs_refused(self):
        m=self.manifest.replace('"../internal/**"','"android-app/internal/**"',1)
        self.assertNotEqual(m,self.manifest)
        with self.assertRaises(ContractError):audit(m,self.go,self.workflow)

    def test_missing_go_handoff_or_old_compatibility_refused(self):
        for key in ['hasNativeGoVet', 'hasLegacyGoTest']:
            with self.assertRaises(ContractError):audit(self.manifest,self.go.replace(key,'R06_MUTATION_REMOVED'),self.workflow)

    def test_linux_race_and_api36_cannot_disappear(self):
        for value in ('go test -race ./internal/config ./internal/control ./internal/daemon',
                      'connectedDebugAndroidTest', 'test "$HEAD_SHA" ='):
            with self.assertRaises(ContractError):audit(self.manifest,self.go,self.workflow.replace(value,'REMOVED',1))

if __name__=='__main__': unittest.main(verbosity=2)

#!/usr/bin/env python3
"""R04 read-only GitHub Actions artifact resolver.

Downloads one *exact* successful workflow artifact to an owned local directory,
validates its checksum, and delegates ZIP/ELF verification to xbsrepo. Never
calls su, magisk, ksud, adb, gradle or modifies installed Android modules.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

REPO='Mikeyphw/xbsctl'
SHA=re.compile(r'[0-9a-f]{40}\Z')
DIGEST=re.compile(r'[0-9a-f]{64}\Z')
MAX_ZIP=192<<20

class Refusal(RuntimeError):pass

def execute(argv, cwd=None):
    if argv[0] not in ('gh','go','git'):
        raise Refusal('R04 allows only gh read/download, git read or go local verify commands')
    if argv[0]=='gh' and len(argv)>1 and argv[1] not in ('api','run'):
        raise Refusal('R04 GitHub invocation is not read-only')
    if argv[0]=='gh' and len(argv)>2 and argv[1]=='run' and argv[2]!='download':
        raise Refusal('R04 only allows hosted artifact download')
    if argv[0]=='git' and argv[1:] not in (["rev-parse","HEAD"], ["status","--porcelain","--untracked-files=all","--","cmd/xbsrepo","internal/module","go.mod","go.sum"]):
        raise Refusal('R04 only allows fixed git read-only source checks')
    p=subprocess.run(argv,cwd=cwd,capture_output=True,text=True,timeout=150)
    if p.returncode:
        raise Refusal(f'command exit={p.returncode} {argv[:3]} stdout={p.stdout[-700:]} stderr={p.stderr[-1400:]}')
    return p.stdout

def api(path):
    if not path.startswith('repos/'+REPO+'/') or any(s in path for s in ('..','\n','\r',' ')):
        raise Refusal('GitHub API path rejected')
    return json.loads(execute(['gh','api',path]))

def sha256(path):
    h=hashlib.sha256()
    with open(path,'rb') as f:
        for chunk in iter(lambda:f.read(1024*1024),b''):h.update(chunk)
    return h.hexdigest()

def safe_parent(path):
    p=Path(path).absolute()
    for ancestor in list(p.parents)[::-1]+[p]:
        if ancestor.is_symlink():raise Refusal('symlinked output path refused')
    if not p.is_dir():raise Refusal('output parent must exist and be a directory')
    return p

def resolve_latest(expected_head,outdir,repo_dir):
    """Resolve the newest source-exact successful run whose artifact passes ALL gates.

    Intentionally bounded to 300 recent workflow runs; a partial search is not
    silently called successful if no candidate qualifies. No root actions.
    """
    if not SHA.fullmatch(expected_head):
        raise Refusal('exact source SHA required for latest artifact discovery')
    candidates=[]
    for page in (1,2,3):
        listing=api(f'repos/{REPO}/actions/runs?head_sha={expected_head}&per_page=100&page={page}')
        runs=listing.get('workflow_runs',[])
        if not isinstance(runs,list):raise Refusal('invalid workflow run listing response')
        for run in runs:
            if run.get('head_sha')==expected_head and run.get('status')=='completed' and run.get('conclusion')=='success' and isinstance(run.get('id'),int) and run.get('id')>0 and run.get('repository',{}).get('full_name')==REPO:
                candidates.append(run)
        if len(runs)<100:break
    candidates.sort(key=lambda x:(str(x.get('created_at','')), x.get('run_attempt',0),x['id']),reverse=True)
    if not candidates:raise Refusal('no successful source-exact hosted workflow runs found')
    reasons=[]
    for run in candidates:
        try:
            found=resolve(run['id'],expected_head,outdir,repo_dir)
            found['discovery']='LATEST_SUCCESSFUL_SOURCE_EXACT_QUALIFIED_RUN'
            return found
        except Refusal as e:
            if 'destination already exists' in str(e):raise
            reasons.append(f"run={run['id']}: "+str(e)[:200])
    raise Refusal('no source-exact run qualified within bounded listing: '+'; '.join(reasons[:5]))

def resolve(run_id,expected_head,outdir,repo_dir):
    if not isinstance(run_id,int) or run_id<=0 or not SHA.fullmatch(expected_head):
        raise Refusal('explicit run-id and exact source SHA required')
    outdir=safe_parent(outdir)
    repo_dir=Path(repo_dir).resolve()
    if not (repo_dir/'go.mod').is_file():raise Refusal('xbsctl checkout missing')
    if execute(['git','rev-parse','HEAD'],cwd=repo_dir).strip()!=expected_head:
        raise Refusal('verifier checkout HEAD differs from requested hosted source SHA')
    dirty=execute(['git','status','--porcelain','--untracked-files=all','--','cmd/xbsrepo','internal/module','go.mod','go.sum'],cwd=repo_dir)
    if dirty.strip():raise Refusal('local Go verifier source has tracked or untracked modifications: '+dirty[:500])
    run=api(f'repos/{REPO}/actions/runs/{run_id}')
    if run.get('id')!=run_id or run.get('head_sha')!=expected_head or run.get('conclusion')!='success' or run.get('status')!='completed':
        raise Refusal('hosted run is not a successful exact-source run')
    attempt=run.get('run_attempt')
    if not isinstance(attempt,int) or attempt<=0:raise Refusal('unknown hosted run attempt')
    if run.get('repository',{}).get('full_name')!=REPO:
        raise Refusal('GitHub run repository mismatch')
    jobs=api(f'repos/{REPO}/actions/runs/{run_id}/jobs?per_page=100').get('jobs',[])
    required=[j for j in jobs if j.get('name')=='go-contract' and j.get('run_attempt',attempt)==attempt]
    if len(required)!=1 or required[0].get('conclusion')!='success':
        raise Refusal('required go-contract job was not observed successful')
    mandatory=['Go unit tests','Go vet','XBS contracts','Source-bound final seal','Android module candidate','Module checksum']
    steps={s.get('name'):s.get('conclusion') for s in required[0].get('steps',[])}
    if any(steps.get(s)!='success' for s in mandatory):raise Refusal('mandatory source-bound module job steps missing/failed')
    artifact_name=f'xbs-android-module-{run_id}-{attempt}'
    arts=api(f'repos/{REPO}/actions/runs/{run_id}/artifacts?per_page=100').get('artifacts',[])
    hits=[a for a in arts if a.get('name')==artifact_name]
    if len(hits)!=1 or hits[0].get('expired') or hits[0].get('size_in_bytes',0)<=0:
        raise Refusal('unique unexpired module artifact missing')
    if hits[0].get('workflow_run',{}).get('head_sha')!=expected_head or hits[0].get('workflow_run',{}).get('id')!=run_id:
        raise Refusal('artifact is not associated with exact workflow run and source SHA')
    with tempfile.TemporaryDirectory(prefix='xbs-r04-hosted-',dir=os.environ.get('TMPDIR') or None) as work:
        execute(['gh','run','download',str(run_id),'--repo',REPO,'--name',artifact_name,'--dir',work])
        entries=list(Path(work).iterdir())
        if {p.name for p in entries}!={'XtremeBS-go.zip','SHA256SUMS'} or not all(p.is_file() and not p.is_symlink() for p in entries):
            raise Refusal('hosted artifact contains unexpected or missing files')
        module=Path(work)/'XtremeBS-go.zip';checksum=Path(work)/'SHA256SUMS'
        if module.stat().st_size<=0 or module.stat().st_size>MAX_ZIP:
            raise Refusal('hosted module ZIP size out of bounds')
        sums=checksum.read_text('utf-8')
        match=re.fullmatch(r'([a-f0-9]{64})  XtremeBS-go\.zip\n',sums)
        if not match:raise Refusal('SHA256SUMS format is ambiguous or contains extra entries')
        digest=sha256(module)
        if digest!=match.group(1):raise Refusal('hosted module ZIP checksum mismatch')
        execute(['go','run','./cmd/xbsrepo','verify-module-zip','--zip',str(module),'--sha256',digest],cwd=repo_dir)
        receipt={'schema':'xbs-r04-hosted-artifact/v1','repository':REPO,
                 'commit':expected_head,'run_id':run_id,'run_attempt':attempt,
                 'artifact':artifact_name,'artifact_id':hits[0].get('id'),
                 'sha256':digest,'size_bytes':module.stat().st_size,
                 'status':'HOSTED_ARTIFACT_CHECKSUM_AND_LAYOUT_VERIFIED_NOT_DEVICE_ACCEPTANCE'}
        destination=outdir/f'XtremeBS-{run_id}-{attempt}.zip'
        receipt_path=outdir/f'XtremeBS-{run_id}-{attempt}.receipt.json'
        if destination.exists() or receipt_path.exists() or destination.is_symlink() or receipt_path.is_symlink():
            raise Refusal('destination already exists; never overwrite previously published evidence')
        created=[]
        try:
            for source,target in [(module,destination)]:
                fd=os.open(str(target),os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600);created.append(target)
                with os.fdopen(fd,'wb') as out,open(source,'rb') as inp:
                    shutil.copyfileobj(inp,out);out.flush();os.fsync(out.fileno())
            if sha256(destination)!=digest:raise Refusal('published artifact digest differs')
            data=json.dumps(receipt,indent=2,sort_keys=True)+'\n'
            fd=os.open(str(receipt_path),os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600);created.append(receipt_path)
            with os.fdopen(fd,'w') as out:out.write(data);out.flush();os.fsync(out.fileno())
            fd=os.open(str(outdir),os.O_RDONLY);os.fsync(fd);os.close(fd)
        except BaseException:
            for f in created:
                try:f.unlink()
                except OSError:pass
            raise
        return {'zip':str(destination),'receipt':str(receipt_path),**receipt}

def main(argv=None):
    p=argparse.ArgumentParser(description='R04 exact-run read-only hosted module artifact verifier')
    group=p.add_mutually_exclusive_group(required=True)
    group.add_argument('--run-id',type=int)
    group.add_argument('--latest',action='store_true',help='discover newest successful qualified run at exact source SHA')
    p.add_argument('--source-sha',required=True)
    p.add_argument('--output-dir',required=True)
    p.add_argument('--repo-dir',default='.')
    a=p.parse_args(argv)
    try:print(json.dumps(resolve_latest(a.source_sha,a.output_dir,a.repo_dir) if a.latest else resolve(a.run_id,a.source_sha,a.output_dir,a.repo_dir),indent=2))
    except (Refusal,OSError,ValueError,subprocess.TimeoutExpired,json.JSONDecodeError) as e:
        print('XBS_R04_HOSTED_RESOLVER_REFUSED: '+str(e),file=sys.stderr);return 2
    return 0
if __name__=='__main__':sys.exit(main())

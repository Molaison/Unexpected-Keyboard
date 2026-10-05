import json, os, subprocess
from pathlib import Path

def git(*args):
    return subprocess.check_output(['git', *args])
base = os.environ['GITHUB_SHA']
upstream = '41249cd63701e70f291bf5fe65d0d52a6efeaafe'
final_tree = git('rev-parse','HEAD^{tree}').decode().strip()
known = {line.split()[0] for line in git('rev-list','--objects',base,upstream).decode().splitlines()}
raw = git('diff','--raw','--no-abbrev','--no-renames','-z',base,final_tree).split(b'\0')
entries = []
payload = Path(os.environ['RUNNER_TEMP'])/'blob-request.json'
for i in range(0,len(raw)-1,2):
    fields = raw[i].decode().split()
    _, mode, _, sha, status = fields
    path = raw[i+1].decode()
    entry = {'path':path,'mode':mode if status != 'D' else '100644','type':'commit' if mode == '160000' else 'blob','sha':None if status == 'D' else sha}
    if status != 'D' and sha not in known and mode != '160000':
        payload.write_text(json.dumps({'encoding':'utf-8','content':git('cat-file','blob',sha).decode('utf-8')}))
        result = json.loads(subprocess.check_output(['gh','api','--method','POST',f'repos/{os.environ["GITHUB_REPOSITORY"]}/git/blobs','--input',str(payload)]))
        assert result['sha'] == sha, (result['sha'],sha)
        known.add(sha)
    entries.append(entry)
result = {'base':base,'upstream':upstream,'tree':final_tree,'run_id':os.environ['GITHUB_RUN_ID'],'base_tree':git('rev-parse',base+'^{tree}').decode().strip(),'entries':entries}
Path('build/validation/final-tree.json').write_text(json.dumps(result,indent=2)+'\n')
print('Prepared source blobs and manifest for the authorized connector; no tree, commit, workflow or branch was changed.')
print(final_tree)

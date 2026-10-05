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
for i in range(0,len(raw)-1,2):
    fields = raw[i].decode().split()
    _, mode, _, sha, status = fields
    path = raw[i+1].decode()
    entry = {'path':path,'mode':mode if status != 'D' else '100644','type':'commit' if mode == '160000' else 'blob'}
    if status == 'D':
        entry['sha'] = None
    elif sha in known or mode == '160000':
        entry['sha'] = sha
    else:
        entry['content'] = git('cat-file','blob',sha).decode('utf-8')
    entries.append(entry)
payload = Path(os.environ['RUNNER_TEMP'])/'final-tree-request.json'
payload.write_text(json.dumps({'base_tree':git('rev-parse',base+'^{tree}').decode().strip(),'tree':entries}))
result = json.loads(subprocess.check_output(['gh','api','--method','POST',f'repos/{os.environ["GITHUB_REPOSITORY"]}/git/trees','--input',str(payload)]))
assert result['sha'] == final_tree, (result['sha'],final_tree)
result = {'base':base,'upstream':upstream,'tree':final_tree,'run_id':os.environ['GITHUB_RUN_ID']}
Path('build/validation/final-tree.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result))

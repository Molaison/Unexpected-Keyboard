import base64, hashlib, json, os, pathlib, subprocess
root=pathlib.Path('build/validation'); root.mkdir(parents=True, exist_ok=True)
def git(*args): return subprocess.check_output(['git',*args]).decode().strip()
base='b0b6c88a2e8019c56c7bb08fe437c98975ce63b4'
entries=[]
for name in git('diff','--name-only',base,'HEAD').splitlines():
    found=git('ls-tree','HEAD','--',name)
    if not found:
        entries.append(dict(path=name,mode='100644',type='blob',sha=None)); continue
    mode,kind,sha=found.split('\t')[0].split()
    if kind!='blob': raise RuntimeError('Unexpected changed gitlink')
    data=pathlib.Path(name).read_bytes()
    payload=pathlib.Path(os.environ['RUNNER_TEMP'])/'ux-blob.json'
    payload.write_text(json.dumps(dict(content=base64.b64encode(data).decode(),encoding='base64')))
    result=json.loads(subprocess.check_output(['gh','api','--method','POST','repos/'+os.environ['GITHUB_REPOSITORY']+'/git/blobs','--input',str(payload)]))
    if result['sha']!=sha: raise RuntimeError('Blob hash mismatch: '+name)
    entries.append(dict(path=name,mode=mode,type='blob',sha=sha))
(root/'source-tree.json').write_text(json.dumps(dict(tree=git('rev-parse','HEAD^{tree}'),base_tree=git('rev-parse',base+'^{tree}'),entries=entries),indent=2))
subprocess.run(['git','archive','--format=tar.gz','-o',str(root/'tested-source.tar.gz'),'HEAD'],check=True)
print('Exported exact source tree',git('rev-parse','HEAD^{tree}'))

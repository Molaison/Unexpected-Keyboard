from pathlib import Path
import re, subprocess
root = Path.cwd()
def read(p): return (root/p).read_text()
def write(p,s): (root/p).write_text(s)
def resolve(p, fn):
    s=read(p)
    pattern = re.compile(r'^<<<<<<< HEAD\n(.*?)^=======\n(.*?)^>>>>>>> [^\n]+\n', re.M|re.S)
    s=pattern.sub(lambda m:fn(m[1],m[2]),s)
    assert '<<<<<<<' not in s,p
    write(p,s)
for p in ['res/values-zh-rCN/strings.xml','srcs/juloo.keyboard2/KeyValue.java']:
    resolve(p,lambda a,b:a+b)
resolve('build.gradle.kts',lambda a,b:a.replace('versionCode = 55','versionCode = 56').replace('versionName = "2.0.4"','versionName = "2.1.0"'))
def keyboard(a,b):
    if 'Symbol_provider' in a:
        return '         DoubaoVoiceInput.Host, PinyinInput.Host,\n         KeyValue.Stateful.Symbol_provider, DictionarySwitcher.Callback\n'
    return a+b
resolve('srcs/juloo.keyboard2/Keyboard2.java',keyboard)
resolve('.github/workflows/make-apk.yml',lambda a,b:a+b if 'if-no-files-found' in a else a)
s=read('.github/workflows/make-apk.yml')
s=s.replace('''        artifact="${{github.repository_owner}} ${{github.ref_name}}"
        artifact="${artifact//\\//-}" # replace slashes
        echo "artifact=${artifact}" >> $GITHUB_ENV
''','')
s=s.replace('''        # Warn about outdated generated files.
        if ! git diff --quiet; then
          echo "Warning: Generated files are not uptodate. Run 'gradle test'."
          git diff --name-only
        fi
''','')
write('.github/workflows/make-apk.yml',s)
with open(root/'res/xml/method.xml','w') as out:
    subprocess.run(['python3','gen_method_xml.py'],cwd=root,stdout=out,check=True)
subprocess.run(['git','add','-u'],cwd=root,check=True)

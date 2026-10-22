#!/usr/bin/env python3
import hashlib,re,subprocess,tarfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]; out=root/'dist'; out.mkdir(exist_ok=True)
version=re.search(r'\bversionName\s*=\s*"([0-9][A-Za-z0-9.+-]*)"',(root/'app/build.gradle.kts').read_text())
if version is None: raise ValueError('Missing Android versionName')
version=version.group(1)
files=[name for name in subprocess.check_output(['git','-C',str(root),'ls-files','-z']).decode().split('\0')
       if name and Path(name).parts[0]!='dist']
extra=['engine/src/main/assets/pikafish.nnue','dist/build-manifest.json']
target=out/f'xiangqi-assistant-{version}-source.tar.gz'
with tarfile.open(target,'w:gz') as tar:
    for name in sorted(set(files+extra)):
        p=root/name
        assert not any(x in p.parts for x in ['.local-signing','.git','.superpowers'])
        assert p.suffix not in ['.keystore','.jks','.key','.pem','.mp4']
        if p.is_file(): tar.add(p,arcname=f'JJ-chess-{version}/'+name,recursive=False)
print(str(target)); print(hashlib.sha256(target.read_bytes()).hexdigest())

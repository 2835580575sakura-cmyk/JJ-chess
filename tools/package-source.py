#!/usr/bin/env python3
import hashlib,json,subprocess,tarfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]; out=root/'dist'; out.mkdir(exist_ok=True)
files=subprocess.check_output(['git','-C',str(root),'ls-files','-z']).decode().strip('\0').split('\0')
extra=['engine/src/main/assets/pikafish.nnue','dist/build-manifest.json']
target=out/'xiangqi-assistant-0.1.0-source.tar.gz'
with tarfile.open(target,'w:gz') as tar:
    for name in sorted(set(files+extra)):
        p=root/name
        assert not any(x in p.parts for x in ['.local-signing','.git','.superpowers'])
        assert p.suffix not in ['.keystore','.jks','.key','.pem','.mp4']
        if p.is_file(): tar.add(p,arcname='JJ-chess-0.1.0/'+name,recursive=False)
print(str(target)); print(hashlib.sha256(target.read_bytes()).hexdigest())

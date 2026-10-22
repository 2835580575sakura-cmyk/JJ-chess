#!/usr/bin/env python3
"""Prepare fixed upstream sources and the separately licensed, verified NNUE."""
import hashlib, json, shutil, subprocess, sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
CACHE=Path('/workspace/.toolchains')
COMMIT='4c17cee11f888ae1d48a9494f2e2239f019f0a1f'
NETWORK_SHA='7d13d73569a9b571ba0eb20cf1596247bc2a42738967e61afef6482b231e900e'
ARCHIVE_SHA='41952bbfe2520faceb5902c69e6ab4845cc999841d2b49a95cc1be7867a25e5b'
def digest(path):
    with path.open('rb') as f: return hashlib.file_digest(f,'sha256').hexdigest()
def main():
    source=CACHE/'pikafish-source'
    if not source.exists():
        subprocess.run(['git','clone','--quiet','--depth','1','--branch','Pikafish-2026-09-06','https://github.com/official-pikafish/Pikafish.git',str(source)],check=True)
    actual=subprocess.check_output(['git','-C',str(source),'rev-parse','HEAD'],text=True).strip()
    if actual!=COMMIT: raise SystemExit('Unexpected upstream commit')
    target=ROOT/'third_party/pikafish'
    target.mkdir(parents=True,exist_ok=True)
    shutil.copytree(source/'src',target/'src',dirs_exist_ok=True)
    for name in ['Copying.txt','AUTHORS','README.md']:
        if (source/name).exists(): shutil.copy2(source/name,target/name)
    archive=CACHE/'Pikafish.2026-09-06.7z'
    if not archive.exists():
        subprocess.run(['curl','--fail','--location','--silent','--show-error','https://github.com/official-pikafish/Pikafish/releases/download/Pikafish-2026-09-06/Pikafish.2026-09-06.7z','-o',str(archive)],check=True)
    if digest(archive)!=ARCHIVE_SHA: raise SystemExit('Release archive checksum mismatch')
    extracted=CACHE/'pikafish-release'
    if not (extracted/'pikafish.nnue').exists():
        sys.path.insert(0,str(CACHE/'python-libs'))
        try: import py7zr
        except ImportError: raise SystemExit('Install py7zr==1.0.0 in /workspace/.toolchains/python-libs first')
        with py7zr.SevenZipFile(archive) as z: z.extract(path=extracted,targets=['pikafish.nnue','NNUE-License.md'])
    if digest(extracted/'pikafish.nnue')!=NETWORK_SHA: raise SystemExit('NNUE checksum mismatch')
    assets=ROOT/'engine/src/main/assets'
    assets.mkdir(parents=True,exist_ok=True)
    shutil.copy2(extracted/'pikafish.nnue',assets/'pikafish.nnue')
    notices=ROOT/'third_party/licenses'
    notices.mkdir(parents=True,exist_ok=True)
    shutil.copy2(extracted/'NNUE-License.md',notices/'NNUE-License.md')
    manifest={'engineTag':'Pikafish-2026-09-06','engineCommit':COMMIT,'archiveSha256':ARCHIVE_SHA,'networkSha256':NETWORK_SHA,'networkBytes':(assets/'pikafish.nnue').stat().st_size,'networkSource':'https://github.com/official-pikafish/Pikafish/releases/tag/Pikafish-2026-09-06','networkLicense':'NNUE-License.md','ndk':'28.2.13676358'}
    (ROOT/'third_party/pikafish-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    print('Prepared fixed engine, matching NNUE and separate license')
if __name__=='__main__': main()

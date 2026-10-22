#!/usr/bin/env python3
"""Fail closed on signature, ABI, alignment, bundled NNUE, or permission regressions."""
import hashlib,json,os,re,struct,subprocess,sys,zipfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]
apk=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else root/'app/build/outputs/apk/debug/app-debug.apk'
sdk=Path(os.environ['ANDROID_HOME']); bt=sdk/'build-tools/36.0.0'
def run(args):
    p=subprocess.run([str(x) for x in args],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    if p.returncode: raise SystemExit(p.stdout[-6000:])
    return p.stdout
signature=run([bt/'apksigner','verify','--verbose','--print-certs',apk]); assert 'Verified' in signature
alignment=run([bt/'zipalign','-c','-P','16','-v','4',apk]); assert 'Verification successful' in alignment
permissions=run([bt/'aapt2','dump','permissions',apk]); assert 'android.permission.INTERNET' not in permissions and 'BIND_ACCESSIBILITY_SERVICE' not in permissions
xml=run([bt/'aapt2','dump','xmltree',apk,'--file','AndroidManifest.xml']); assert 'AccessibilityService' not in xml
upstream=json.loads((root/'third_party/pikafish-manifest.json').read_text())
with zipfile.ZipFile(apk) as z:
    names=z.namelist(); libs=[n for n in names if n.startswith('lib/') and n.endswith('.so')]
    assert libs and all(n.startswith('lib/arm64-v8a/') for n in libs),libs
    assert {'libpikafish_base.so','libpikafish_dotprod.so','libcpu_dispatch.so'} <= {Path(n).name for n in libs}
    for name in names: assert not re.search(r'\.(?:keystore|jks|key|pem)$',name,re.I),name
    sha=hashlib.sha256(z.read('assets/pikafish.nnue')).hexdigest(); assert sha==upstream['networkSha256']
    assert z.getinfo('assets/pikafish.nnue').file_size==upstream['networkBytes']
    for name in libs:
        data=z.read(name); assert data[:6]==b'\x7fELF\x02\x01'; assert struct.unpack_from('<H',data,18)[0]==183
        phoff=struct.unpack_from('<Q',data,32)[0]; entsize,count=struct.unpack_from('<HH',data,54)
        loads=0
        for i in range(count):
            typ,flags,offset,vaddr,paddr,filesz,memsz,align=struct.unpack_from('<IIQQQQQQ',data,phoff+i*entsize)
            if typ==1:
                loads+=1; assert align>=16384 and (offset-vaddr)%16384==0,(name,align)
        assert loads>0
    assert 'assets/licenses/GPL-3.0.txt' in names and 'assets/licenses/NNUE-License.md' in names
certificate=re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-f]+)',signature)
manifest={'apk':apk.name,'apkSha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'apkBytes':apk.stat().st_size,'sourceCommit':run(['git','-C',root,'rev-parse','HEAD']).strip(),'certificateSha256':certificate.group(1) if certificate else None,'upstream':upstream,'abis':['arm64-v8a'],'nativeLibraries':libs,'signatureVerified':True,'zip16KiBVerified':True,'elf16KiBVerified':True,'deviceTests':'not run; no Android device attached'}
output=root/'dist/build-manifest.json'; output.parent.mkdir(exist_ok=True); output.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
print('PASS: signature, permissions, ARM64, ZIP/ELF 16 KiB alignment, NNUE SHA-256, licenses, no private keys')
print('APK SHA-256: '+manifest['apkSha256']); print('Manifest: '+str(output))

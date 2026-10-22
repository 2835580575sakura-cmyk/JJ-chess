#!/usr/bin/env python3
"""Extract board-only reference fixtures and manually labelled opening templates."""
import argparse,json,struct,subprocess,tempfile
from pathlib import Path
from PIL import Image
ROOT=Path(__file__).resolve().parents[1]
START='rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1'
CAL6='rnbakabnr/9/4c2c1/p1p1p1p1p/9/9/P1P1P1P1P/1C2C1N2/9/RNBAKAB1R w - - 0 1'
CAL10='1rbakabnr/9/2n1c2c1/p1p1p1p1p/9/2P6/P3P1P1P/1C2C1N2/9/RNBAKAB1R w - - 0 1'
HOLD='r1bakabnr/9/2n1c2c1/p1p1p1p1p/9/2P6/P3P1P1P/1C2C1N2/9/RNBAKAB1R w - - 0 1'
def main():
    parser=argparse.ArgumentParser(); parser.add_argument('video'); args=parser.parse_args()
    out=ROOT/'vision/src/test/resources/jj'; out.mkdir(parents=True,exist_ok=True)
    templates=ROOT/'vision/src/main/resources/jj'; templates.mkdir(parents=True,exist_ok=True)
    sources=[('start',2,START,'training'),('cal6',6,CAL6,'calibration'),('cal10',10,CAL10,'calibration'),('holdout8',8.5,HOLD,'holdout'),('animation18',18,None,'animation'),('animation27',27,None,'animation')]
    initial=None
    with tempfile.TemporaryDirectory() as temp:
        for name,t,fen,group in sources:
            p=Path(temp)/f'{name}.png'
            subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-ss',str(t),'-i',args.video,'-frames:v','1','-update','1',str(p)],check=True)
            image=Image.open(p).convert('RGB')
            image.crop((8,330,576,945)).save(out/f'{name}.png')
            if name=='start': initial=image
        entries=[]
        for row,text in enumerate(START.split()[0].split('/')):
            file=0
            for c in text:
                if c.isdigit(): file+=int(c); continue
                label=('rnbakcp'.index(c.lower()))+(0 if c.isupper() else 7)
                x=round(40+505*file/8); y=round(366+543*row/9)
                entries.append((label,initial.crop((x-28,y-28,x+28,y+28))))
                file+=1
        with (templates/'templates.bin').open('wb') as f:
            f.write(struct.pack('>ii',0x4A4A5431,len(entries)))
            for label,img in entries:
                f.write(struct.pack('>iii',label,img.width,img.height))
                for r,g,b in img.getdata(): f.write(struct.pack('>I',0xff000000|(r<<16)|(g<<8)|b))
    (out/'labels.properties').write_text('\n'.join(name+'='+fen for name,t,fen,group in sources if fen)+'\n')
    (out/'manifest.json').write_text(json.dumps({'sourceFile':Path(args.video).name,'crop':[8,330,576,945],'grid':[32,36,537,579],'turnNotAnnotated':True,'annotations':'Manually read board occupancy; FEN side field is not an observed turn. Opening template labels follow standard start position.','samples':[{'name':n,'seconds':t,'group':g} for n,t,fen,g in sources]},indent=2)+'\n')
    print('Prepared board-only manually labelled fixtures and 32 opening templates')
if __name__=='__main__': main()

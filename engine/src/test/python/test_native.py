import json, os, subprocess, tempfile, unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[4]
PROBE=ROOT/'.build/native/host_probe'
NET=ROOT/'engine/src/main/assets/pikafish.nnue'
START='rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1'

def fen(pieces,side='w'):
    rows=[]
    for rank in range(9,-1,-1):
        row=''; count=0
        for file in range(9):
            piece=pieces.get(chr(97+file)+str(rank))
            if piece:
                if count: row+=str(count); count=0
                row+=piece
            else: count+=1
        if count: row+=str(count)
        rows.append(row)
    return '/'.join(rows)+' '+side+' - - 0 1'

class NativeTests(unittest.TestCase):
    def run_probe(self,cmd,position=START,net=NET,moves=None):
        args=[str(PROBE),cmd,'--fen',position]
        if cmd=='search': args+=['--net',str(net),'--movetime','1000']
        if moves: args+=['--moves',' '.join(moves)]
        return subprocess.run(args,text=True,capture_output=True,timeout=30)
    def legal(self,position=START,moves=None):
        r=self.run_probe('legal',position,moves=moves)
        self.assertEqual(0,r.returncode,r.stderr+r.stdout)
        return json.loads(r.stdout.strip().splitlines()[-1])['legalMoves']
    def test_initial_legal(self):
        moves=self.legal(); self.assertIn('b0c2',moves); self.assertNotIn('b0b2',moves)
    def test_horse_leg(self):
        pieces={'e0':'K','e9':'k','e5':'P','b0':'N','b1':'R'}
        self.assertNotIn('b0c2',self.legal(fen(pieces)))
    def test_cannon_screen(self):
        pieces={'e0':'K','e9':'k','e5':'P','b2':'C','b7':'r'}
        self.assertNotIn('b2b7',self.legal(fen(pieces)))
        pieces['b4']='p'
        self.assertIn('b2b7',self.legal(fen(pieces)))
    def test_flying_generals(self):
        self.assertNotIn('e5d5',self.legal(fen({'e0':'K','e9':'k','e5':'P'})))
    def test_check_must_be_answered(self):
        moves=self.legal(fen({'e0':'K','d9':'k','e5':'r','a0':'R'}))
        self.assertNotIn('a0a1',moves)
    def test_both_sides(self):
        self.assertIn('b9c7',self.legal(START.replace(' w ',' b ')))
    def test_terminal_has_no_legal_move(self):
        self.assertEqual([],self.legal(fen({'e0':'K','d9':'k','d2':'r','f2':'r','e1':'p','c0':'n'})))
    def test_invalid_position_and_history(self):
        self.assertNotEqual(0,self.run_probe('legal','9/9/9/9/9/9/9/9/9/9 w - - 0 1').returncode)
        self.assertNotEqual(0,self.run_probe('legal',moves=['b0b2']).returncode)
    def test_search_returns_legal_move(self):
        r=self.run_probe('search'); self.assertEqual(0,r.returncode,r.stderr+r.stdout)
        best=json.loads(r.stdout.strip().splitlines()[-1])['bestMove']
        self.assertIn(best,self.legal())
    def test_corrupt_network_fails(self):
        with tempfile.NamedTemporaryFile() as f:
            f.write(b'corrupt'); f.flush()
            self.assertNotEqual(0,self.run_probe('search',net=f.name).returncode)

if __name__=='__main__': unittest.main()

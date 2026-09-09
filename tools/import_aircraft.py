"""Convert upstream C++ offline database without redrawing silhouettes."""
import re,json,sys,pathlib,csv,io
s=pathlib.Path(sys.argv[1]).read_text();out=pathlib.Path(sys.argv[2]);out.mkdir(parents=True,exist_ok=True)
def section(name): return s.split(name,1)[1].split('};',1)[0].split('{',1)[1]
def rows(name):
 for r in re.findall(r'\{([^{}]+)\}',section(name)):
  yield next(csv.reader(io.StringIO(r),skipinitialspace=True))
keys=['icao','mfr','model','cls','eng_n','eng_t','eng','span_dm','len_dm','mtow_100kg','cruise_kt','sil']
spec={}
for r in rows('inline const AcSpec AC_SPECS[]'):
 d=dict(zip(keys,r));d.update({k:int(d[k]) for k in keys[4:] if k!='eng'});spec[d['icao']]=d
ops={r[0]:r[1:] for r in rows('inline const AcOperator AC_OPERATORS[]')}
blocks=[[int(r[0],16),int(r[1],16),r[2]] for r in rows('inline const AcHexBlock AC_HEX_BLOCKS[]')]
(out/'aircraft.json').write_text(json.dumps({'specs':spec,'operators':ops,'blocks':blocks},ensure_ascii=False))
a=re.sub(r'//[^\n]*','',section('inline const uint8_t AC_SIL_A8['));b=bytes(map(int,re.findall(r'\d+',a)));assert len(b)==107*9216;(out/'silhouettes.a8').write_bytes(b)
assert len(spec)==316 and len(ops)==6004
print(f'{len(spec)} types, {len(ops)} operators, {len(blocks)} countries, {len(b)//9216} silhouettes')

"""Compare complete generated codes and PCM, rather than duration alone."""
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np

p=argparse.ArgumentParser()
p.add_argument("baseline",type=Path)
p.add_argument("optimized",type=Path)
p.add_argument("--output",type=Path,required=True)
a=p.parse_args()
b=json.loads((a.baseline/'benchmark.json').read_text(encoding='utf-8'))
n=json.loads((a.optimized/'benchmark.json').read_text(encoding='utf-8'))
assert len(b['reports'])==len(n['reports'])
rows=[]
for i,(old,new) in enumerate(zip(b['reports'],n['reports'])):
    assert old['request']['text']==new['request']['text']
    assert old['request']['seed']==new['request']['seed']
    old_codes=np.load(a.baseline/str(i)/'1-codes.npy')
    new_codes=np.load(a.optimized/str(i)/'1-codes.npy')
    identical=bool(np.array_equal(old_codes,new_codes))
    old_pcm=hashlib.sha256((a.baseline/str(i)/'audio8.wav').read_bytes()).hexdigest()
    new_pcm=hashlib.sha256((a.optimized/str(i)/'audio8.wav').read_bytes()).hexdigest()
    row=dict(index=i,codes_identical=identical,pcm_identical=old_pcm==new_pcm,
             frames=int(old_codes.shape[-1]),audio_seconds=old['audio_seconds'],
             old_seconds=old['wall_seconds'],new_seconds=new['wall_seconds'])
    rows.append(row)
    assert identical,row
    assert old_pcm==new_pcm,row
result=dict(rows=rows,baseline_rtf=b['warm_rtf'],optimized_rtf=n['warm_rtf'],
            time_reduction=1-n['warm_wall']/b['warm_wall'],
            speedup=b['warm_rtf']/n['warm_rtf'],
            old_peak_reserved=b['reports'][-1]['engine']['peak_reserved_bytes'],
            new_peak_reserved=n['reports'][-1]['engine']['peak_reserved_bytes'])
a.output.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(result,ensure_ascii=False,indent=2),flush=True)

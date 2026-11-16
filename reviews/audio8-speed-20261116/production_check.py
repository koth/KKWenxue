"""Exercise the actual service queue without exposing its private credential."""
import argparse
import json
from pathlib import Path
import sqlite3
import urllib.request

p = argparse.ArgumentParser()
p.add_argument("mode",choices=["submit","status"])
a = p.parse_args()
root = Path('/home/jetson/workspace/audio8-speed-20261116')
headers = {'X-Service-Token':Path('/home/jetson/.local/share/shanye-tts/api-token').read_text().strip()}
if a.mode=='submit':
    headers.update({'Content-Type':'application/json','Idempotency-Key':'audio8-reference-cache-v2-20261116'})
    body = dict(book_id='local-f6a38e5f6131290460a62005',
                chapter_ids=['local-f6a38e5f6131290460a62005-1031'],
                voice_preset_version_id='clone-a63c22269e594284805a0ffaadb784a1',mode='new-version')
    req = urllib.request.Request('http://127.0.0.1:17860/api/v1/previews',
                                 data=json.dumps(body).encode(),headers=headers)
    result = json.load(urllib.request.urlopen(req,timeout=30))
    (root/'production-job.json').write_text(json.dumps(result))
    print(result)
else:
    identifier = json.loads((root/'production-job.json').read_text())['id']
    req = urllib.request.Request('http://127.0.0.1:17860/api/v1/jobs/'+identifier,headers=headers)
    result = json.load(urllib.request.urlopen(req,timeout=30))
    (root/'production-report.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
    c = sqlite3.connect('/home/jetson/.local/share/shanye-tts/service.sqlite');c.row_factory=sqlite3.Row
    rows = [dict(r) for r in c.execute('select segment_id,number,elapsed,runtime_json,error from attempts where job_id=? order by id',(identifier,))]
    for row in rows:
        row['runtime'] = json.loads(row.pop('runtime_json'))
    (root/'production-attempts.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2))
    artifacts = [dict(r) for r in c.execute('select a.* from artifacts a join job_chapters jc on jc.artifact_id=a.id where jc.job_id=?',(identifier,))]
    (root/'production-artifacts.json').write_text(json.dumps(artifacts,ensure_ascii=False,indent=2))
    print(json.dumps(dict(id=identifier,state=result['state'],counts=result['counts'],
                         inference_revision=result['config']['generation'].get('inference_revision'),
                         artifacts=[dict(id=r['id'],audio_path=r['audio_path'],manifest_path=r['manifest_path']) for r in artifacts]),ensure_ascii=False))

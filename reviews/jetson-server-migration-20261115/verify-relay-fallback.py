"""Explicitly request an on-demand relay upload and validate the public download."""
import hashlib,json,subprocess,sys,time
from pathlib import Path
from urllib.request import Request,urlopen
peer=['ssh','-S','/tmp/shanye-jetson-lan-master','jetson@192.168.3.33']
remote="set -a; . /home/jetson/workspace/shanye-tts-service/service.env; set +a; python3 - <<'EOPY'\nimport json,os\nprint(json.dumps({'url':os.environ['SHANYE_RELAY_URL'],'token':os.environ['SHANYE_RELAY_TOKEN']}))\nEOPY"
credentials=json.loads(subprocess.check_output(peer+[remote]))
ids=json.loads(subprocess.check_output(peer+['cat /home/jetson/workspace/migration-20261115/acceptance-input.json']))
kind=sys.argv[1]
ident=ids[kind+'_id']
base=credentials['url'];headers={'X-Relay-Token':credentials['token']}
def get(path,post=False):
    r=Request(base+path,data=b'{}' if post else None,headers={**headers,'Content-Type':'application/json'},method='POST' if post else 'GET')
    with urlopen(r,timeout=40) as response:return json.load(response)
t=get('/v1/tasks/'+ident)
assert t['state']=='completed',t['state']
if t['result']['relay_state']!='ready':get('/v1/tasks/'+ident+'/result/request',True)
for _ in range(45):
    t=get('/v1/tasks/'+ident)
    if t['result']['relay_state']=='ready':break
    time.sleep(2)
else:raise RuntimeError('Relay upload did not finish within 90 seconds')
with urlopen(Request(base+t['result']['download_path'],headers=headers),timeout=60) as response:data=response.read()
assert len(data)==t['result']['bytes'] and hashlib.sha256(data).hexdigest()==t['result']['sha256']
local=Path('/tmp')/f'shanye-jetson-{kind}-acceptance.zip'
if local.exists():assert data==local.read_bytes()
report={'task_id':ident,'bytes':len(data),'sha256':t['result']['sha256'],'relay_state':t['result']['relay_state'],'matched_lan':local.exists()}
Path(f'/Users/kothchen/code/KKWenxue/reviews/jetson-server-migration-20261115/{kind}-relay-report.json').write_text(json.dumps(report,indent=2))
print(json.dumps({kind:report}))

"""Act as a LAN App client; never print or persist result capability tokens."""
import hashlib,io,json,subprocess,sys,zipfile
from pathlib import Path
from urllib.request import Request,urlopen
peer=['ssh','-S','/tmp/shanye-jetson-lan-master','jetson@192.168.3.33']
root='/home/jetson/workspace/migration-20261115/'
tasks=json.loads(subprocess.check_output(peer+['cat '+root+'acceptance-tasks.json']))
inputs=json.loads(subprocess.check_output(peer+['cat '+root+'acceptance-input.json']))
texts={c['id']:c['canonical_text'] for c in inputs['request']['inline_chapters']}
reports={}
for kind,t in tasks.items():
    if t['state']!='completed':
        print(kind,t['state']);continue
    result=t['result'];direct=result['direct']
    assert direct['url'].startswith('http://192.168.3.33:')
    req=Request(direct['url'],headers={'X-Result-Token':direct['token']})
    with urlopen(req,timeout=40) as response: data=response.read()
    sha=hashlib.sha256(data).hexdigest()
    assert len(data)==result['bytes'] and sha==result['sha256']
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        assert z.testzip() is None
        if kind=='tts':
            catalogue=json.loads(z.read('catalogue.json'))
            assert catalogue['schema_version']==1
            assert len(catalogue['entries'])==2
            for item in catalogue['entries']:
                manifest_bytes=z.read(item['manifest']);audio=z.read(item['audio'])
                assert hashlib.sha256(manifest_bytes).hexdigest()==item['manifest_sha256']
                assert hashlib.sha256(audio).hexdigest()==item['audio_sha256']
                m=json.loads(manifest_bytes)
                assert m['complete'] and m['schema_version']==2
                original=texts[m['chapter_id']];assert hashlib.sha256(original.encode()).hexdigest()==m['source_sha256']
                for cue in m['cues']:
                    text=original.encode('utf-16-le')[cue['start_utf16']*2:cue['end_utf16']*2].decode('utf-16-le')
                    assert text==cue['original_text']
                    assert 0<=cue['start_ms']<cue['end_ms']<=m['duration_ms']+2
            count=len(catalogue['entries'])
        else:
            manifest=json.loads(z.read('manifest.json'))
            assert hashlib.sha256(z.read('book.txt')).hexdigest()==manifest['sha256']
            count=manifest['chapters']
    dest=Path('/tmp')/f'shanye-jetson-{kind}-acceptance.zip';dest.write_bytes(data)
    reports[kind]={'task_id':t['id'],'bytes':len(data),'sha256':sha,'chapters':count,'lan_host':'192.168.3.33','downloaded_to':str(dest)}
    print(json.dumps({kind:reports[kind]},ensure_ascii=False))
Path('/Users/kothchen/code/KKWenxue/reviews/jetson-server-migration-20261115/lan-download-report.json').write_text(json.dumps(reports,ensure_ascii=False,indent=2))

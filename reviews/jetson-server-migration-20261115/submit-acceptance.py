"""Submit a small complete TTS book and one cached-text delivery via the real relay."""
import hashlib
import json
import os
from pathlib import Path
import sqlite3
from urllib.request import Request, urlopen

ROOT = Path('/home/jetson/workspace/migration-20261115')
def post(payload):
    req = Request(os.environ['SHANYE_RELAY_URL']+'/v1/tasks', data=json.dumps(payload, ensure_ascii=False).encode(), headers={'X-Relay-Token': os.environ['SHANYE_RELAY_TOKEN'], 'Content-Type': 'application/json'})
    with urlopen(req, timeout=20) as response:
        return json.load(response)
def digest(text): return hashlib.sha256(text.encode()).hexdigest()
book='jetson-migration-acceptance-20261115'
texts=[
    '夜色渐深，街道安静下来。\n\n他推开门，看见桌上放着一封信。\n\n　>',
    '他沿着河边的小路慢慢往前走，远处的桥头亮着一盏灯，灯下站着一个穿着旧外套的人，对方没有说话，只是抬起手指了指身后的街巷，那里有一家早已关门的书店，窗户后面却透出微弱的光，像是有人正坐在里面翻阅一本厚厚的旧书，而门前那把生锈的锁也不知什么时候被轻轻打开了。',
]
chapters=[]
for i,text in enumerate(texts):
    ident=f'{book}-{i+1}'; sha=digest(text)
    chapters.append({'id':ident,'book_id':book,'book_title':'Jetson 迁移验收','chapter_index':i,'chapter_number':i+1,'title':f'第{i+1}章 迁移验收','canonical_text':text,'source_sha256':sha,'current_revision':digest(f'{ident}:{sha}:reader-canonical+OpenCC-t2s-v1:reader-sentence-bounded160-v1'),'segments':[]})
request={'book_id':book,'chapter_ids':[c['id'] for c in chapters],'voice_preset_version_id':'clone-9ef173d881f74a49888715b1ed92b24d','mode':'reuse','export':{'speed':1.0,'pause_ms':180,'format':'m4a','normalize_loudness':True},'inline_chapters':chapters}
tts=post({'kind':'tts','dedupe_key':'jetson-migration-acceptance-20261115-v1','payload':{'request':request},'metadata':{'title':'Jetson 迁移验收'}})
with sqlite3.connect('/home/jetson/workspace/shanye-content/content.db') as db:
    b=db.execute('select book_id,title from content order by chars limit 1').fetchone()
content=post({'kind':'content.serve','dedupe_key':'jetson-migration-content-20261115-v1','payload':{'bookId':b[0],'version':'migration-20261115'}})
record={'tts_id':tts['id'],'content_id':content['id'],'content_book':b[0],'request':request}
out=ROOT/'acceptance-input.json'; out.write_text(json.dumps(record,ensure_ascii=False,indent=2));out.chmod(0o600)
print(json.dumps({'tts_id':tts['id'],'content_id':content['id'],'characters':[len(t) for t in texts]},ensure_ascii=False))

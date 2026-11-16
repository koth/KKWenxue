"""Verify the real production chapter, download it and its Android import ZIP."""
import json
from pathlib import Path
import subprocess
import time
import urllib.request
import zipfile

from local.library import LocalLibrary
from tts.audio import measure_loudness
from tts.common import digest, file_digest

BASE = 'http://127.0.0.1:17861'
JOB = '82f9f9ce2777411099a0c4a5550a1749'
ROOT = Path(__file__).resolve().parent

def get(path):
    return json.load(urllib.request.urlopen(BASE + path, timeout=60))

def download(path, target):
    with urllib.request.urlopen(BASE + path, timeout=60) as response, target.open('wb') as output:
        for block in iter(lambda: response.read(1024 * 1024), b''):
            output.write(block)

last = None
while True:
    job = get('/api/v1/jobs/' + JOB)
    progress = job['counts']['completed'] // 10
    if progress != last:
        print(job['state'], job['counts'], flush=True)
        last = progress
    if job['state'] in {'succeeded', 'failed', 'partial_failed', 'cancelled'}:
        break
    time.sleep(6)
assert job['state'] == 'succeeded', (job['state'], job['errors'])
artifact = job['chapters'][0]['artifact_id']
manifest = get('/api/v1/artifacts/' + artifact + '/manifest')
assert manifest['profile']['engine'] == 'cosyvoice3-0.5b-cuda'
assert manifest['profile']['speaker'] == 'clone:9ef173d881f74a49888715b1ed92b24d'
assert manifest['complete'] and manifest['speed'] == 1
(ROOT / 'job.json').write_text(json.dumps(job, ensure_ascii=False, indent=2) + '\n')
(ROOT / '984-cosyvoice-stf.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
audio = ROOT / '984-cosyvoice-stf.m4a'
download('/api/v1/artifacts/' + artifact + '/audio', audio)
assert file_digest(audio) == manifest['audio_sha256']
library = LocalLibrary(Path('app/src/main/assets/shanye.db'))
source = library.payload(manifest['book_id'], [manifest['chapter_id']])[0]
assert source['current_revision'] == manifest['source_revision']
assert source['source_sha256'] == manifest['source_sha256']
original = source['canonical_text'].encode('utf-16-le')
for cue in manifest['cues']:
    selected = original[cue['start_utf16'] * 2:cue['end_utf16'] * 2].decode('utf-16-le')
    assert selected == cue['original_text']
subprocess.run(['ffmpeg', '-v', 'error', '-i', str(audio), '-f', 'null', '-'], check=True)
session = get('/session')['session']
request = urllib.request.Request(BASE + '/api/v1/download-bundles',
    data=json.dumps({'artifact_ids': [artifact]}).encode(),
    headers={'Content-Type': 'application/json', 'X-Local-Session': session,
             'Origin': BASE, 'Idempotency-Key': 'cosyvoice-stf-984-production-bundle-20261115'})
with urllib.request.urlopen(request, timeout=60) as response:
    bundle_id = json.load(response)['id']
while True:
    bundle = get('/api/v1/download-bundles/' + bundle_id)
    if bundle['download_ready']:
        break
    assert bundle['state'] not in {'failed', 'partial_failed', 'cancelled'}, bundle
    time.sleep(2)
archive = ROOT / '984-cosyvoice-stf.zip'
download('/api/v1/download-bundles/' + bundle_id + '/file', archive)
assert file_digest(archive) == bundle['bundle']['sha256']
with zipfile.ZipFile(archive) as z:
    assert z.testzip() is None
    catalogue = json.loads(z.read('catalogue.json'))
report = {
    'job_id': JOB, 'artifact_id': artifact, 'bundle_id': bundle_id,
    'state': job['state'], 'counts': job['counts'], 'duration_ms': manifest['duration_ms'],
    'cues': len(manifest['cues']), 'source_revision_matches_current_android_book': True,
    'all_cue_utf16_text_matches': True, 'full_aac_decode': True,
    'audio_sha256': file_digest(audio), 'zip_sha256': file_digest(archive),
    'zip_crc_valid': True, 'loudness': measure_loudness(audio), 'catalogue': catalogue,
}
(ROOT / 'validation.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
print(json.dumps({k: report[k] for k in ('state', 'counts', 'duration_ms', 'cues', 'loudness', 'bundle_id')}, ensure_ascii=False), flush=True)

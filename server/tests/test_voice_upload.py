import base64
from contextlib import closing
import io
import json
from pathlib import Path
import tempfile
import unittest
import wave

import numpy as np
from fastapi.testclient import TestClient

from server.app import create_app
from server.config import Settings
from server.db import connect
from server.presets import snapshot
from server.tests.fixtures import install_test_voice
from tts.common import digest


def reference_payload():
    audio=io.BytesIO()
    with wave.open(audio,'wb') as wav:
        wav.setnchannels(1);wav.setsampwidth(2);wav.setframerate(44100)
        wav.writeframes((np.sin(np.arange(4*44100)*.03)*3000).astype('<i2').tobytes())
    codes=io.BytesIO();np.save(codes,np.ones((60,10),dtype=np.int64))
    a,c=audio.getvalue(),codes.getvalue()
    return dict(name='新 MP3 音色',ref_text='',audio_sha256=digest(a),codes_sha256=digest(c),
        audio_base64=base64.b64encode(a).decode(),codes_base64=base64.b64encode(c).decode())


class VoiceUploadTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        root=Path(self.temp.name)
        self.settings=Settings(state=root/'state',audio=root/'audio',min_free_bytes=0)
        install_test_voice(self.settings)
        self.client=TestClient(create_app(self.settings))
        self.client.headers['X-Service-Token']=self.settings.token_file.read_text()

    def test_published_upload_is_selectable_immutable_and_idempotent(self):
        payload=reference_payload()
        first=self.client.post('/api/v1/voice-clones/preencoded',json=payload)
        self.assertEqual(201,first.status_code,first.text)
        second=self.client.post('/api/v1/voice-clones/preencoded',json=payload)
        self.assertEqual(first.json()['id'],second.json()['id'])
        presets=self.client.get('/api/v1/presets').json()['items']
        self.assertEqual(1,sum(p['id']==first.json()['id'] for p in presets))
        with closing(connect(self.settings)) as db:
            config=snapshot(db,self.settings,first.json()['id'],None)['generation']
            self.assertEqual(payload['codes_sha256'],config['reference_codes_sha256'])
            self.assertEqual(payload['audio_sha256'],config['ref_audio_sha256'])
            self.assertEqual(0,db.execute('SELECT COUNT(*) FROM jobs').fetchone()[0])

    def test_invalid_upload_never_publishes_a_broken_preset(self):
        manifest=(self.settings.zonos_voice_path/'voices.json').read_bytes()
        payload=reference_payload();payload['codes_sha256']='0'*64
        response=self.client.post('/api/v1/voice-clones/preencoded',json=payload)
        self.assertEqual(422,response.status_code)
        payload=reference_payload()
        invalid=io.BytesIO();np.save(invalid,np.zeros((60,10),dtype=object))
        payload['codes_base64']=base64.b64encode(invalid.getvalue()).decode()
        payload['codes_sha256']=digest(invalid.getvalue())
        self.assertEqual(422,self.client.post('/api/v1/voice-clones/preencoded',json=payload).status_code)
        self.assertEqual(manifest,(self.settings.zonos_voice_path/'voices.json').read_bytes())

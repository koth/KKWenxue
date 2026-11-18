"""Exercise the actual HTTP/process boundary, without GPU or downloaded weights."""
from contextlib import closing
import json
import socket
import sys
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path

from server.config import Settings,ENGINE
from server.db import connect
from server.presets import snapshot
from server.tests.fixtures import install_test_voice
from server.voices import DEFAULT_PRESET_ID,HAORAN_PRESET_ID
from server.zonos_runtime import ZonosEngine,content_report,generation_seed

FAKE='''#!{python}
from http.server import HTTPServer,BaseHTTPRequestHandler
import io,json,math,sys,wave,struct,pathlib,time
port=int(sys.argv[sys.argv.index('--port')+1])
record=pathlib.Path(__file__).with_suffix('.requests')
class H(BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def do_GET(self):
  self.send_response(200);self.end_headers()
  self.wfile.write(json.dumps({{'speakers':[dict(id=n,original_name=n+'.wav',speaker_modes=['reference']) for n in ['Haoran','LinChuan']]}}).encode())
 def do_POST(self):
  req=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
  with record.open('a') as f:f.write(json.dumps(req)+'\\n')
  if req['text']=='hang':time.sleep(10)
  buf=io.BytesIO()
  with wave.open(buf,'wb') as w:
   w.setnchannels(1);w.setsampwidth(2);w.setframerate(44100)
   w.writeframes(b''.join(struct.pack('<h',int(3000*math.sin(i/10))) for i in range(35280)))
  self.send_response(200);self.end_headers();self.wfile.write(buf.getvalue())
HTTPServer(('127.0.0.1',port),H).serve_forever()
'''


class ZonosTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        root=Path(self.temp.name)
        with socket.socket() as sock:
            sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
        self.settings=Settings(state=root/'state',audio=root/'audio',zonos_port=port)
        install_test_voice(self.settings)
        self.settings.zonos_binary.write_text(FAKE.format(python=sys.executable))
        self.engine=ZonosEngine(self.settings,{})
        self.addCleanup(self.engine.close)
        with closing(connect(self.settings)) as db:
            self.config=snapshot(db,self.settings,DEFAULT_PRESET_ID,None)['generation']

    def row(self,text='林川推开木门。',attempt=1):
        return dict(id='sentence',chapter_index=2,segment_index=3,spoken_text=text,attempt_number=attempt)

    def requests(self):
        return [json.loads(s) for s in self.settings.zonos_binary.with_suffix('.requests').read_text().splitlines()]

    def test_resident_native_server_preencoded_voice_and_resampling(self):
        with patch.object(self.engine.verifier,'verify',return_value=dict(accepted=True,differences=[])):
            first=self.engine.generate([self.row()],self.config)[0]
            process=self.engine.process
            second=self.engine.generate([self.row()],self.config)[0]
        self.assertEqual((19200,),first.shape)
        self.assertEqual(first.tolist(),second.tolist())
        self.assertIs(process,self.engine.process)
        req=self.requests()[0]
        self.assertEqual('reference',req['speaker_mode'])
        self.assertTrue(req['laci']);self.assertEqual('auto',req['short_text'])
        self.assertEqual('cmn',req['language'])
        self.assertNotIn('speaker_audio_base64',req)
        self.assertTrue(self.engine.last_execution['sentence']['content_verified'])
        self.assertNotIn('--spk-encoder',process.args)
        self.assertNotIn('--zac-encoder',process.args)

    def test_haoran_and_clone_are_distinct_immutable_snapshots(self):
        with closing(connect(self.settings)) as db:
            haoran=snapshot(db,self.settings,HAORAN_PRESET_ID,None)['generation']
        self.assertNotEqual(haoran['reference_codes_path'],self.config['reference_codes_path'])
        self.assertEqual('zonos:haoran',haoran['speaker'])
        with patch.object(self.engine.verifier,'verify',return_value=dict(accepted=True,differences=[])):
            self.assertEqual((19200,),self.engine.generate([self.row()],haoran)[0].shape)
        self.assertEqual('Haoran',self.requests()[0]['speaker_embedding_id'])
        (self.settings.audio/self.config['reference_codes_path']).write_bytes(b'changed')
        with self.assertRaisesRegex(RuntimeError,'缓存已变更'):
            self.engine.generate([self.row()],self.config)

    def test_omissions_and_hallucinations_are_not_published(self):
        failed=dict(accepted=False,differences=[],gaps=[dict(text='众人')])
        with patch.object(self.engine.verifier,'verify',return_value=failed):
            value=self.engine.generate([self.row()],self.config)[0]
        self.assertIsInstance(value,Exception)
        req=self.requests();self.assertEqual(2,len(req))
        self.assertNotEqual(req[0]['seed'],req[1]['seed'])
        self.assertEqual('failed',self.engine.last_execution['sentence']['state'])
        added=dict(accepted=True,differences=[dict(expected_units=[],actual_units=['duo','yu','zi'])])
        self.assertFalse(content_report(added)['accepted'])
        with patch.object(self.engine.verifier,'verify',side_effect=RuntimeError('QA unavailable')):
            self.assertIsInstance(self.engine.generate([self.row()],self.config)[0],Exception)

    def test_failed_check_retries_once_then_records_verification(self):
        reports=[dict(accepted=False,differences=[]),dict(accepted=True,differences=[])]
        with patch.object(self.engine.verifier,'verify',side_effect=reports):
            value=self.engine.generate([self.row()],self.config)[0]
        self.assertEqual((19200,),value.shape)
        parts=self.engine.last_execution['sentence']['parts']
        self.assertEqual(['rejected-content','completed'],[p['state'] for p in parts])

    def test_port_conflict_does_not_attach_to_another_process(self):
        with socket.socket() as sock:
            sock.bind(('127.0.0.1',self.settings.zonos_port));sock.listen()
            with self.assertRaises(OSError):self.engine.generate([self.row()],self.config)
        self.assertIsNone(self.engine.process)

    def test_timeout_kills_uncertain_native_request_before_retry(self):
        self.settings.zonos_timeout=1
        result=self.engine.generate([self.row('hang')],self.config)[0]
        self.assertIsInstance(result,Exception)
        self.assertIsNone(self.engine.process)

    def test_restart_accepts_time_wait_from_closed_native_server(self):
        with socket.socket() as listener:
            listener.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
            listener.bind(('127.0.0.1',self.settings.zonos_port));listener.listen()
            with socket.create_connection(listener.getsockname()) as client:
                connection,_=listener.accept()
                connection.shutdown(socket.SHUT_WR)
                connection.close()
                self.assertEqual(b'',client.recv(1))
        with patch.object(self.engine.verifier,'verify',return_value=dict(accepted=True,differences=[])):
            self.assertEqual((19200,),self.engine.generate([self.row()],self.config)[0].shape)

    def test_old_engine_rejected_and_attempt_seed_changes(self):
        with self.assertRaisesRegex(RuntimeError,'旧合成配置'):
            self.engine.generate([],{**self.config,'engine':'audio8-realistic-air-cpp'})
        self.assertNotEqual(generation_seed(self.row(),self.config),generation_seed(self.row(attempt=2),self.config))
        self.assertEqual(ENGINE,self.config['engine'])

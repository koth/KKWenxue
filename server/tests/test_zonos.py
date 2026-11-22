"""Exercise the actual HTTP/process boundary, without GPU or downloaded weights."""
from contextlib import closing
import json
import socket
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch
from pathlib import Path

from server.config import Settings,ENGINE
from server.db import connect
from server.presets import snapshot
from server.tests.fixtures import install_test_voice
from server.voices import DEFAULT_PRESET_ID,HAORAN_PRESET_ID
from server.zonos_runtime import (
    ZonosEngine,content_report,generation_seed,VERIFICATION,LEGACY_VERIFICATION,
    SPEECH_PLAN,LEGACY_SPEECH_PLAN,PREVIOUS_SPEECH_PLAN,FIXED_PAUSE_SPEECH_PLAN,
)
from server.speech_quality import compare_transcript, spoken_units

FAKE='''#!{python}
from http.server import HTTPServer,BaseHTTPRequestHandler
import io,json,math,sys,wave,struct,pathlib,time
port=int(sys.argv[sys.argv.index('--port')+1])
record=pathlib.Path(__file__).with_suffix('.requests')
class H(BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def do_GET(self):
  self.send_response(200);self.end_headers()
  names=sorted(p.stem for p in pathlib.Path(sys.argv[sys.argv.index('--tts-default-voices-dir')+1]).glob('*.wav'))
  self.wfile.write(json.dumps({{'speakers':[dict(id=n,original_name=n+'.wav',speaker_modes=['reference']) for n in names]}}).encode())
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

    def test_verifier_handles_adjacent_chinese_number_groups_without_crashing(self):
        text = '九对，十对，十一对，十二对，十三. ..十三！！'
        transcript = '九对十对十一对十二对十三十三'
        units = spoken_units(transcript)
        self.assertEqual(['shi', 'san', 'shi', 'san'], [unit[0] for unit in units[-4:]])
        self.assertEqual([(10, 14)] * 4, [(unit[1], unit[2]) for unit in units[-4:]])
        report = compare_transcript(text, transcript)
        self.assertTrue(report['accepted'], report)
        self.assertEqual([], report['gaps'])
        missing = compare_transcript(text, '九对十对十一对十二对')
        self.assertFalse(missing['accepted'], missing)

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

    def test_newly_uploaded_voice_reloads_at_the_next_batch_boundary(self):
        from server.voice_publish import publish_reference
        from server.tests.test_voice_upload import reference_payload
        with patch.object(self.engine.verifier,'verify',return_value=dict(accepted=True,differences=[])):
            self.engine.generate([self.row()],self.config)
            previous=self.engine.process
            with closing(connect(self.settings)) as db:
                created=publish_reference(db,self.settings,reference_payload())
                config=snapshot(db,self.settings,created['id'],None)['generation']
            result=self.engine.generate([self.row()],config)[0]
        self.assertEqual((19200,),result.shape)
        self.assertIsNot(previous,self.engine.process)
        self.assertEqual(config['native_voice_name'].removesuffix('.wav'),self.requests()[-1]['speaker_embedding_id'])

    def test_native_request_uses_cleaned_text_but_verifier_uses_original(self):
        text='她问：“《山海经》，，，你看过吗？！”'
        row=self.row(text)
        report=compare_transcript(text,'她问山海经你看过吗')
        with patch.object(self.engine.verifier,'verify',return_value=report) as verify:
            results=self.engine.generate([row],self.config)
        self.assertEqual((19200,),results[0].shape)
        self.assertEqual(['她问：山海经，你看过吗？'],[req['text'] for req in self.requests()])
        verify.assert_called_once()
        self.assertEqual(text,verify.call_args.args[0])
        self.assertEqual(text,row['spoken_text'])
        execution=self.engine.last_execution[row['id']]
        self.assertEqual(SPEECH_PLAN,execution['speech_plan'])
        self.assertEqual('她问：山海经，你看过吗？',execution['native_text'])

    def test_title_space_and_comma_are_real_pcm_silence_with_one_coverage_check(self):
        import numpy as np
        for text, title, pause_ms in [('第1168章 看不见的人', True, 500), ('门外雨停了，林川放下书。', False, 250)]:
            row = {**self.row(text), 'sentence_index': -1 if title else 0}
            with patch.object(self.engine.verifier,'verify',return_value=dict(accepted=True,differences=[])) as verify:
                samples = self.engine.generate([row],{**self.config,'speech_plan':FIXED_PAUSE_SPEECH_PLAN})[0]
            self.assertEqual(19200 * 2 + pause_ms * 24, len(samples))
            self.assertTrue(np.all(samples[19200:19200+pause_ms*24] == 0))
            verify.assert_called_once()
            self.assertEqual(text,verify.call_args.args[0])
            self.assertIs(samples,verify.call_args.args[1])
            execution = self.engine.last_execution[row['id']]
            self.assertEqual(pause_ms,execution['pauses'][0]['duration_ms'])
            self.assertNotEqual(execution['parts'][0]['seed'],execution['parts'][1]['seed'])

    def test_previous_punctuation_plan_remains_a_frozen_single_request(self):
        text = '门外雨停了，林川放下书。'
        with patch.object(self.engine.verifier,'verify',return_value=dict(accepted=True,differences=[])):
            samples=self.engine.generate([self.row(text)],{**self.config,'speech_plan':PREVIOUS_SPEECH_PLAN})[0]
        self.assertEqual((19200,),samples.shape)
        self.assertEqual([text],[req['text'] for req in self.requests()])
        self.assertEqual([],self.engine.last_execution['sentence']['pauses'])

    def test_failure_after_a_completed_clause_never_returns_partial_audio(self):
        self.settings.zonos_timeout=1
        result=self.engine.generate([self.row('门外雨停了，hang')],{**self.config,'speech_plan':FIXED_PAUSE_SPEECH_PLAN})[0]
        self.assertIsInstance(result,Exception)
        self.assertIsNone(self.engine.process)
        self.assertEqual('failed',self.engine.last_execution['sentence']['state'])
        self.assertEqual(1,len(self.engine.last_execution['sentence']['parts']))

    def test_natural_plan_keeps_whole_sentence_and_rechecks_after_audio_adjustment(self):
        import numpy as np
        text='门外雨停了，林川放下书。'
        passed=dict(accepted=True,differences=[])
        adjusted=np.ones(18000,dtype=np.float32)*.1
        with patch('server.zonos_runtime.natural_pauses',return_value=(adjusted,[dict(changed=True)])), \
             patch.object(self.engine.verifier,'verify',return_value=passed) as verify:
            result=self.engine.generate([self.row(text)],self.config)[0]
        self.assertIs(adjusted,result)
        self.assertEqual(2,verify.call_count)
        self.assertEqual([text],[req['text'] for req in self.requests()])
        self.assertTrue(self.engine.last_execution['sentence']['content_verified'])

    def test_natural_pause_adjustment_cannot_publish_a_failed_second_content_check(self):
        import numpy as np
        passed=dict(accepted=True,differences=[])
        failed=dict(accepted=False,differences=[],gaps=[dict(text='林川')])
        with patch('server.zonos_runtime.natural_pauses',return_value=(np.ones(12000),[])), \
             patch.object(self.engine.verifier,'verify',side_effect=[passed,failed]):
            result=self.engine.generate([self.row()],self.config)[0]
        self.assertIsInstance(result,ValueError)
        self.assertIn('停顿调整后',str(result))

    def test_frozen_legacy_speech_plan_keeps_original_punctuation_rules(self):
        row=self.row('《山海经》……')
        config={**self.config,'speech_plan':LEGACY_SPEECH_PLAN}
        with patch.object(self.engine.verifier,'verify',return_value=dict(accepted=True,differences=[])):
            self.assertEqual((19200,),self.engine.generate([row],config)[0].shape)
        self.assertEqual(['《山海经》，'],[req['text'] for req in self.requests()])
        self.assertEqual(LEGACY_SPEECH_PLAN,self.engine.last_execution[row['id']]['speech_plan'])

    def test_cleaned_punctuation_does_not_hide_real_omissions(self):
        text='（《山海经》），、、、林川推开木门。'
        report=compare_transcript(text,'山海经')
        with patch.object(self.engine.verifier,'verify',return_value=report):
            result=self.engine.generate([self.row(text)],self.config)[0]
        self.assertEqual('山海经，林川推开木门。',''.join(req['text'] for req in self.requests()))
        self.assertIsInstance(result,ValueError)
        self.assertIn('林川推开木门',str(result))
        execution=self.engine.last_execution['sentence']
        self.assertEqual('rejected-content',execution['parts'][0]['state'])
        self.assertIn('连续漏读较长内容',execution['verification_checks'][0]['rejection_reasons'])

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
        req=self.requests();self.assertEqual(1,len(req))
        self.assertEqual('failed',self.engine.last_execution['sentence']['state'])
        added=dict(accepted=True,differences=[dict(expected_units=[],actual_units=['duo','yu','zi'])])
        self.assertFalse(content_report(added)['accepted'])
        with patch.object(self.engine.verifier,'verify',side_effect=RuntimeError('QA unavailable')):
            self.assertIsInstance(self.engine.generate([self.row()],self.config)[0],Exception)

    def test_small_difference_passes_once_even_for_queued_legacy_config(self):
        report=compare_transcript('林川推开木门。','林川推木门。')
        with patch.object(self.engine.verifier,'verify',return_value=report):
            value=self.engine.generate([self.row()],{**self.config,'verification':LEGACY_VERIFICATION})[0]
        self.assertEqual((19200,),value.shape)
        execution=self.engine.last_execution['sentence']
        self.assertEqual(['completed'],[p['state'] for p in execution['parts']])
        self.assertTrue(execution['content_tolerated'])
        self.assertEqual(VERIFICATION,execution['verification_policy'])
        self.assertEqual(LEGACY_VERIFICATION,execution['requested_verification_policy'])
        self.assertEqual(1,len(self.requests()))

    def test_minor_spelling_number_letter_differences_are_warnings_only(self):
        for text,transcript in [
            ('还上这户人家的欠款比较难。','还上这户人家的欠款比较。'),
            ('我也就放心了，期待新剧ing）','我也就放心了期待新剧应'),
            ('等登上那列鬼电车，你先去456号车厢搜索，我帮你盯着这里。','等登上那列鬼电车你先去四十五六日车厢搜索我帮你盯着这里'),
            ('呼，呼，一间废弃的办公室中，年轻的女孩喘着粗气。','呼一间废弃的办公室中年轻的女孩喘着粗气'),
            ('林川推开木门，看着桌上那封信。','林川推开木门看着桌上那本信'),
        ]:
            with self.subTest(text=text):
                report=content_report(compare_transcript(text,transcript))
                self.assertTrue(report['accepted'],report)
                self.assertTrue(report['tolerated'],report)

    def test_large_omissions_and_unrelated_hallucinations_still_fail(self):
        for text,transcript in [
            ('我已经把他们欠下的款项全都还清了。','我我。'),
            ('林川推开木门，看着桌上那封信。','林川推开木门。'),
            ('山本春奈忙道。','亚马莫特苦群戴美琪是吉富'),
            ('呼。','小朋友大家好欢迎收听今天的节目'),
            ('现在开始。',''),
        ]:
            with self.subTest(text=text):
                report=content_report(compare_transcript(text,transcript))
                self.assertFalse(report['accepted'],report)
                self.assertTrue(report['rejection_reasons'])

    def test_port_conflict_does_not_attach_to_another_process(self):
        self.settings.zonos_timeout=.02
        with socket.socket() as sock:
            sock.bind(('127.0.0.1',self.settings.zonos_port));sock.listen()
            with self.assertRaises(OSError):self.engine.generate([self.row()],self.config)
        self.assertIsNone(self.engine.process)

    def test_startup_waits_for_transient_port_release_without_generating_retries(self):
        with socket.socket() as sock:
            sock.bind(('127.0.0.1',self.settings.zonos_port));sock.listen()
            timer=threading.Timer(.05,sock.close);timer.start()
            try:
                with patch.object(self.engine.verifier,'verify',return_value=dict(accepted=True,differences=[])):
                    self.assertEqual((19200,),self.engine.generate([self.row()],self.config)[0].shape)
            finally:timer.join()
        self.assertEqual(1,len(self.requests()))

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
        with self.assertRaisesRegex(RuntimeError,'未知的朗读策略'):
            self.engine.generate([],{**self.config,'speech_plan':'unknown'})
        self.assertNotEqual(generation_seed(self.row(),self.config),generation_seed(self.row(attempt=2),self.config))
        self.assertNotEqual(generation_seed(self.row(),self.config),generation_seed({**self.row(),'retry_round':1},self.config))
        self.assertEqual(ENGINE,self.config['engine'])

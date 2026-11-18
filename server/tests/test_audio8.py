"""Exercise the native process protocol without requiring CUDA or model weights."""
from contextlib import closing
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import numpy as np

from server.audio8_runtime import Audio8Engine, generation_seed, minimum_audio_seconds
from server.config import Settings
from server.audio8_runtime import ENGINE, REVISION
from server.db import connect
from server.presets import snapshot as active_snapshot
from server.tests.fixtures import install_test_voice
from server.voices import DEFAULT_PRESET_ID
from server.worker import Worker
from server.speech_plan import SPEECH_PLAN, speech_fragments
from server.speech_quality import VERIFICATION
from tts.common import digest, file_digest


FAKE_NATIVE = '''#!{python}
import json, math, pathlib, sys, time, wave, struct
print(json.dumps({{"stage":"ready", "engine":{{"gpu":"test CUDA", "sample_rate":44100}}}}), flush=True)
for line in sys.stdin:
    req=json.loads(line)
    assert req["split_sentences"] is False
    if req["text"] == "error":
        print(json.dumps({{"stage":"error", "error":"invalid request"}}), flush=True)
        continue
    if req["text"] == "hang":
        time.sleep(10)
    directory=pathlib.Path(req["output_dir"])
    if req["text"].startswith("native-short:") or (req["text"] == "一次短句，" and req["seed"] == 200045):
        report={{"state":"failed", "error":"Complete audio is below requested minimum duration",
                "fragments":[{{"audio_seconds":.5 if req["text"].startswith("native-short:") else .1,"generation_seconds":.2,"decode_and_save_seconds":.1}}]}}
        (directory/"report.json").write_text(json.dumps(report))
        print(json.dumps({{"stage":"error","error":report["error"]}}), flush=True)
        continue
    with wave.open(str(directory/"audio8.wav"), "wb") as output:
        output.setnchannels(1); output.setsampwidth(2); output.setframerate(44100)
        output.writeframes(b"".join(struct.pack("<h", int(3000*math.sin(i/10))) for i in range(35280)))
    report={{"state":"completed", "generation_seconds":.2, "decode_and_save_seconds":.1, "audio_seconds":.8}}
    (directory/"report.json").write_text(json.dumps(report))
    print(json.dumps({{"stage":"completed"}}), flush=True)
'''


def snapshot(db,settings,preset_id,export):
    """Historical snapshots for the retired adapter's protocol regression tests."""
    value=active_snapshot(db,settings,preset_id,export)
    value['generation'].update(engine=ENGINE,revision=REVISION,
        inference_revision=settings.audio8_inference_revision,
        speech_plan=SPEECH_PLAN,verification=VERIFICATION if settings.audio8_verify else None)
    return value


class Audio8Tests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        self.settings = Settings(state=root/"state", audio=root/"audio", audio8_verify=False)
        install_test_voice(self.settings)
        self.settings.audio8_binary.write_text(FAKE_NATIVE.format(python=sys.executable))
        with closing(connect(self.settings)) as db:
            self.config = snapshot(db, self.settings, DEFAULT_PRESET_ID, None)["generation"]
        key = digest(json.dumps([REVISION, self.config["ref_audio_sha256"], self.config["ref_text"]], ensure_ascii=False))
        voice_dir = self.settings.audio / "audio8-voices" / key
        voice_dir.mkdir(parents=True)
        np.save(voice_dir / "reference_codes.npy", np.zeros((10, 20), dtype="int64"))
        (voice_dir / "voice.json").write_text(json.dumps({
            "codes": "reference_codes.npy", "text": self.config["ref_text"],
            "reference_sha256": self.config["ref_audio_sha256"],
            "codes_sha256": file_digest(voice_dir / "reference_codes.npy"),
        }))
        self.engine = Audio8Engine(self.settings, {})
        self.addCleanup(self.engine.close)

    def row(self, text="测试原文。"):
        return {"id":"segment1", "chapter_index":2, "segment_index":3, "spoken_text":text}

    def test_native_process_is_reused_and_wav_is_resampled(self):
        from server.zonos_runtime import ZonosEngine
        self.assertIsInstance(Worker(self.settings).engine, ZonosEngine)
        result = self.engine.generate([self.row()], self.config)
        self.assertEqual((19200,), result[0].shape)
        self.assertTrue(np.isfinite(result[0]).all())
        pid = self.engine.process.pid
        result = self.engine.generate([self.row()], self.config)
        self.assertEqual(pid, self.engine.process.pid)
        self.assertEqual(200045, self.engine.last_execution["segment1"]["seed"])
        self.assertEqual([], list((self.settings.audio / ".audio8-work").iterdir()))

    def test_errors_and_timeouts_close_process_for_retry(self):
        result = self.engine.generate([self.row("error")], self.config)
        self.assertIsInstance(result[0], RuntimeError)
        self.assertIsNone(self.engine.process)
        self.settings.audio8_timeout = 1
        result = self.engine.generate([self.row("hang")], self.config)
        self.assertIsInstance(result[0], TimeoutError)
        self.assertIsNone(self.engine.process)
        self.assertEqual((19200,), self.engine.generate([self.row()], self.config)[0].shape)

    def test_retry_seeds_are_distinct_stable_and_preserve_first_pass(self):
        row = self.row()
        seeds = [generation_seed({**row, "attempt_number": i}, self.config) for i in (1, 2, 3)]
        self.assertEqual(200045, seeds[0])
        self.assertEqual(3, len(set(seeds)))
        self.assertEqual(seeds[1], generation_seed({**row, "attempt_number": 2}, self.config))
        children = [{**row, "depth": 1, "id": id} for id in ("child1", "child2")]
        self.assertEqual(3, len({seeds[0], *(generation_seed(r, self.config) for r in children)}))
        self.engine.generate([{**row, "attempt_number": 2}], self.config)
        self.assertEqual(seeds[1], self.engine.last_execution[row["id"]]["seed"])

    def test_native_short_audio_failure_keeps_timings_and_does_not_publish_audio(self):
        text = "native-short:雨水落在泥泞的山路上，他咬着牙继续向前跑，身后的脚步声始终没有停下，令他心惊不已。"
        legacy = {k: v for k, v in self.config.items() if k != "speech_plan"}
        result = self.engine.generate([self.row(text)], legacy)[0]
        self.assertIsInstance(result, RuntimeError)
        self.assertIn("提前结束", str(result))
        self.assertIn("实际 0.50 秒", str(result))
        actual = self.engine.last_execution["segment1"]
        self.assertEqual("failed", actual["state"])
        self.assertEqual(.5, actual["audio_seconds"])
        self.assertEqual(.2, actual["generation_seconds"])
        self.assertEqual(.1, actual["decode_seconds"])
        self.assertEqual(minimum_audio_seconds(text), actual["minimum_audio_seconds"])
        self.assertEqual([], list((self.settings.audio / ".audio8-work").iterdir()))
        # The native process handled this request error and remains healthy.
        pid = self.engine.process.pid
        self.assertTrue(actual["parts"][0]["process_reused"])
        self.assertEqual((19200,), self.engine.generate([self.row()], self.config)[0].shape)
        self.assertEqual(pid, self.engine.process.pid)

    def test_clause_audio_is_concatenated_once_and_partial_result_is_rejected(self):
        self.assertEqual(SPEECH_PLAN, self.config["speech_plan"])
        row = self.row("推开木门，四周安静。")
        result = self.engine.generate([row], self.config)[0]
        self.assertEqual((38400,), result.shape)
        actual = self.engine.last_execution[row["id"]]
        self.assertEqual(["推开木门，", "四周安静。"], [p["text"] for p in actual["parts"]])
        self.assertEqual([200045,200046], [p["seed"] for p in actual["parts"]])
        self.assertEqual(1.6, actual["audio_seconds"])
        self.assertEqual(.4, actual["generation_seconds"])
        # The first piece succeeds; no partial audio may escape when the next
        # piece fails. The queue retries the original durable text segment.
        result = self.engine.generate([self.row("推开木门，error")], self.config)[0]
        self.assertIsInstance(result, RuntimeError)
        self.assertEqual("failed", self.engine.last_execution["segment1"]["state"])
        self.assertEqual([], list((self.settings.audio / ".audio8-work").iterdir()))

    def test_only_the_short_failed_phrase_is_retried_and_not_included_in_output(self):
        row = self.row("一次短句，后续短句。")
        result = self.engine.generate([row], self.config)[0]
        self.assertEqual((38400,), result.shape)
        actual = self.engine.last_execution[row["id"]]
        self.assertEqual(["一次短句，", "一次短句，", "后续短句。"], [p["text"] for p in actual["parts"]])
        self.assertEqual(["failed", "completed", "completed"], [p["state"] for p in actual["parts"]])
        self.assertEqual(1, actual["part_retries"])
        self.assertTrue(actual["parts"][0]["process_reused"])
        self.assertNotEqual(actual["parts"][0]["seed"], actual["parts"][1]["seed"])
        self.assertEqual(1.6, actual["audio_seconds"])
        self.assertEqual(.6, round(actual["generation_seconds"], 3))

    def test_fragment_plan_keeps_text_quotes_numbers_and_stutter(self):
        text = "　　他問：『現在是12:30，價錢1,000元！』\n😀她說：清水組長，它……它還在後面！"
        parts = speech_fragments(text)
        self.assertEqual(text, "".join(parts))
        self.assertIn("『現在是12:30，", parts)
        self.assertIn("價錢1,000元！』", parts)
        self.assertIn("它……它還在後面！", parts)
        self.assertTrue(all(any(c.isalnum() for c in p) for p in parts))
        run = "很長的沒有標點的中文小說內容" * 10
        parts = speech_fragments(run)
        self.assertEqual(run, "".join(parts))
        self.assertTrue(all(len(p) <= 32 for p in parts))
        self.assertEqual([], speech_fragments("　 >　"))

    def test_content_check_repairs_only_the_missing_phrase(self):
        row=self.row('推开木门，四周安静。')
        config={**self.config,'verification':VERIFICATION}
        failed=dict(accepted=False,gaps=[dict(start=5,end=7,text='四周')])
        isolated=dict(accepted=False,gaps=[dict(start=0,end=2,text='四周')])
        with patch.object(self.engine.verifier,'verify',side_effect=[failed,isolated,dict(accepted=True)]) as verify:
            result=self.engine.generate([row],config)[0]
        self.assertEqual((38400,),result.shape)
        actual=self.engine.last_execution[row['id']]
        self.assertTrue(actual['content_verified'])
        self.assertEqual(1,actual['content_retries'])
        self.assertEqual(['推开木门，','四周安静。','四周安静。'],[p['text'] for p in actual['parts']])
        self.assertEqual(['completed','rejected-content','completed'],[p['state'] for p in actual['parts']])
        self.assertNotEqual(actual['parts'][1]['seed'],actual['parts'][2]['seed'])
        self.assertEqual(1.6,actual['audio_seconds'])
        self.assertEqual(3,verify.call_count)

    def test_uncertain_recognition_is_confirmed_in_isolation(self):
        row=self.row('推开木门，四周安静。')
        config={**self.config,'verification':VERIFICATION}
        failed=dict(accepted=False,gaps=[dict(start=5,end=6,text='四')])
        with patch.object(self.engine.verifier,'verify',side_effect=[failed,dict(accepted=True)]):
            result=self.engine.generate([row],config)[0]
        self.assertEqual((38400,),result.shape)
        actual=self.engine.last_execution[row['id']]
        self.assertEqual(2,len(actual['parts']))
        self.assertTrue(actual['content_verified'])
        self.assertEqual('isolated-confirmation',actual['verification_basis'])

    def test_unverified_or_incomplete_content_never_returns_partial_audio(self):
        row=self.row('推开木门，四周安静。')
        config={**self.config,'verification':VERIFICATION}
        failed=dict(accepted=False,gaps=[dict(start=5,end=7,text='四周')])
        with patch.object(self.engine.verifier,'verify',return_value=failed):
            result=self.engine.generate([row],config)[0]
        self.assertIsInstance(result,Exception)
        self.assertIn('内容校验未通过',str(result))
        self.assertEqual('failed',self.engine.last_execution[row['id']]['state'])
        self.assertIsNotNone(self.engine.process)
        with patch.object(self.engine.verifier,'verify',side_effect=RuntimeError('verifier unavailable')):
            result=self.engine.generate([row],config)[0]
        self.assertIsInstance(result,Exception)
        self.assertEqual([],list((self.settings.audio/'.audio8-work').iterdir()))

    def test_legacy_policy_stays_frozen_and_new_snapshot_tracks_verifier(self):
        row=self.row('我...我来了。')
        self.engine.generate([row],{**self.config,'speech_plan':'clauses32-v1'})
        self.assertEqual(row['spoken_text'],self.engine.last_execution[row['id']]['parts'][0]['native_text'])
        self.engine.generate([row],self.config)
        self.assertEqual('我，我来了。',self.engine.last_execution[row['id']]['parts'][0]['native_text'])
        self.settings.audio8_verify=True
        with closing(connect(self.settings)) as db:
            current=snapshot(db,self.settings,DEFAULT_PRESET_ID,None)['generation']
        self.assertEqual(VERIFICATION,current['verification'])
        self.assertNotEqual(current,self.config)

    def test_fused_setting_matches_native_flag_and_new_job_snapshot(self):
        for enabled, revision in ((False, "expanded-kv-v3"), (True, "fused-decode-v6")):
            with self.subTest(enabled=enabled):
                self.engine.close()
                self.settings.audio8_fused_kernels = enabled
                with closing(connect(self.settings)) as db:
                    config = snapshot(db, self.settings, DEFAULT_PRESET_ID, None)["generation"]
                self.assertEqual(revision, config["inference_revision"])
                self.assertEqual((19200,), self.engine.generate([self.row()], config)[0].shape)
                self.assertEqual(enabled, "--fused-kernels" in self.engine.process.args)

    def test_cached_voice_avoids_hashing_and_still_detects_changed_files(self):
        with patch("server.audio8_runtime.file_digest", wraps=file_digest) as hashed:
            voice = self.engine._voice(self.config)
            self.assertEqual(2, hashed.call_count)
            self.assertEqual(voice, self.engine._voice(self.config))
            self.assertEqual(2, hashed.call_count)
            # A changed manifest must be read and validated again.
            voice.write_text(voice.read_text()+"\n")
            self.engine._voice(self.config)
            self.assertEqual(4, hashed.call_count)
            reference = self.settings.audio / self.config["ref_audio_path"]
            reference.write_bytes(b"changed recording")
            with self.assertRaisesRegex(RuntimeError, "校验"):
                self.engine._voice(self.config)

    def test_changed_codes_invalidate_voice_and_require_reencoding(self):
        voice = self.engine._voice(self.config)
        (voice.parent / "reference_codes.npy").write_bytes(b"invalid codes")
        with patch("server.audio8_runtime.subprocess.run", side_effect=RuntimeError("encoder invoked")) as encoder:
            with self.assertRaisesRegex(RuntimeError, "encoder invoked"):
                self.engine._voice(self.config)
            self.assertEqual(1, encoder.call_count)

    def test_premature_eos_is_not_accepted_as_a_complete_long_passage(self):
        text = "雨水落在泥泞的山路上，他咬着牙继续向前跑，身后的脚步声始终没有停下，令他心惊不已。"
        result = self.engine.generate([self.row(text)], self.config)
        self.assertIsInstance(result[0], RuntimeError)
        self.assertIn("提前结束", str(result[0]))
        self.assertEqual(0, minimum_audio_seconds("你好。"))
        self.assertEqual(0, minimum_audio_seconds("A long English passage should not count each letter as a syllable."))

    def test_legacy_config_missing_transcript_and_changed_audio_are_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "旧合成引擎"):
            self.engine.generate([], {"engine":"cosyvoice3-0.5b-cuda"})
        with self.assertRaisesRegex(RuntimeError, "参考文本"):
            self.engine.generate([], {**self.config, "ref_text":""})
        with self.assertRaisesRegex(RuntimeError, "校验"):
            self.engine.generate([], {**self.config, "ref_audio_sha256":"changed"})
        self.assertIsNone(self.engine.process)
        self.assertEqual(ENGINE, self.config["engine"])
        self.assertNotIn("instruct", self.config)


if __name__ == "__main__":
    unittest.main()

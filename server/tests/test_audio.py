import tempfile
import unittest
from pathlib import Path

import numpy as np
import soundfile as sf

from tts.audio import render, write_flac, measure_loudness
from tts.common import file_digest


class LoudnessTests(unittest.TestCase):
    def test_title_gap_counts_existing_quiet_edges_instead_of_adding_them(self):
        title={**self.clip('heading'), 'kind':'chapter_title'}
        body=self.clip('body')
        result=render([title,body],self.root/'title.m4a',1,180,'title','book',natural_title_pause=True)
        self.assertEqual(150,result['cues'][0]['start_ms']-result['title_cues'][0]['end_ms'])
        self.assertEqual(4150,result['duration_ms'])

    def test_title_gap_can_remove_excess_quiet_without_shortening_speech(self):
        title={**self.clip('heading'), 'kind':'chapter_title'}
        body=self.clip('body')
        for clip in [title,body]:
            samples,rate=sf.read(clip['path'],dtype='float32')
            samples=np.concatenate((np.zeros(7200),samples,np.zeros(7200)))
            write_flac(clip['path'],samples)
        result=render([title,body],self.root/'title-trim.m4a',1,180,'title','book',natural_title_pause=True)
        self.assertEqual(result['title_cues'][0]['end_ms'],result['cues'][0]['start_ms'])
        self.assertEqual(4750,result['duration_ms'])

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def clip(self, name, amplitude=0.025, duration=2.0):
        path = self.root / (name + ".flac")
        samples = amplitude * np.sin(
            np.arange(round(duration * 24000)) * 2 * np.pi * 310 / 24000
        )
        # Known silent boundaries let us detect processing delay as well as duration drift.
        samples[:2400] = 0
        samples[-2400:] = 0
        write_flac(path, samples)
        return {
            "path": path,
            "segment_id": name,
            "sentence_index": 0,
            "sentence_start_utf16": 0,
            "sentence_end_utf16": 3,
            "start_utf16": 0,
            "end_utf16": 3,
            "original_text": "你好。",
            "spoken_text": "你好。",
        }

    def test_quiet_and_loud_exports_match_loudness_without_moving_cues(self):
        quiet, loud = self.clip("quiet", 0.025), self.clip("loud", 0.6)
        before = file_digest(quiet["path"])
        raw = render([quiet, quiet], self.root / "raw.m4a", 1, 180, "test", "test")
        normalized = render(
            [quiet, quiet],
            self.root / "normal.m4a",
            1,
            180,
            "test",
            "test",
            normalize_loudness=True,
        )
        other = render(
            [loud, loud],
            self.root / "loud.m4a",
            1,
            180,
            "test",
            "test",
            normalize_loudness=True,
        )
        self.assertGreater(
            normalized["loudness"]["output_lufs"]
            - normalized["loudness"]["input_lufs"],
            10,
        )
        self.assertAlmostEqual(-18, normalized["loudness"]["output_lufs"], delta=1)
        self.assertAlmostEqual(
            other["loudness"]["output_lufs"],
            normalized["loudness"]["output_lufs"],
            delta=1,
        )
        self.assertLessEqual(normalized["loudness"]["output_true_peak_db"], -1)
        self.assertEqual(raw["cues"], normalized["cues"])
        self.assertEqual(raw["duration_ms"], normalized["duration_ms"])
        self.assertEqual(before, file_digest(quiet["path"]))
        self.assertNotIn("loudness", raw)
        import subprocess

        decoded = self.root / "decoded.wav"
        subprocess.run(
            [
                "ffmpeg",
                "-v",
                "error",
                "-i",
                str(self.root / "normal.m4a"),
                str(decoded),
            ],
            check=True,
        )
        samples, rate = sf.read(decoded)
        for cue in normalized["cues"]:
            start = round(cue["start_ms"] * rate / 1000)
            # Initial silence remains initial silence; speech is not shifted into the gap.
            self.assertLess(np.max(np.abs(samples[start : start + 1000])), 0.005)
            self.assertGreater(
                np.max(np.abs(samples[start + 3000 : start + 6000])), 0.05
            )

    def test_sub_400ms_audio_is_not_rejected_or_padded(self):
        clip = self.clip("short", 0.025, 0.3)
        result = render(
            [clip],
            self.root / "short.m4a",
            1,
            0,
            "test",
            "test",
            normalize_loudness=True,
        )
        self.assertEqual(300, result["duration_ms"])
        self.assertEqual("short-audio-peak-gain", result["loudness"]["mode"])
        self.assertLessEqual(measure_loudness(self.root / "short.m4a")["input_tp"], -1)

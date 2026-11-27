import unittest

from server.prosody import pause_fragments, natural_pauses
from server.text_normalization import normalize_tts_text


class ProsodyTests(unittest.TestCase):
    def sample(self, gap_ms):
        import numpy as np
        rate=24000
        tone=(.1*np.sin(np.arange(4800)*.08)).astype(np.float32)
        samples=np.concatenate((np.zeros(2400,dtype=np.float32),tone,
                                np.zeros(gap_ms*24,dtype=np.float32),tone,
                                np.zeros(2400,dtype=np.float32)))
        return samples,dict(tokens=['门','开'],timestamps=[.12,.3+gap_ms/1000])

    def test_natural_pauses_reduce_total_existing_gap_without_resynthesizing_words(self):
        import numpy as np
        samples,alignment=self.sample(600)
        result,changes=natural_pauses(samples,'门，开。',alignment)
        self.assertLess(len(result),len(samples)-24000*.4)
        self.assertEqual(1,len(changes))
        self.assertTrue(changes[0]['changed'])
        self.assertLessEqual(changes[0]['duration_ms'],180)
        self.assertTrue(np.array_equal(samples[:7200],result[:7200]))
        self.assertTrue(np.array_equal(samples[-7200:],result[-7200:]))

    def test_natural_pauses_keep_good_native_gap_and_do_not_cut_connected_speech(self):
        samples,alignment=self.sample(120)
        result,changes=natural_pauses(samples,'门，开。',alignment)
        self.assertIs(samples,result)
        self.assertFalse(any(change['changed'] for change in changes))
        samples,alignment=self.sample(0)
        result,changes=natural_pauses(samples,'门，开。',alignment)
        self.assertIs(samples,result)
        self.assertEqual([],changes)

    def test_unaligned_or_incomplete_asr_never_changes_the_audio(self):
        samples,alignment=self.sample(600)
        for report in (None,dict(tokens=['门'],timestamps=[]),dict(tokens=['门','外'],timestamps=[.12,.9])):
            result,changes=natural_pauses(samples,'门，开。',report)
            self.assertIs(samples,result)
            self.assertEqual([],changes)

    def test_chapter_number_and_heading_have_a_pause_without_changing_words(self):
        parts = pause_fragments('第1168章 看不见的人', title=True)
        self.assertEqual(['第1168章 ', '看不见的人'], [part['text'] for part in parts])
        self.assertEqual([500, 0], [part['pause_ms'] for part in parts])
        parts = pause_fragments('第 1168 章 看不见的人', title=True)
        self.assertEqual(['第1168章 ', '看不见的人'], [part['text'] for part in parts])
        parts = pause_fragments('第1233章意外', title=True)
        self.assertEqual(['第1233章 ', '意外'], [part['text'] for part in parts])

    def test_commas_colons_and_semicolons_get_distinct_internal_gaps(self):
        text = '他说：雨停了，先出门；再回来。'
        parts = pause_fragments(text)
        self.assertEqual(text, ''.join(part['text'] for part in parts))
        self.assertEqual([300, 250, 400, 0], [part['pause_ms'] for part in parts])
        self.assertEqual(1, len(pause_fragments('雨停了，')))

    def test_english_words_grouped_numbers_decimals_times_and_urls_stay_together(self):
        text = 'New York at 12:30 costs 1,234.56 on https://example.com'
        self.assertEqual([dict(text=text, pause_ms=0, reason=None)], pause_fragments(text, title=True))
        parts = pause_fragments('编号1,2,3，各取1,000个。')
        self.assertEqual(['编号1,', '2,', '3，', '各取1,000个。'], [part['text'] for part in parts])

    def test_punctuation_clusters_short_words_and_wrappers_do_not_lose_content(self):
        text = normalize_tts_text('她说：“不，，，先等等！再说。”')
        parts = pause_fragments(text)
        self.assertEqual(text, ''.join(part['text'] for part in parts))
        self.assertTrue(all(any(char.isalnum() for char in part['text']) for part in parts))
        self.assertEqual([], pause_fragments('，……'))

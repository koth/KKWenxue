import unittest
from importlib.util import find_spec

from server.speech_quality import compare_transcript, native_speech_text


@unittest.skipUnless(find_spec('pypinyin') and find_spec('cn2an'), 'Install server/requirements-qa.txt for content comparison tests')
class SpeechQualityTests(unittest.TestCase):
    def test_homophones_and_numbers_are_not_missing_words(self):
        for text, transcript in [
            ('楊逍來了。','杨潇来了。'),
            ('还剩下150万。','还剩下一百五十万。'),
            ('两个5500。','两个五千五。'),
            ('价格3.14元。','价格三点一四元。'),
            ('1500的样子。','幺五零零的样子。'),
        ]:
            with self.subTest(text=text):
                self.assertTrue(compare_transcript(text,transcript)['accepted'])

    def test_real_middle_and_tail_omissions_are_rejected_with_source_offsets(self):
        for text, transcript, missing in [
            ('我...我已经还他们一些了，','我我。','已经还他们一些了'),
            ('他还要取出自己珍藏的好酒款待众人。','他还要取出自己珍藏的好酒款。','待众人'),
            ('还上这户人家的欠款比较难，','还上这户人家的欠款比较，','难'),
            ('原本还表现得态度不耐烦，','原本还表现的态度，','不耐烦'),
        ]:
            with self.subTest(text=text):
                result=compare_transcript(text,transcript)
                self.assertFalse(result['accepted'])
                self.assertIn(missing,''.join(g['text'] for g in result['gaps']))
                for gap in result['gaps']:
                    self.assertEqual(gap['text'],text[gap['start']:gap['end']])
        self.assertFalse(compare_transcript('现在开始。','')['accepted'])

    def test_ellipsis_changes_only_pause_not_words(self):
        self.assertEqual('「那您的父亲现在，」高木玲奈小心翼翼问。',
                         native_speech_text('「那您的父亲现在......」高木玲奈小心翼翼问。'))
        self.assertEqual('我，我已经还他们一些了，',native_speech_text('我...我已经还他们一些了，'))
        self.assertEqual('价格3.14元。',native_speech_text('价格3.14元。'))

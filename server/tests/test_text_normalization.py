import unittest

from server.text_normalization import normalize_tts_text


class TextNormalizationTests(unittest.TestCase):
    def test_prose_punctuation_keeps_words_and_intonation(self):
        for source, expected in [
            ("她问：“《山海经》……你看过吗？！”", "她问：山海经，你看过吗？"),
            ("「《山海经·海外经》」（插图版）到了！！。", "山海经·海外经插图版到了！"),
            ("等等，　，、再走；；听——有人。", "等等，再走；听，有人。"),
            ("他停了...又说⋯没事．．．走吧。", "他停了，又说，没事，走吧。"),
            ("［真的吗﹖﹗］", "真的吗？"),
            ("《【（）“”】》", ""),
            ("身后……", "身后，"),
        ]:
            with self.subTest(source=source):
                normalized = normalize_tts_text(source)
                self.assertEqual(expected, normalized)
                self.assertEqual(normalized, normalize_tts_text(normalized))
                self.assertEqual(
                    [char for char in source if char.isalnum()],
                    [char for char in normalized if char.isalnum()],
                )

    def test_numbers_and_other_meaningful_symbols_are_not_rewritten(self):
        for text in [
            "在12:30记录：-5℃、3.14、1,000、50%、2026-11-19、C++/Python、v1.2.3。",
            "分值为３．１４，时间１２：３０。",
        ]:
            self.assertEqual(text, normalize_tts_text(text))

    def test_wrapper_removal_does_not_join_latin_words_or_digit_groups(self):
        source = """用API《V2》读"don't"和‘l’esprit’，对照12【34】和Ａ〈Ｂ〉。"""
        self.assertEqual("用API V2读don't和l’esprit，对照12 34和Ａ Ｂ。", normalize_tts_text(source))

import builtins
import unittest
from unittest.mock import patch

from server.speech_quality import compare_transcript


class SpeechQualityDependencyTests(unittest.TestCase):
    def test_missing_cn2an_falls_back_to_character_pronunciation(self):
        original = builtins.__import__

        def without_cn2an(name, *args, **kwargs):
            if name == 'cn2an':
                raise ImportError('simulated missing optional dependency')
            return original(name, *args, **kwargs)

        with patch('builtins.__import__', side_effect=without_cn2an):
            report = compare_transcript('第1168章 看不见的人', '第一千一百六十八章看不见的人')
        self.assertTrue(report['accepted'], report)

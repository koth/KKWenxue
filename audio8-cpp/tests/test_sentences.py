"""Run on the deployment host: python test_sentences.py /path/to/audio8-tts."""
import json
import subprocess
import sys
import unittest

BINARY = sys.argv.pop(1) if len(sys.argv) > 1 else "audio8-tts"


class SentenceTests(unittest.TestCase):
    def test_reader_dialogue_and_non_spoken_boundaries(self):
        cases = [
            ('「清水组长，它..它还在后面！」', ['「清水组长，它..它还在后面！」']),
            ('他说：“别回头！？”她停住了。', ['他说：“别回头！？”', '她停住了。']),
            ('“别走……”接着，他关上了门。', ['“别走……”', '接着，他关上了门。']),
            ('　> ！？』', []),
            ('你好。\n\n「再见！」', ['你好。', '\n\n「再见！」']),
            ('Hello! Next?', ['Hello!', ' Next?']),
        ]
        result = subprocess.run([BINARY, '--split-text'],
                                input=''.join(json.dumps({'text': text}, ensure_ascii=False)+'\n' for text, _ in cases),
                                capture_output=True, text=True, encoding='utf-8', check=True, timeout=30)
        actual = [json.loads(line)['sentences'] for line in result.stdout.splitlines()]
        self.assertEqual([expected for _, expected in cases], actual)


if __name__ == '__main__':
    unittest.main()

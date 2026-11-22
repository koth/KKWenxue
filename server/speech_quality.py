"""Content coverage check; ASR spelling is compared phonetically, not verbatim."""
from __future__ import annotations

from difflib import SequenceMatcher
import re

VERIFICATION = 'sensevoice-coverage-v1'
NUMBER = re.compile(r'[0-9零〇一二两三四五六七八九十百千万亿点幺]+(?:\.[0-9]+)?')


def spoken_units(text):
    """Return pronunciation units and their offsets into the unmodified text."""
    try:
        import cn2an
    except ImportError:
        # The verifier runs in a deliberately small QA environment. Number
        # canonicalization is an optimization; phonetic character fallback is
        # sufficient and must never make the whole TTS job fail.
        cn2an = None
    from opencc import OpenCC
    from pypinyin import lazy_pinyin

    simplified = OpenCC('t2s').convert(text)
    # Spoken input is already simplified; keep offsets meaningful if a future
    # converter ever changes a phrase's length.
    if len(simplified) != len(text):
        simplified = ''.join(OpenCC('t2s').convert(c) for c in text)
    units = []
    cursor = 0
    for match in NUMBER.finditer(simplified):
        for i in range(cursor, match.start()):
            char = simplified[i]
            if char.isalnum():
                units.append((lazy_pinyin(char)[0].lower(), i, i+1))
        number = match.group()
        try:
            if cn2an is None:
                raise ImportError('cn2an unavailable')
            value = cn2an.cn2an(number.replace('幺','一'), 'smart')
            canonical = cn2an.an2cn(str(int(value)) if value == int(value) else str(value))
        except (ImportError, KeyError, ValueError, TypeError, IndexError):
            # cn2an rejects adjacent number groups such as “十三十三”
            # (common when ASR removes punctuation). Fall back to the
            # individual characters so verification never turns an audio
            # result into an infrastructure error.
            canonical = number
        for phone in lazy_pinyin(canonical):
            if phone.isalnum():
                units.append((phone.lower(), match.start(), match.end()))
        cursor = match.end()
    for i in range(cursor, len(simplified)):
        if simplified[i].isalnum():
            units.append((lazy_pinyin(simplified[i])[0].lower(), i, i+1))
    return units


def compare_transcript(text, transcript):
    expected, actual = spoken_units(text), spoken_units(transcript)
    a, b = [x[0] for x in expected], [x[0] for x in actual]
    gaps, differences = [], []
    for op, start, end, other_start, other_end in SequenceMatcher(None,a,b,autojunk=False).get_opcodes():
        if op == 'equal':
            continue
        missing = end-start
        added = other_end-other_start
        item = dict(op=op,expected_units=a[start:end],actual_units=b[other_start:other_end])
        if missing:
            lo, hi = expected[start][1], expected[end-1][2]
            item.update(start=lo,end=hi,text=text[lo:hi])
            # Homophones have already matched. A deletion inside a word, or a
            # substantial replacement, must not pass merely because audio is long.
            if op == 'delete' or missing-added >= 2 or (op == 'replace' and missing >= 3):
                gaps.append(item)
        differences.append(item)
    if not b and a:
        gaps = [dict(op='empty',start=0,end=len(text),text=text)]
    return dict(policy=VERIFICATION,accepted=not gaps,transcript=transcript,
                expected_units=len(a),recognized_units=len(b),gaps=gaps,differences=differences)


def native_speech_text(text):
    """Normalize pause notation for Audio8 without changing reader source words."""
    # ASCII dots are common in imported web novels. The model sometimes treats
    # them as an ending cue and skips the remainder, even in a short clause.
    return re.sub(r'(?:\.{2,}|…+|⋯+)', '，', text)

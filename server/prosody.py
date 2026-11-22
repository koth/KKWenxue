"""Explicit pause boundaries for checkpoints without learned pause tokens."""
import re

from tts.common import has_spoken_content

_BOUNDARIES = re.compile(r'[，,﹐、､﹑；;﹔：:﹕。！？!?]|\s+')
_GROUPED_NUMBER = re.compile(r'(?<!\d)\d{1,3}(?:,\d{3})+(?:\.\d+)?(?!\d)')
_CHAPTER_NUMBER = re.compile(r'第\s*([0-9零〇一二三四五六七八九十百千万两]+)\s*([章回节卷部集篇])')


def pause_fragments(text, title=False):
    """Keep words/punctuation intact and return text plus required following gap.

    Whitespace is a pause only in Chinese headings, never inside ordinary
    English word groups. Decimal/grouped numbers, times and URL schemes stay
    together. Trailing punctuation does not add silence to an otherwise whole
    utterance; the chapter renderer already handles inter-sentence gaps.
    """
    if title:
        text = _CHAPTER_NUMBER.sub(lambda match: '第' + match[1] + match[2], text)
    numeric_commas = {index for match in _GROUPED_NUMBER.finditer(text)
                      for index in range(match.start(), match.end()) if text[index] == ','}
    result, start = [], 0
    for match in _BOUNDARIES.finditer(text):
        mark, index, end = match[0], match.start(), match.end()
        if mark.isspace():
            left, right = text[index-1:index], text[end:end+1]
            if not title or not any('\u3400' <= char <= '\u9fff' for char in left + right):
                continue
            pause, reason = 500, 'title-space'
        elif index in numeric_commas:
            continue
        elif mark == ':' and (text[end:end+2] == '//' or
                               (index and end < len(text) and text[index-1].isdigit() and text[end].isdigit())):
            continue
        elif mark in '，,﹐、､﹑':
            pause, reason = 250, 'comma'
        elif mark in '；;﹔':
            pause, reason = 400, 'semicolon'
        elif mark in '：:﹕':
            pause, reason = 300, 'colon'
        else:
            pause, reason = 500, 'sentence'
        if has_spoken_content(text[start:end]) and has_spoken_content(text[end:]):
            result.append(dict(text=text[start:end], pause_ms=pause, reason=reason))
            start = end
    if has_spoken_content(text[start:]):
        result.append(dict(text=text[start:], pause_ms=0, reason=None))
    elif result:
        result[-1]['text'] += text[start:]
        result[-1].update(pause_ms=0, reason=None)
    return result


def natural_pauses(samples, text, alignment, title=False, rate=24000):
    """Keep a whole take's intonation; adjust only confidently aligned quiet gaps."""
    from bisect import bisect_right
    from difflib import SequenceMatcher
    import numpy as np
    from .speech_quality import spoken_units

    if not alignment:
        return samples, []
    tokens, times = alignment.get('tokens', []), alignment.get('timestamps', [])
    if not tokens or len(tokens) != len(times) or any(b < a for a, b in zip(times, times[1:])):
        return samples, []
    token_text, starts = '', []
    for token in tokens:
        starts.append(len(token_text))
        token_text += token.replace('▁', ' ')
    fragments = pause_fragments(text, title=title)
    source = ''.join(part['text'] for part in fragments)
    expected, actual = spoken_units(source), spoken_units(token_text)
    mapping = {}
    for a, b, size in SequenceMatcher(None, [unit[0] for unit in expected],
                                     [unit[0] for unit in actual], autojunk=False).get_matching_blocks():
        mapping.update((a+i, b+i) for i in range(size))
    frame = max(1, rate // 200)
    rms = np.sqrt(np.mean(samples[:len(samples)//frame*frame].reshape(-1,frame)**2,axis=1))
    if not len(rms):
        return samples, []
    threshold = max(.00003, min(.0003, float(np.percentile(rms, 95)) * .02))
    limits = {'comma': (70, 180), 'colon': (90, 220), 'semicolon': (160, 300),
              'sentence': (200, 400), 'title-space': (170, 320)}
    edits, offset = [], 0
    for fragment in fragments[:-1]:
        offset += len(fragment['text'])
        left = [i for i, unit in enumerate(expected) if unit[2] <= offset]
        right = [i for i, unit in enumerate(expected) if unit[1] >= offset]
        if not left or not right or left[-1] not in mapping or right[0] not in mapping:
            continue
        a, b = mapping[left[-1]], mapping[right[0]]
        if b != a+1:
            continue
        ta, tb = bisect_right(starts, actual[a][1])-1, bisect_right(starts, actual[b][1])-1
        if ta < 0 or tb <= ta:
            continue
        lo = max(0, round((times[ta]+.04)*rate)//frame)
        hi = min(len(rms), round((times[tb]-.02)*rate)//frame)
        ranges, start = [], None
        for i in range(lo, hi):
            if rms[i] < threshold:
                if start is None: start = i
            elif start is not None:
                ranges.append((start, i)); start = None
        if start is not None: ranges.append((start, hi))
        ranges = [(a*frame,b*frame) for a,b in ranges if b-a >= 3]
        if not ranges:
            continue  # Never cut through voiced syllables just to force a comma.
        start, end = max(ranges, key=lambda pair: pair[1]-pair[0])
        minimum, maximum = limits[fragment['reason']]
        original_ms = (end-start)*1000/rate
        target_ms = (minimum if original_ms < minimum else
                     (minimum+maximum)//2 if original_ms > maximum else original_ms)
        edits.append(dict(start=start,end=end,target=round(target_ms*rate/1000),
                          reason=fragment['reason'],original_duration_ms=round(original_ms)))
    pieces, cursor, position, changes = [], 0, 0, []
    for edit in sorted(edits, key=lambda edit: edit['start']):
        start, end, target = edit['start'], edit['end'], edit['target']
        if start < cursor:
            continue
        pieces.append(samples[cursor:start]); position += start-cursor
        quiet = samples[start:end].copy()
        if target != len(quiet):
            # Only the middle of an already quiet region changes; keep both
            # boundaries to protect soft consonants and the original phrasing.
            keep = min(len(quiet)//2, target//2)
            quiet = np.concatenate((quiet[:keep], np.zeros(target-2*keep,dtype=np.float32), quiet[-keep:]))
        pieces.append(quiet)
        changes.append(dict(reason=edit['reason'],start_sample=position,end_sample=position+len(quiet),
                            original_duration_ms=edit['original_duration_ms'],
                            duration_ms=round(len(quiet)*1000/rate),changed=target != end-start))
        position += len(quiet); cursor = end
    if not any(change['changed'] for change in changes):
        return samples, changes
    pieces.append(samples[cursor:])
    return np.concatenate(pieces), changes

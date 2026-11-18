"""Short native utterances, independent of the reader's durable text anchors."""
from tts.common import CLOSERS, has_spoken_content

SPEECH_PLAN = "clauses32-v2"
PAUSES = set("，,；;：:。！？!?\n")


def speech_fragments(text, maximum=32):
    """Keep each spoken clause intact when possible; never omit source text."""
    spans, start, index = [], 0, 0
    while index < len(text):
        char = text[index]
        # A decimal/grouping separator or time is part of the number.
        numeric = char in ",:" and index > 0 and index + 1 < len(text) and text[index-1].isdigit() and text[index+1].isdigit()
        index += 1
        if char in PAUSES and not numeric:
            while index < len(text) and (text[index] in CLOSERS or text[index] in "。！？!?"):
                index += 1
            spans.append(text[start:index])
            start = index
    if start < len(text):
        spans.append(text[start:])
    fragments, pending = [], ""
    for span in spans:
        # Normally pauses already give short units. Bound unpunctuated runs as
        # well, preferring word spaces instead of cutting an English word.
        while len(span) > maximum:
            cut = max((i+1 for i in range(maximum//2, maximum) if span[i].isspace()), default=maximum)
            pending += span[:cut]
            if has_spoken_content(pending):
                fragments.append(pending)
                pending = ""
            span = span[cut:]
        pending += span
        if has_spoken_content(pending):
            fragments.append(pending)
            pending = ""
    if pending and fragments:
        fragments[-1] += pending
    return fragments

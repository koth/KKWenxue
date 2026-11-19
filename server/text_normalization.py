"""Normalize punctuation in model input, not reader text or ASR references."""

import re

# Chinese prose does not need a space where a title mark was removed, but
# Western words and digit groups must not run together (API《V2》, 12【34】).
_WORD = r"[A-Za-z0-9À-ÖØ-öø-ÿＡ-Ｚａ-ｚ０-９]"
_WRAPPERS = re.compile(
    r"""(?:[《》〈〉「」『』“”‘"＂（）()【】〔〕〖〗［］\[\]]"""
    rf"|(?<!{_WORD})['’＇]|['’＇](?!{_WORD}))+"
)
_WORD_CHAR = re.compile(_WORD)

# Preserve question/exclamation intonation over other marks in a cluster.
_PAUSE_PRIORITY = ("？?﹖", "！!﹗", "。｡", "；;﹔", "：:﹕")
_DOTS = ".．﹒"
_ELLIPSES = "…⋯"
_PAUSE = "".join(_PAUSE_PRIORITY) + _DOTS + _ELLIPSES + "，,﹐、､﹑—"
_PAUSE_RUN = re.compile(rf"[{_PAUSE}](?:\s*[{_PAUSE}])*")


def _remove_wrapper(match: re.Match[str]) -> str:
    text = match.string
    lo, hi = match.span()
    if (
        lo and hi < len(text)
        and _WORD_CHAR.fullmatch(text[lo - 1])
        and _WORD_CHAR.fullmatch(text[hi])
    ):
        return " "
    return ""


def _compact_pause(match: re.Match[str]) -> str:
    marks = match[0].replace(" ", "")
    # Leave single marks alone (including 3.14, 12:30, 1,000 and URLs).
    if len(marks) == 1 and marks not in _ELLIPSES:
        return marks
    for group in _PAUSE_PRIORITY:
        if any(char in group for char in marks):
            return group[0]
    if sum(char in _DOTS for char in marks) == 1:
        return "。"
    return "，"


def normalize_tts_text(text: str) -> str:
    """Remove decorative wrappers and compact stacked pauses without dropping words."""
    unwrapped = _WRAPPERS.sub(_remove_wrapper, text)
    return _PAUSE_RUN.sub(_compact_pause, " ".join(unwrapped.split()))

"""Chapter headings have their own audio timeline, outside reader body offsets."""
from __future__ import annotations

import re

from tts.common import bounded_spans, digest, has_spoken_content, utf16_len

VERSION = 'chapter-title-v1'


def chapter_parts(db, revision_id, title=None):
    """Keep body IDs/cache stable; negative sentence indices identify headings.

    Heading offsets refer to the heading itself and must never become body cues.
    They share the ordinary durable generation/retry queue with body segments.
    """
    parts = list(db.execute(
        'SELECT * FROM segments WHERE revision_id=? AND depth=0 AND sentence_index>=0 ORDER BY segment_index',
        (revision_id,),
    ))
    if not title or not has_spoken_content(title) or not any(
        has_spoken_content(part['spoken_text']) for part in parts
    ):
        return parts
    from opencc import OpenCC
    convert = OpenCC('t2s').convert
    body = db.execute('SELECT canonical_text FROM revisions WHERE id=?', (revision_id,)).fetchone()[0]
    key = lambda text: ''.join(char for char in convert(text) if char.isalnum())
    # Some sources already include a heading paragraph. Let the body read it once.
    first_line = body.lstrip().splitlines()[0] if body.strip() else ''
    if key(first_line) == key(title):
        return parts
    spans = list(bounded_spans(title, 0, len(title)))
    headings = []
    for index, (start, end) in enumerate(spans):
        original = title[start:end]
        spoken = re.sub(r'\s+', ' ', convert(original)).strip()
        if not has_spoken_content(spoken):
            continue
        identifier = digest(f'{revision_id}:{VERSION}:{digest(title)}:{start}:{end}')
        heading = dict(
            id=identifier, revision_id=revision_id, segment_index=index-len(spans),
            sentence_index=-1, sentence_start_utf16=0, sentence_end_utf16=utf16_len(title),
            start_utf16=utf16_len(title[:start]), end_utf16=utf16_len(title[:end]),
            original_text=original, spoken_text=spoken, parent_segment_id=None, depth=0,
        )
        headings.append(heading)
    return headings + parts


def store_headings(db, parts):
    for part in parts:
        if part['sentence_index'] < 0:
            db.execute('INSERT OR IGNORE INTO segments VALUES (?,?,?,?,?,?,?,?,?,?,?,?)', tuple(
                part[key] for key in ('id', 'revision_id', 'segment_index', 'sentence_index',
                    'sentence_start_utf16', 'sentence_end_utf16', 'start_utf16', 'end_utf16',
                    'original_text', 'spoken_text', 'parent_segment_id', 'depth')
            ))

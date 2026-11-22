import json
import unittest

from fastapi.testclient import TestClient

from server.app import create_app
from server.artifacts import available
from server.cli import import_artifact
from server.jobs import action, detail, submit
from server.narration import chapter_parts
from server.presets import snapshot
from server.tests import test_service
from server.voices import DEFAULT_PRESET_ID


class ChapterNarrationTests(unittest.TestCase):
    def setUp(self):
        self.service = test_service.ServiceTests()
        self.service.setUp()
        self.addCleanup(self.service.doCleanups)

    def parts(self, job):
        return list(self.service.db.execute('''SELECT s.* FROM job_items ji
            JOIN segments s ON s.id=ji.segment_id WHERE ji.job_id=? ORDER BY ji.position''', (job,)))

    def manifest(self, job):
        s = self.service
        result = detail(s.db, job)
        self.assertEqual('succeeded', result['state'], result)
        artifact = available(s.db, s.settings, result['chapters'][0]['artifact_id'])
        return artifact, json.loads((s.settings.audio / artifact['manifest_path']).read_text())

    def test_title_uses_same_voice_reuses_body_and_preserves_body_anchors(self):
        s = self.service
        chapter = s.chapters[0]
        request = s.request([chapter['id']])
        old_config = snapshot(s.db, s.settings, DEFAULT_PRESET_ID, None)
        old_config.pop('narration')
        old = submit(s.db, s.settings, request, 'old-without-title', frozen=old_config)
        s.finish()
        _, old_manifest = self.manifest(old)
        self.assertNotIn('title_cues', old_manifest)

        new = submit(s.db, s.settings, request, 'include-title')
        before = detail(s.db, new)
        self.assertEqual(detail(s.db, old)['variant_id'], before['variant_id'])
        self.assertEqual(before['counts']['total'] - 1, before['counts']['reused'])
        s.finish()
        artifact, manifest = self.manifest(new)
        self.assertEqual(old_manifest['profile'], manifest['profile'])
        self.assertEqual(old_manifest['source_sha256'], manifest['source_sha256'])
        title = manifest['title_cues'][0]
        self.assertEqual(chapter['title'], title['original_text'])
        self.assertEqual(0, title['start_ms'])
        self.assertGreater(title['end_ms'], 0)
        self.assertEqual(title['end_ms'] + 350, manifest['cues'][0]['start_ms'])
        shift = manifest['cues'][0]['start_ms'] - old_manifest['cues'][0]['start_ms']
        self.assertEqual(len(old_manifest['cues']), len(manifest['cues']))
        for old_cue, cue in zip(old_manifest['cues'], manifest['cues']):
            self.assertEqual({k: v for k, v in old_cue.items() if k not in ('start_ms', 'end_ms')},
                             {k: v for k, v in cue.items() if k not in ('start_ms', 'end_ms')})
            self.assertEqual(old_cue['start_ms'] + shift, cue['start_ms'])
            self.assertEqual(old_cue['end_ms'] + shift, cue['end_ms'])
        imported = import_artifact(s.settings, s.settings.audio / artifact['audio_path'],
                                   s.settings.audio / artifact['manifest_path'], chapter['id'])
        self.assertTrue(available(s.db, s.settings, imported['artifact_id'])['complete'])
        client = TestClient(create_app(s.settings))
        client.headers['X-Service-Token'] = s.settings.token_file.read_text()
        revision = client.get('/api/v1/revisions/' + chapter['current_revision']).json()
        self.assertTrue(all(part['sentence_index'] >= 0 for part in revision['segments']))

    def test_estimate_includes_title_without_writing_synthetic_segments(self):
        s = self.service
        chapter = s.chapters[0]
        client = TestClient(create_app(s.settings))
        client.headers['X-Service-Token'] = s.settings.token_file.read_text()
        count = s.db.execute('SELECT count(*) FROM segments').fetchone()[0]
        estimate = client.post('/api/v1/jobs/estimate', json=s.request([chapter['id']])).json()
        self.assertEqual(count, s.db.execute('SELECT count(*) FROM segments').fetchone()[0])
        job = submit(s.db, s.settings, s.request([chapter['id']]), 'estimate-title')
        self.assertEqual(estimate['segments'], len(self.parts(job)))

    def test_renamed_title_changes_only_heading_and_retry_keeps_frozen_title(self):
        s = self.service
        chapter = s.chapters[0]
        request = s.request([chapter['id']])
        old = submit(s.db, s.settings, request, 'original-title')
        original_parts = self.parts(old)
        s.db.execute('UPDATE chapters SET title=? WHERE id=?', ('第1章 新的标题', chapter['id']))
        new = submit(s.db, s.settings, request, 'renamed-title')
        new_parts = self.parts(new)
        self.assertNotEqual(original_parts[0]['id'], new_parts[0]['id'])
        self.assertEqual([p['id'] for p in original_parts[1:]], [p['id'] for p in new_parts[1:]])
        self.assertEqual('第1章 新的标题', new_parts[0]['original_text'])
        s.db.execute("UPDATE jobs SET state='partial_failed' WHERE id=?", (old,))
        retry = action(s.db, s.settings, old, 'retry-failed', 'frozen-title-retry')
        self.assertEqual([p['id'] for p in original_parts], [p['id'] for p in self.parts(retry)])

    def test_heading_already_in_body_is_not_read_twice_and_symbol_body_stays_skipped(self):
        s = self.service
        title = s.chapters[0]['title']
        chapter = s.symbol_chapter(title + '\n\n真正的正文从这里开始。')
        parts = chapter_parts(s.db, chapter['current_revision'], title)
        self.assertTrue(all(part['sentence_index'] >= 0 for part in parts))
        chapter = s.symbol_chapter('>')
        parts = chapter_parts(s.db, chapter['current_revision'], title)
        self.assertTrue(all(part['sentence_index'] >= 0 for part in parts))

    def test_long_title_is_bounded_and_legacy_retry_excludes_headings(self):
        s = self.service
        chapter = s.chapters[0]
        title = '第1章 ' + '很长的章节标题😀' * 40
        parts = chapter_parts(s.db, chapter['current_revision'], title)
        headings = [part for part in parts if part['sentence_index'] < 0]
        self.assertGreater(len(headings), 1)
        self.assertEqual(title, ''.join(part['original_text'] for part in headings))
        self.assertTrue(all(len(part['original_text']) <= 160 for part in headings))
        old_config = snapshot(s.db, s.settings, DEFAULT_PRESET_ID, None)
        old_config.pop('narration')
        old = submit(s.db, s.settings, s.request([chapter['id']]), 'legacy-title', frozen=old_config)
        submit(s.db, s.settings, s.request([chapter['id']]), 'new-title')
        s.db.execute("UPDATE jobs SET state='partial_failed' WHERE id=?", (old,))
        retry = action(s.db, s.settings, old, 'retry-failed', 'legacy-title-retry')
        self.assertTrue(all(part['sentence_index'] >= 0 for part in self.parts(retry)))

    def test_failed_heading_blocks_export_and_retry_reuses_valid_body(self):
        s = self.service
        request = s.request([s.chapters[0]['id']])
        generate = s.engine.generate
        s.engine.generate = lambda rows, config: [
            ValueError('内容校验未通过') if row['sentence_index'] < 0 else generate([row], config)[0]
            for row in rows
        ]
        job = submit(s.db, s.settings, request, 'failed-title')
        s.finish()
        result = detail(s.db, job)
        self.assertEqual('partial_failed', result['state'])
        self.assertEqual(1, result['counts']['failed'])
        self.assertIsNone(result['chapters'][0]['artifact_id'])
        s.engine.generate = generate
        retry = action(s.db, s.settings, job, 'retry-failed', 'retry-title')
        recovering = detail(s.db, retry)
        self.assertEqual(recovering['counts']['total'] - 1, recovering['counts']['reused'])
        s.finish()
        _, manifest = self.manifest(retry)
        self.assertEqual(1, len(manifest['title_cues']))

    def test_long_heading_does_not_consume_body_preview_budget(self):
        s = self.service
        chapter = s.chapters[0]
        s.db.execute('UPDATE chapters SET title=? WHERE id=?', ('第1章 ' + '很长的章节标题' * 40, chapter['id']))
        job = submit(s.db, s.settings, s.request([chapter['id']]), 'long-heading-preview', kind='preview')
        parts = self.parts(job)
        self.assertGreater(sum(part['sentence_index'] < 0 for part in parts), 1)
        self.assertTrue(any(part['sentence_index'] >= 0 for part in parts))

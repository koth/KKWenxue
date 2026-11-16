"""Read-only snapshot of the in-progress production chapter after rollout."""
from pathlib import Path
import json
import sqlite3
import statistics

db = sqlite3.connect("file:/home/jetson/.local/share/shanye-tts/service.sqlite?mode=ro", uri=True)
db.row_factory = sqlite3.Row
job_id = "f79270933946451f97ba766c9b58e27b"
rows = db.execute("""
SELECT ji.position, length(s.spoken_text) characters, a.elapsed wall_seconds,
       1.0 * a.samples / a.sample_rate audio_seconds, at.runtime_json
FROM job_items ji JOIN segments s ON s.id=ji.segment_id
JOIN segment_audio a ON a.id=ji.audio_id
LEFT JOIN attempts at ON at.job_id=ji.job_id AND at.segment_id=ji.segment_id AND at.number=ji.attempts
WHERE ji.job_id=? AND ji.state='ready' AND ji.cache_hit=0 ORDER BY ji.position
""", (job_id,)).fetchall()
samples = []
for row in rows:
    item = dict(row)
    item["cuda_graph"] = json.loads(item.pop("runtime_json") or "{}").get("cuda_graph", False)
    item["rtf"] = item["wall_seconds"] / item["audio_seconds"]
    samples.append(item)
groups = {}
for name, enabled in [("before", False), ("after", True)]:
    group = [i for i in samples if i["cuda_graph"] == enabled]
    # Exclude each process's first segment, which includes model/reference load.
    warm = group[1:]
    groups[name] = dict(total=len(group), warm_segments=len(warm))
    if warm:
        groups[name].update(
            weighted_rtf=sum(i["wall_seconds"] for i in warm) / sum(i["audio_seconds"] for i in warm),
            median_rtf=statistics.median(i["rtf"] for i in warm),
        )
report = dict(job_id=job_id, counts=dict(db.execute(
    "SELECT state,count(*) FROM job_items WHERE job_id=? GROUP BY state", (job_id,))),
    groups=groups, samples=samples)
Path(__file__).with_name("production-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
print(json.dumps({key: value for key, value in report.items() if key != "samples"}, ensure_ascii=False))

export type ChapterRef = { id: string; current_revision: string; artifacts: any[] };

export function completeArtifactIds(job: any): string[] {
  if (job?.kind !== "synthesis" || job?.state !== "succeeded") return [];
  return [...new Set<string>((job.chapters || []).map((chapter: any) => chapter.artifact_id).filter(Boolean))];
}

/** The local book catalogue has no server artifacts; join by immutable revision. */
export function chapterArtifacts(chapter: ChapterRef | null, jobs: any[]): any[] {
  if (!chapter) return [];
  const result: any[] = [];
  for (const job of jobs) {
    for (const item of job.chapters || []) {
      if (item.chapter_id !== chapter.id || item.revision_id !== chapter.current_revision || !item.artifact_id) continue;
      if (!result.some((a) => a.id === item.artifact_id))
        result.push({ id: item.artifact_id, complete: job.kind !== "preview" });
    }
  }
  for (const artifact of chapter.artifacts || [])
    if (!result.some((a) => a.id === artifact.id)) result.push(artifact);
  return result;
}

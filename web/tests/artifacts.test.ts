import { test } from "node:test";
import assert from "node:assert/strict";
import { chapterArtifacts, completeArtifactIds } from "../src/artifacts.ts";

test("completed synthesis jobs expose their immutable artifacts for an App bundle", () => {
  assert.deepEqual(
    completeArtifactIds({kind:"synthesis", state:"succeeded", chapters:[{artifact_id:"a"},{artifact_id:"b"},{artifact_id:"a"}]}),
    ["a", "b"],
  );
  assert.deepEqual(completeArtifactIds({kind:"synthesis", state:"partial_failed", chapters:[{artifact_id:"a"}]}), []);
  assert.deepEqual(completeArtifactIds({kind:"preview", state:"succeeded", chapters:[{artifact_id:"a"}]}), []);
});

test("local chapters join completed artifacts by chapter and exact source revision", () => {
  const chapter = { id: "984", current_revision: "v2", artifacts: [] };
  const jobs = [
    { kind: "synthesis", chapters: [{chapter_id:"984", revision_id:"v2", artifact_id:"new"}] },
    { kind: "preview", chapters: [{chapter_id:"984", revision_id:"v2", artifact_id:"preview"}] },
    { kind: "synthesis", chapters: [{chapter_id:"984", revision_id:"v1", artifact_id:"old"}] },
    { kind: "synthesis", chapters: [{chapter_id:"985", revision_id:"v2", artifact_id:"other"}] },
    { kind: "synthesis", chapters: [{chapter_id:"984", revision_id:"v2", artifact_id:"new"}] },
  ];
  assert.deepEqual(chapterArtifacts(chapter, jobs), [{id:"new",complete:true},{id:"preview",complete:false}]);
  assert.deepEqual(chapterArtifacts(null,jobs), []);
});

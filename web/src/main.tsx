import React, { useEffect, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import { api, mutate, pending, uploadReference } from "./api";
import { chapterArtifacts, completeArtifactIds } from "./artifacts";
import "./style.css";

type Chapter = {
  id: string;
  title: string;
  chapter_number: number;
  chapter_index: number;
  current_revision: string;
  characters: number;
  segments: number;
  same_number_order: number;
  artifacts: any[];
};
const labels: Record<string, string> = {
  queued: "排队中",
  running: "合成中",
  exporting: "导出中",
  pausing: "正在暂停",
  paused: "已暂停",
  cancelling: "正在取消",
  cancelled: "已取消",
  succeeded: "已完成",
  partial_failed: "部分失败",
  failed: "失败",
};
const terminal = new Set([
  "succeeded",
  "partial_failed",
  "failed",
  "cancelled",
]);
const seconds = (ms: number) =>
  `${Math.floor(ms / 60000)
    .toString()
    .padStart(2, "0")}:${Math.floor((ms / 1000) % 60)
    .toString()
    .padStart(2, "0")}`;
function Icon({ name = "book", size = 20 }: { name?: string; size?: number }) {
  const paths: Record<string, React.ReactNode> = {
    book: (
      <>
        <path d="M12 6C8 3 4 4 3 5v14c3-1 6-1 9 1 3-2 6-2 9-1V5c-3-1-6-1-9 1Z" />
        <path d="M12 6v14" />
      </>
    ),
    wave: (
      <>
        <path d="M4 10v4m4-8v12m4-15v18m4-15v12m4-8v4" />
      </>
    ),
    play: <path d="m9 5 11 7-11 7Z" />,
    plus: <path d="M12 5v14M5 12h14" />,
    search: (
      <>
        <circle cx="10" cy="10" r="6" />
        <path d="m15 15 5 5" />
      </>
    ),
    download: (
      <>
        <path d="M12 3v12m-5-5 5 5 5-5M4 17v4h16v-4" />
      </>
    ),
    arrow: <path d="M4 12h16m-6-6 6 6-6 6" />,
    settings: (
      <>
        <path d="M4 7h16M4 17h16" />
        <circle cx="9" cy="7" r="2" />
        <circle cx="15" cy="17" r="2" />
      </>
    ),
  };
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {paths[name] || paths.book}
    </svg>
  );
}

function App() {
  const [books, setBooks] = useState<any[]>([]),
    [book, setBook] = useState(""),
    [catalogChapters, setChapters] = useState<Chapter[]>([]),
    [catalogTotal, setTotal] = useState(0),
    [page, setPage] = useState(0),
    [search, setSearch] = useState("");
  const [resolvedChapters, setResolvedChapters] = useState<Chapter[] | null>(null);
  const chapters = resolvedChapters
    ? resolvedChapters.slice(page * 80, (page + 1) * 80)
    : catalogChapters;
  const total = resolvedChapters?.length ?? catalogTotal;
  const [selected, setSelected] = useState<string[]>([]),
    [expression, setExpression] = useState(""),
    [ambiguous, setAmbiguous] = useState<any[]>([]),
    [current, setCurrent] = useState<Chapter | null>(null);
  const [presets, setPresets] = useState<any[]>([]),
    [preset, setPreset] = useState(""),
    [voices, setVoices] = useState<any[]>([]),
    [speaker, setSpeaker] = useState(""),
    [instruction, setInstruction] = useState(""),
    [name, setName] = useState(""),
    [speed, setSpeed] = useState(1),
    [pause, setPause] = useState(180);
  const [normalizeLoudness, setNormalizeLoudness] = useState(true);
  const [referenceFile, setReferenceFile] = useState<File | null>(null),
    [referenceText, setReferenceText] = useState(""),
    [cloneReady, setCloneReady] = useState(false);
  const [referenceUpload, setReferenceUpload] = useState(false);
  const [health, setHealth] = useState<any>(null),
    [connected, setConnected] = useState(false),
    [jobs, setJobs] = useState<any[]>([]),
    [estimate, setEstimate] = useState<any>(null),
    [mode, setMode] = useState("reuse");
  const [error, setError] = useState(""),
    [notice, setNotice] = useState(""),
    [busy, setBusy] = useState(false),
    [manifest, setManifest] = useState<any>(null),
    [revision, setRevision] = useState<any>(null),
    [artifact, setArtifact] = useState(""),
    [time, setTime] = useState(0),
    [playSpeed, setPlaySpeed] = useState(1),
    [tab, setTab] = useState("text");
  const audio = useRef<HTMLAudioElement>(null);
  const chapterList = useRef<HTMLDivElement>(null);
  const bookRef = useRef("");
  const [waiting, setWaiting] = useState(!!pending());
  async function run(work: () => Promise<void>) {
    setBusy(true);
    setError("");
    try {
      await work();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
      setWaiting(!!pending());
    }
  }
  async function refresh() {
    try {
      const [h, j, b] = await Promise.all([
        api("/api/v1/health"),
        api("/api/v1/jobs"),
        api("/api/v1/books"),
      ]);
      setHealth(h);
      setCloneReady(h.models?.voice_clone_ready ?? false);
      setJobs(j.items);
      setBooks(b.items);
      setConnected(true);
      if (!bookRef.current && b.items.length) {
        setBook(b.items[0].id);
        bookRef.current = b.items[0].id;
      }
    } catch {
      setConnected(false);
    }
  }
  useEffect(() => {
    let alive = true;
    async function poll() {
      if (!alive) return;
      await refresh();
      if (alive)
        timer = window.setTimeout(poll, document.hidden ? 10000 : 2000);
    }
    let timer = window.setTimeout(poll, 0);
    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, []);
  useEffect(() => {
    if (!connected) return;
    Promise.all([api("/api/v1/presets"), api("/api/v1/voices")])
      .then(([p, v]) => {
        setPresets(p.items);
        setVoices(v.voices);
        setCloneReady(v.voice_cloning);
        setReferenceUpload(v.reference_upload === true);
        setPreset((current) =>
          p.items.some((item: any) => item.id === current)
            ? current
            : v.default_preset_id || p.items[0]?.id || "",
        );
      })
      .catch(() => setConnected(false));
  }, [connected]);
  const completedKey = jobs
    .map((j) => j.chapters.map((c: any) => c.artifact_id || "").join())
    .join();
  useEffect(() => {
    if (!book || resolvedChapters !== null) return;
    let active = true;
    const timer = setTimeout(() => {
      api(
        `/api/v1/books/${book}/chapters?offset=${page * 80}&limit=80&q=${encodeURIComponent(search)}`,
      )
        .then((v) => {
          if (active) {
            setChapters(v.items);
            setTotal(v.total);
            setCurrent((c) =>
              c ? v.items.find((x: Chapter) => x.id === c.id) || c : null,
            );
          }
        })
        .catch((e) => setError(e.message));
    }, 180);
    return () => {
      active = false;
      clearTimeout(timer);
    };
  }, [book, page, search, completedKey, resolvedChapters]);
  useEffect(() => {
    if (chapterList.current) chapterList.current.scrollTop = 0;
  }, [book, page, search, resolvedChapters]);
  useEffect(() => {
    const p = presets.find((p) => p.id === preset);
    if (p) {
      setSpeaker(p.speaker);
      setInstruction(p.instruct);
      setName(p.name);
      setSpeed(p.export.speed);
      setPause(p.export.pause_ms);
      setNormalizeLoudness(p.export.normalize_loudness ?? true);
    }
  }, [preset, presets]);
  useEffect(() => {
    let active = true;
    setManifest(null);
    setTime(0);
    async function load() {
      if (artifact) {
        const m = await api(`/api/v1/artifacts/${artifact}/manifest`);
        const r = await api(`/api/v1/revisions/${m.source_revision}`);
        if (active) {
          setManifest(m);
          setRevision(r);
        }
      } else if (current) {
        const r = await api(`/api/v1/revisions/${current.current_revision}`);
        if (active) setRevision(r);
      } else if (active) setRevision(null);
    }
    load().catch((e) => {
      if (active) setError(e.message);
    });
    return () => {
      active = false;
    };
  }, [artifact, current?.current_revision]);
  const isClone = speaker.startsWith("clone:");
  const currentVoice = voices.find((v) => v.speaker === speaker);
  const builtinPresets = presets.filter((p) => p.builtin);
  const savedPresets = presets.filter((p) => !p.builtin);
  const dirty =
    !!presets.find((p) => p.id === preset) &&
    (speaker !== presets.find((p) => p.id === preset)?.speaker ||
      instruction !== presets.find((p) => p.id === preset)?.instruct);
  const payload = {
    book_id: book,
    chapter_ids: selected,
    voice_preset_version_id: preset,
    export: {
      speed, pause_ms: pause, format: "m4a",
      normalize_loudness: normalizeLoudness,
    },
    mode,
  };
  useEffect(() => {
    setEstimate(null);
    if (!book || !selected.length || dirty) return;
    const timer = setTimeout(
      () =>
        api("/api/v1/jobs/estimate", payload)
          .then(setEstimate)
          .catch(() => {}),
      300,
    );
    return () => clearTimeout(timer);
  }, [book, selected.join(), preset, speed, pause, normalizeLoudness, mode, dirty]);
  const gpu = health?.workers?.gpu;
  const activeSentence = manifest?.cues.find(
    (c: any) => time * 1000 >= c.start_ms && time * 1000 < c.end_ms,
  )?.sentence_index;
  const artifacts = chapterArtifacts(current, jobs);
  function openChapter(c: Chapter) {
    setCurrent(c);
    const available = chapterArtifacts(c, jobs);
    setArtifact(available.find((a) => a.complete)?.id || available[0]?.id || "");
    setTab("text");
  }
  function toggle(id: string) {
    setSelected((old) =>
      old.includes(id) ? old.filter((x) => x !== id) : [...old, id],
    );
  }
  async function savePreset() {
    const v = await api("/api/v1/presets", {
      name: name || "自定义旁白",
      speaker,
      instruct: instruction,
      export: {
        speed, pause_ms: pause, format: "m4a",
        normalize_loudness: normalizeLoudness,
      },
    });
    const p = await api("/api/v1/presets");
    setPresets(p.items);
    setPreset(v.id);
    setNotice("已保存为新的预设版本");
  }
  async function uploadVoiceClone() {
    if (!referenceFile) throw Error("请选择一个参考音频文件");
    const result = await uploadReference(
      referenceFile,
      referenceText,
    );
    const p = await api("/api/v1/presets");
    setPresets(p.items);
    setPreset(result.id);
    setNotice(result.message);
  }
  async function submit(preview = false) {
    if (dirty) throw Error("请先保存修改后的声音为新预设");
    const chosen = preview
      ? current
        ? [current.id]
        : selected.slice(0, 1)
      : selected;
    if (!chosen.length) throw Error("请先选择章节");
    const v = await mutate(preview ? "/api/v1/previews" : "/api/v1/jobs", {
      ...payload,
      chapter_ids: chosen,
    });
    setNotice(`已提交${preview ? "试听" : "合成"} · ${v.id.slice(0, 8)}`);
    await refresh();
  }
  async function control(j: any, action: string) {
    if (action === "retry-failed")
      await mutate(`/api/v1/jobs/${j.id}/actions`, { action });
    else await api(`/api/v1/jobs/${j.id}/actions`, { action });
    await refresh();
  }
  async function bundle() {
    const selection = await api("/api/v1/chapter-selections/resolve", {
      book_id: book,
      chapter_ids: selected,
    });
    const ids: string[] = [];
    for (const c of selection.chapters) {
      const available = chapterArtifacts(c, jobs);
      const chosen =
        c.id === current?.id && artifact
          ? available.find((a: any) => a.id === artifact && a.complete)?.id
          : available.find((a: any) => a.complete)?.id;
      if (!chosen)
        throw Error(`${c.title} 尚无选中的完整成品，请先合成或切换到完整章节版本`);
      ids.push(chosen);
    }
    await mutate("/api/v1/download-bundles", { artifact_ids: ids });
    setNotice("听书包已排队，下载 ZIP 后可在 Android「听书 → 导入听书包」中离线播放");
    await refresh();
  }
  async function bundleJob(job: any) {
    const ids = completeArtifactIds(job);
    if (!ids.length) throw Error("这项任务还没有可打包的完整章节");
    await mutate("/api/v1/download-bundles", { artifact_ids: ids });
    setNotice(`已为 ${ids.length} 章生成 App 导入包；完成后在下方下载 ZIP`);
    await refresh();
  }
  function seek(sentence: number) {
    const cue = manifest?.cues.find((c: any) => c.sentence_index === sentence);
    if (cue && audio.current) {
      audio.current.currentTime = cue.start_ms / 1000;
      void audio.current.play().catch(() => {});
    }
  }
  function textView() {
    if (!revision) return null;
    const sentences = new Map<number, { start: number; end: number }>();
    revision.segments.forEach((s: any) =>
      sentences.set(s.sentence_index, {
        start: s.sentence_start_utf16,
        end: s.sentence_end_utf16,
      }),
    );
    return [...sentences.entries()].map(([i, r]) => (
      <span
        key={i}
        onClick={() => seek(i)}
        className={`${activeSentence === i ? "spoken" : ""} ${manifest ? "seekable" : ""}`}
      >
        {revision.canonical_text.slice(r.start, r.end)}
      </span>
    ));
  }

  return (
    <div className="app-shell">
      <header>
        <a className="brand" href="/" aria-label="山页语音工作台">
          <span className="brand-mark">
            <Icon name="book" size={23} />
          </span>
          <b>山页</b>
          <span className="brand-divider" /> <span>语音工作台</span>
        </a>
        <div className="header-right">
          <span className={`connection ${connected ? "online" : ""}`}>
            <i />
            {connected ? "服务器已连接" : "正在连接服务器"}
          </span>
          <span className="header-note">让好故事，有一个好声音</span>
        </div>
      </header>
      <section className="intro">
        <div>
          <p className="eyebrow">SHANYE / AUDIO STUDIO</p>
          <h1>
            让文字，慢慢有声<span>。</span>
          </h1>
          <p className="intro-sub">
            从一句试听，到一整本故事。声音、节奏，由你来定。
          </p>
        </div>
        <div className="engine-badge">
          <Icon name="wave" size={30} />
          <div>
            <b>ZONOS3-Mini</b>
            <small>
              {gpu?.alive
                ? gpu.model === "ready"
                  ? "模型已就绪"
                  : gpu.model === "loading"
                    ? "正在加载模型"
                    : gpu.state === "external-gpu-owner"
                      ? "GPU 被其他执行器占用"
                      : "等待合成任务"
                : "GPU worker 未连接"}{" "}
              · 参考音色
            </small>
          </div>
        </div>
      </section>
      {(error || notice || waiting) && (
        <div className={`message ${error ? "error" : ""}`} role="status">
          <span>
            {error || notice || "有一笔提交尚未确认，核对后可继续操作。"}
          </span>
          {waiting && (
            <button
              onClick={() =>
                run(async () => {
                  const p = pending();
                  if (p) {
                    await mutate(p.path, p.body);
                    setNotice("提交状态已核对");
                    await refresh();
                  }
                })
              }
            >
              核对提交
            </button>
          )}
          <button
            className="dismiss"
            onClick={() => {
              setError("");
              setNotice("");
            }}
            aria-label="关闭提示"
          >
            ×
          </button>
        </div>
      )}
      <main className="workspace">
        <aside className="catalog panel">
          <div className="panel-heading">
            <span>
              <Icon name="book" /> 书库
            </span>
            <small>{books.length.toString().padStart(2, "0")} 本</small>
          </div>
          <select
            aria-label="选择书籍"
            value={book}
            onChange={(e) => {
              bookRef.current = e.target.value;
              setBook(e.target.value);
              setSelected([]);
              setCurrent(null);
              setArtifact("");
              setPage(0);
              setSearch("");
              setExpression("");
              setAmbiguous([]);
              setResolvedChapters(null);
            }}
          >
            <option value="">选择一本书</option>
            {books.map((b) => (
              <option key={b.id} value={b.id}>
                {b.title}
              </option>
            ))}
          </select>
          <div className="search">
            <Icon name="search" size={16} />
            <input
              placeholder="搜索章节或标题"
              value={search}
              onChange={(e) => {
                setSearch(e.target.value);
                setPage(0);
                setResolvedChapters(null);
                setAmbiguous([]);
              }}
              aria-label="搜索章节"
            />
          </div>
          <div className="range-input">
            <input
              placeholder="章号，如 914,916-919"
              value={expression}
              onChange={(e) => setExpression(e.target.value)}
              aria-label="章节范围"
            />
            <button
              disabled={!book || busy}
              onClick={() =>
                run(async () => {
                  const r = await api("/api/v1/chapter-selections/resolve", {
                    book_id: book,
                    expression,
                  });
                  // Ambiguous chapter numbers remain unselected, but their
                  // candidates still belong in the matching catalogue.
                  const matches = r.ambiguous.length
                    ? (await api("/api/v1/chapter-selections/resolve", {
                        book_id: book,
                        chapter_ids: [
                          ...new Set([
                            ...r.chapter_ids,
                            ...r.ambiguous.flatMap((a: any) =>
                              a.candidates.map((c: any) => c.id),
                            ),
                          ]),
                        ],
                      })).chapters
                    : r.chapters;
                  if (bookRef.current !== book) return;
                  setSelected(r.chapter_ids);
                  setAmbiguous(r.ambiguous);
                  setResolvedChapters(
                    matches.map((c: any, index: number) => ({
                      ...c,
                      characters: c.characters ?? c.canonical_text?.length ?? 0,
                      segments: Array.isArray(c.segments)
                        ? c.segments.length
                        : c.segments ?? 0,
                      same_number_order:
                        c.same_number_order ??
                        1 + matches.slice(0, index).filter(
                          (p: any) => p.chapter_number === c.chapter_number,
                        ).length,
                      artifacts: c.artifacts || [],
                    })),
                  );
                  setPage(0);
                  setSearch("");
                })
              }
            >
              解析
            </button>
          </div>
          {resolvedChapters !== null && (
            <div className="catalog-filter">
              <span>匹配 {total} 章</span>
              <button
                onClick={() => {
                  setResolvedChapters(null);
                  setExpression("");
                  setAmbiguous([]);
                  setPage(0);
                }}
              >
                显示全部
              </button>
            </div>
          )}
          {!!ambiguous.length && (
            <div className="ambiguities">
              同号章节，请选择：
              {ambiguous
                .flatMap((a) => a.candidates)
                .map((c: any) => (
                  <label key={c.id}>
                    <input
                      type="checkbox"
                      checked={selected.includes(c.id)}
                      onChange={() => toggle(c.id)}
                    />
                    {c.title} · 顺序 {c.chapter_index + 1}
                  </label>
                ))}
            </div>
          )}
          <div className="chapter-meta">
            <label>
              <input
                type="checkbox"
                checked={
                  !!chapters.length &&
                  chapters.every((c) => selected.includes(c.id))
                }
                onChange={(e) =>
                  setSelected((old) =>
                    e.target.checked
                      ? [...new Set([...old, ...chapters.map((c) => c.id)])]
                      : old.filter((id) => !chapters.some((c) => c.id === id)),
                  )
                }
              />{" "}
              本页全选
            </label>
            <small>{total} 章</small>
          </div>
          <div className="chapter-list" ref={chapterList}>
            {chapters.map((c) => (
              <div
                className={`chapter ${current?.id === c.id ? "current" : ""}`}
                key={c.id}
              >
                <input
                  type="checkbox"
                  aria-label={`选择 ${c.title}`}
                  checked={selected.includes(c.id)}
                  onChange={() => toggle(c.id)}
                />
                <button onClick={() => openChapter(c)}>
                  <b>{c.title}</b>
                  <small>
                    {c.characters.toLocaleString()} 字 · {c.segments} 段
                    {c.same_number_order > 1
                      ? ` · 同号 ${c.same_number_order}`
                      : ""}
                  </small>
                </button>
                <span
                  className={`chapter-dot ${chapterArtifacts(c, jobs).some((a) => a.complete) ? "ready" : ""}`}
                  title={chapterArtifacts(c, jobs).length ? "有音频版本" : "待合成"}
                />
              </div>
            ))}
            {!chapters.length && (
              <div className="small-empty">
                <Icon size={30} />
                <p>{book ? "没有匹配章节" : "书库还空着"}</p>
                <small>
                  {book ? "试试其他章号或标题" : "导入一本书，开始制作有声故事"}
                </small>
              </div>
            )}
          </div>
          <div className="catalog-footer">
            <span>
              已选择 <b>{selected.length}</b> 章
            </span>
            <div>
              <button disabled={!page} onClick={() => setPage((p) => p - 1)}>
                ‹
              </button>
              <small>{page + 1}</small>
              <button
                disabled={(page + 1) * 80 >= total}
                onClick={() => setPage((p) => p + 1)}
              >
                ›
              </button>
            </div>
          </div>
        </aside>
        <section className="reader panel">
          <div className="reader-toolbar">
            <div className="tabs">
              <button
                className={tab === "text" ? "active" : ""}
                onClick={() => setTab("text")}
              >
                原文与试听
              </button>
              <button
                className={tab === "versions" ? "active" : ""}
                onClick={() => setTab("versions")}
              >
                音频版本 <small>{artifacts.length}</small>
              </button>
            </div>
            <span className="tiny-label">
              {manifest
                ? manifest.complete
                  ? "完整章节"
                  : "开头试听"
                : "UTF-16 原文同步"}
            </span>
          </div>
          {current ? (
            <>
              <div className="reader-body">
                {tab === "text" ? (
                  <>
                    <p className="eyebrow">
                      CHAPTER{" "}
                      {current.chapter_number.toString().padStart(3, "0")}
                    </p>
                    <h2>{current.title}</h2>
                    <p className="reading-info">
                      {manifest
                        ? `当前版本 ${artifact.slice(0, 8)} · ${presets.find((p) => p.speaker === manifest.profile?.speaker)?.name || "旁白"} · 成品 ${manifest.speed}×`
                        : "选择音色后，先听听这一章的开头。"}
                    </p>
                    <div className="novel-text">{textView()}</div>
                  </>
                ) : (
                  <div className="versions">
                    <h2>声音的不同版本</h2>
                    {artifacts.length ? (
                      artifacts.map((a) => (
                        <button
                          key={a.id}
                          className={artifact === a.id ? "chosen" : ""}
                          onClick={() => {
                            setArtifact(a.id);
                            setTab("text");
                          }}
                        >
                          <Icon name="wave" />
                          <span>
                            {a.complete ? "完整章节" : "试听片段"}
                            <small>
                              {a.id.slice(0, 12)}
                              {a.duration_ms
                                ? ` · ${seconds(a.duration_ms)}`
                                : ""}
                            </small>
                          </span>
                          <Icon name="play" size={16} />
                        </button>
                      ))
                    ) : (
                      <p>还没有音频版本，先生成一段试听吧。</p>
                    )}
                  </div>
                )}
              </div>
              <div className="player">
                {artifact ? (
                  <>
                    <audio
                      ref={audio}
                      key={artifact}
                      controls
                      src={`/api/v1/artifacts/${artifact}/audio`}
                      onTimeUpdate={(e) => setTime(e.currentTarget.currentTime)}
                      onLoadedMetadata={(e) => {
                        e.currentTarget.playbackRate = playSpeed;
                      }}
                    />
                    <div className="player-details">
                      <label>
                        播放{" "}
                        <select
                          aria-label="播放器倍速"
                          value={playSpeed}
                          onChange={(e) => {
                            const v = Number(e.target.value);
                            setPlaySpeed(v);
                            if (audio.current) audio.current.playbackRate = v;
                          }}
                        >
                          {[0.75, 1, 1.15, 1.25, 1.5, 2].map((n) => (
                            <option key={n} value={n}>
                              {n}×
                            </option>
                          ))}
                        </select>
                      </label>
                      <span>
                        {manifest ? seconds(manifest.duration_ms) : "加载中"} ·
                        点击原句定位
                      </span>
                      <a
                        href={`/api/v1/artifacts/${artifact}/manifest`}
                        download
                      >
                        时间索引
                      </a>
                      <a
                        href={`/api/v1/artifacts/${artifact}/audio?download=true`}
                        download
                      >
                        <Icon name="download" size={15} /> M4A
                      </a>
                    </div>
                  </>
                ) : (
                  <div className="player-placeholder">
                    <Icon name="wave" />
                    <span>试听就绪后，在这里听见故事</span>
                  </div>
                )}
              </div>
            </>
          ) : (
            <div className="reader-empty">
              <div className="book-art">
                <div className="book-spine" />
                <span>
                  山<br />页
                </span>
                <div className="book-lines" />
              </div>
              <p className="eyebrow">A STORY, WAITING TO BE HEARD</p>
              <h2>{books.length ? "挑一章，听见故事" : "故事，从这里开声"}</h2>
              <p>
                {books.length
                  ? "在左侧选择章节，再为文字挑一个声音。"
                  : "本地书库暂无内容，请将书籍导入阅读器数据库。"}
              </p>
              {!books.length && (
                <details>
                  <summary>
                    如何导入书籍 <Icon name="arrow" size={15} />
                  </summary>
                  <p>
                    默认读取阅读器自带的本地 SQLite。可将 TXT/EPUB
                    导入阅读器，或启动时指定已有数据库。书库浏览在本机完成。
                  </p>
                  <code>
                    python3 -m local.launcher --library /path/to/shanye.db
                  </code>
                </details>
              )}
            </div>
          )}
        </section>
        <aside className="settings panel">
          <div className="panel-heading">
            <span>
              <Icon name="settings" /> 声音设置
            </span>
            <small>01</small>
          </div>
          <label className="field-label">
            旁白预设{" "}
            <select
              aria-label="旁白预设"
              value={preset}
              onChange={(e) => setPreset(e.target.value)}
            >
              {!!builtinPresets.length && (
                <optgroup label={`默认音色 · ${builtinPresets.length} 种`}>
                  {builtinPresets.map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.name}
                    </option>
                  ))}
                </optgroup>
              )}
              {!!savedPresets.length && (
                <optgroup label="已保存的预设与克隆音色">
                  {savedPresets.map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.name}
                    </option>
                  ))}
                </optgroup>
              )}
            </select>
          </label>
          <div className="voice-card">
            <div className="voice-avatar">
              <Icon name="wave" size={28} />
            </div>
            <div>
              <b>
                {isClone ? "参考音色" : currentVoice?.label?.split(" · ")[0] || speaker}
              </b>
              <small>
                {isClone
                  ? "跟随参考音频 · 请先试听"
                  : currentVoice?.description || "参考音色 · 请先试听"}
              </small>
            </div>
            <span>ZH</span>
          </div>
          {!isClone && currentVoice && (
            <p className="helper voice-origin">
              {currentVoice.native_language}原生音色 · 当前朗读普通话
              <br />
              切换旁白预设后，可直接试听当前章节。
            </p>
          )}
          {referenceUpload && <details className="clone-box">
            <summary>
              用参考音频创建 ZONOS3 音色
            </summary>
            <p className="helper">
              选择 3–30 秒干净的单人录音，支持
              M4A/WAV/MP3。模型跟随录音中的音色和节奏，请同时填写与录音逐字对应的文本。
            </p>
            <input
              aria-label="参考音频"
              type="file"
              accept=".m4a,.wav,.mp3,.flac,.aac"
              onChange={(e) => setReferenceFile(e.target.files?.[0] || null)}
            />
            <textarea
              aria-label="参考音频逐字稿"
              rows={3}
              placeholder="参考音频逐字稿（必填，必须与录音内容一致）"
              value={referenceText}
              onChange={(e) => setReferenceText(e.target.value)}
            />
            <button
              className="clone-upload"
              disabled={busy || !referenceFile || !referenceText.trim()}
              onClick={() => run(uploadVoiceClone)}
            >
              上传并创建克隆预设
            </button>
            <small>
              {cloneReady
                ? "ZONOS3 已就绪，实际音色和节奏以试听为准。"
                : "服务器正在准备 ZONOS3 模型；请稍后重试。"}
            </small>
          </details>}
              <p className="helper">
                Haoran 为预置中文男声，林川来自参考录音克隆。成品语速可在下方调整。
              </p>
          {dirty && (
            <div className="save-preset">
              <input
                aria-label="新预设名称"
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="新预设名称"
              />
              <button onClick={() => run(savePreset)} disabled={busy}>
                保存为新预设
              </button>
            </div>
          )}
          <div className="setting-divider" />
          <div className="slider-label">
            <span>成品语速</span>
            <b>
              {speed.toFixed(2).replace(/0$/, "")}
              <small>×</small>
            </b>
          </div>
          <input
            type="range"
            aria-label="成品语速"
            min="0.75"
            max="1.5"
            step="0.05"
            value={speed}
            onChange={(e) => setSpeed(Number(e.target.value))}
          />
          <div className="range-labels">
            <span>0.75×</span>
            <span>1.0× 自然</span>
            <span>1.5×</span>
          </div>
          <label className="pause-setting">
            <span>句间停顿</span>
            <span>
              <input
                aria-label="句间停顿毫秒"
                type="number"
                min="0"
                max="2000"
                step="10"
                value={pause}
                onChange={(e) => setPause(Number(e.target.value))}
              />{" "}
              ms
            </span>
          </label>
          <p className="helper">
            成品倍速通过 CPU 处理，保留原音高；播放器倍速单独设置。
          </p>
          <label className="clone-check">
            <input
              type="checkbox"
              checked={normalizeLoudness}
              onChange={(e) => setNormalizeLoudness(e.target.checked)}
            />
            统一成品音量
          </label>
          <p className="helper">
            提升偏轻的声音，限制过大峰值。可复用已有音频重新导出；关闭则保留原音量。
          </p>
          <div className="setting-divider" />
          <label className="field-label">
            生成方式
            <select
              aria-label="生成方式"
              value={mode}
              onChange={(e) => setMode(e.target.value)}
            >
              <option value="reuse">复用已有，只合成缺失</option>
              <option value="new-version">新建版本，重新合成</option>
            </select>
          </label>
          <div className="estimate">
            <span>
              本次选择 <b>{selected.length} 章</b>
            </span>
            <span>
              可复用 / 待合成{" "}
              <b>
                {estimate ? `${estimate.reused} / ${estimate.missing} 段${estimate.skipped ? ` · 跳过 ${estimate.skipped} 段纯符号` : ""}` : "—"}
              </b>
            </span>
            <span>
              预计合成时间{" "}
              <b>
                {estimate?.estimated_seconds != null
                  ? `约 ${Math.ceil(estimate.estimated_seconds / 60)} 分钟`
                  : "等待实测"}
              </b>
            </span>
          </div>
          <button
            className="preview-button"
            disabled={busy || !book || dirty || (!current && !selected.length)}
            onClick={() => run(() => submit(true))}
          >
            <Icon name="play" size={17} />
            先试听一段
          </button>
          <button
            className="primary"
            disabled={busy || !selected.length || dirty}
            onClick={() => run(() => submit())}
          >
            开始合成 <Icon name="arrow" size={18} />
          </button>
          <button
            className="download-selected"
            disabled={busy || !selected.length}
            onClick={() => run(bundle)}
          >
            <Icon name="download" size={15} /> 打包听书包（App 导入）
          </button>
        </aside>
      </main>
      <section className="queue panel">
        <div className="queue-header">
          <div>
            <Icon name="wave" />
            <b>任务队列</b>
            <small>
              {jobs.filter((j) => !terminal.has(j.state)).length} 个进行中
            </small>
          </div>
          <span>任务留在服务器，关闭页面也会继续</span>
        </div>
        {!jobs.length ? (
          <div className="queue-empty">
            <span className="empty-line" /> 还没有任务，试着让一段文字开声。
            <span className="empty-line" />
          </div>
        ) : (
          jobs.map((j) => (
            <div className="job" key={j.id}>
              <span className={`status-dot ${j.state}`} />
              <div className="job-description">
                <b>
                  {j.kind === "bundle"
                    ? "章节下载包"
                    : j.kind === "preview"
                      ? "开头试听"
                      : `${j.chapters.length} 章合成`}
                  <small>
                    {j.config.preset_name || "ZIP"} · {j.id.slice(0, 8)}
                  </small>
                </b>
                <span>
                  {j.error ||
                    j.errors[0]?.error ||
                    `${j.counts.completed + (j.counts.skipped || 0)} / ${j.counts.total} 段 · 复用 ${j.counts.reused} 段${j.counts.skipped ? ` · 跳过 ${j.counts.skipped} 段纯符号` : ""}`}
                </span>
                {!!j.errors.length && (
                  <details>
                    <summary>查看失败原文</summary>
                    {j.errors.map((e: any) => (
                      <p key={e.segment_id}>
                        {e.original_text}
                        <small>
                          {e.attempts_total ?? e.attempts} 次尝试
                          {e.split_attempts > 0 ? `（含子片段 ${e.split_attempts} 次）` : ""} · {e.error}
                        </small>
                      </p>
                    ))}
                  </details>
                )}
              </div>
              <div className="job-progress">
                <progress
                  value={j.counts.completed + (j.counts.skipped || 0)}
                  max={j.counts.total || 1}
                />
                <span>{labels[j.state] || j.state}</span>
              </div>
              <div className="job-actions">
                {!terminal.has(j.state) && (
                  <>
                    <button
                      disabled={
                        busy || ["pausing", "cancelling"].includes(j.state)
                      }
                      onClick={() =>
                        run(() =>
                          control(j, j.state === "paused" ? "resume" : "pause"),
                        )
                      }
                    >
                      {j.state === "paused" ? "继续" : "暂停"}
                    </button>
                    <button
                      disabled={busy || j.state === "cancelling"}
                      onClick={() => run(() => control(j, "cancel"))}
                    >
                      取消
                    </button>
                  </>
                )}
                {["partial_failed", "failed"].includes(j.state) &&
                  j.kind !== "bundle" && (
                    <button
                      disabled={busy}
                      onClick={() => run(() => control(j, "retry-failed"))}
                    >
                      重试失败项
                    </button>
                  )}
                {j.chapters
                  .filter((c: any) => c.artifact_id)
                  .map((c: any) => (
                    <button
                      key={c.artifact_id}
                      onClick={() => {
                        setArtifact(c.artifact_id);
                        setCurrent({
                          id: c.chapter_id,
                          title: c.title,
                          chapter_number: c.chapter_number,
                          current_revision: c.revision_id,
                          artifacts: [],
                          chapter_index: c.chapter_index,
                          characters: 0,
                          segments: 0,
                          same_number_order: 1,
                        });
                        setTab("text");
                      }}
                    >
                      播放{j.chapters.length > 1 ? ` ${c.chapter_number}` : ""}
                    </button>
                  ))}
                {j.kind === "bundle" && j.state === "succeeded" && (
                  <a href={`/api/v1/download-bundles/${j.id}/file`} download>
                    下载听书包 ZIP
                  </a>
                )}
                {j.kind === "synthesis" && j.state === "succeeded" && (
                  <button disabled={busy} onClick={() => run(() => bundleJob(j))}>
                    <Icon name="download" size={15} /> 下载 App 导入包
                  </button>
                )}
              </div>
            </div>
          ))
        )}
      </section>
      <footer>
        <span>山页 · 每一个故事，都值得被认真讲述。</span>
        <span>
          {health?.disks
            .map(
              (d: any) =>
                `${d.kind === "audio" ? "音频盘" : "数据盘"} ${(d.free_bytes / 2 ** 30).toFixed(1)} GiB 可用`,
            )
            .join(" / ") || "本地工作台 · SSH 连接"}
        </span>
      </footer>
    </div>
  );
}
createRoot(document.getElementById("root")!).render(<App />);

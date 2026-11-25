//! SQLite 存储：元数据唯一真源的 schema、写入与查询。

use rusqlite::{params, Connection, OptionalExtension};
use serde::Serialize;

use crate::parsers::{Detail, ListItem};

pub fn open(path: &str) -> rusqlite::Result<Connection> {
    let conn = Connection::open(path)?;
    init(&conn)?;
    Ok(conn)
}

pub fn init(conn: &Connection) -> rusqlite::Result<()> {
    conn.pragma_update(None, "journal_mode", "WAL")?;
    conn.busy_timeout(std::time::Duration::from_secs(10))?;
    conn.execute_batch(
        "CREATE TABLE IF NOT EXISTS books (
            id                INTEGER PRIMARY KEY,
            title             TEXT NOT NULL DEFAULT '',
            author            TEXT NOT NULL DEFAULT '',
            category          TEXT NOT NULL DEFAULT '',
            status            TEXT NOT NULL DEFAULT '',
            words             INTEGER,
            words_text        TEXT,
            chapter_no        INTEGER,
            latest_chapter_id INTEGER,
            latest_chapter    TEXT,
            last_update_at    TEXT,
            cover_url         TEXT,
            meta_level        INTEGER NOT NULL DEFAULT 1,
            list_seen_at      INTEGER NOT NULL DEFAULT 0,
            detail_at         INTEGER,
            detail_failures   INTEGER NOT NULL DEFAULT 0,
            first_seen_at     INTEGER NOT NULL DEFAULT 0
        );
        CREATE INDEX IF NOT EXISTS books_updated  ON books(last_update_at DESC);
        CREATE INDEX IF NOT EXISTS books_category ON books(category, last_update_at DESC);
        CREATE INDEX IF NOT EXISTS books_words    ON books(words DESC);
        CREATE INDEX IF NOT EXISTS books_pending  ON books(meta_level, id);
        CREATE INDEX IF NOT EXISTS books_title    ON books(title);
        CREATE INDEX IF NOT EXISTS books_author   ON books(author);

        CREATE TABLE IF NOT EXISTS book_chapters (
            book_id          INTEGER NOT NULL,
            source_id        INTEGER NOT NULL,
            chapter_index    INTEGER NOT NULL,
            title            TEXT NOT NULL,
            body_sha256      TEXT,
            PRIMARY KEY (book_id, source_id),
            UNIQUE (book_id, chapter_index)
        );
        CREATE INDEX IF NOT EXISTS book_chapters_order ON book_chapters(book_id, chapter_index);

        CREATE TABLE IF NOT EXISTS sync_runs (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            kind TEXT NOT NULL,
            started_at INTEGER NOT NULL,
            finished_at INTEGER,
            pages INTEGER NOT NULL DEFAULT 0,
            books_seen INTEGER NOT NULL DEFAULT 0,
            books_changed INTEGER NOT NULL DEFAULT 0,
            detail_ok INTEGER NOT NULL DEFAULT 0,
            detail_failed INTEGER NOT NULL DEFAULT 0,
            error TEXT
        );
        CREATE TABLE IF NOT EXISTS content_generations(book_id INTEGER PRIMARY KEY,updated_at INTEGER NOT NULL DEFAULT 0);
        CREATE TABLE IF NOT EXISTS chapter_bodies(book_id INTEGER NOT NULL,source_id INTEGER NOT NULL,title TEXT NOT NULL,body TEXT NOT NULL,sha256 TEXT NOT NULL,updated_at INTEGER NOT NULL,PRIMARY KEY(book_id,source_id));
        CREATE TABLE IF NOT EXISTS recent_updates(
            book_id INTEGER PRIMARY KEY,
            observed_at INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS refresh_requests(
            book_id INTEGER PRIMARY KEY,
            requested_at INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS prefetch_jobs (
            id TEXT PRIMARY KEY,
            book_id INTEGER NOT NULL,
            priority INTEGER NOT NULL DEFAULT 50,
            state TEXT NOT NULL DEFAULT 'queued',
            total INTEGER NOT NULL DEFAULT 0,
            completed INTEGER NOT NULL DEFAULT 0,
            failed INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS prefetch_items (
            job_id TEXT NOT NULL,
            source_id INTEGER NOT NULL,
            state TEXT NOT NULL DEFAULT 'queued',
            error TEXT,
            updated_at INTEGER NOT NULL,
            PRIMARY KEY(job_id, source_id)
        );",
    )?;
    let has_hash = conn
        .prepare("PRAGMA table_info(book_chapters)")?
        .query_map([], |row| row.get::<_, String>(1))?
        .collect::<rusqlite::Result<Vec<_>>>()?
        .iter()
        .any(|name| name == "body_sha256");
    if !has_hash {
        conn.execute("ALTER TABLE book_chapters ADD COLUMN body_sha256 TEXT", [])?;
    }
    Ok(())
}

pub fn create_prefetch_job(
    conn: &Connection,
    id: &str,
    book_id: i64,
    priority: i64,
    source_ids: &[i64],
) -> rusqlite::Result<()> {
    let now = now();
    let tx = conn.unchecked_transaction()?;
    tx.execute("INSERT INTO prefetch_jobs(id,book_id,priority,state,total,created_at,updated_at) VALUES(?1,?2,?3,'queued',?4,?5,?5)", params![id, book_id, priority, source_ids.len() as i64, now])?;
    for source_id in source_ids {
        tx.execute(
            "INSERT INTO prefetch_items(job_id,source_id,updated_at) VALUES(?1,?2,?3)",
            params![id, source_id, now],
        )?;
    }
    tx.commit()
}

pub fn update_prefetch_item(
    conn: &Connection,
    job_id: &str,
    source_id: i64,
    state: &str,
    error: Option<&str>,
) -> rusqlite::Result<()> {
    let now = now();
    conn.execute("UPDATE prefetch_items SET state=?3,error=?4,updated_at=?5 WHERE job_id=?1 AND source_id=?2", params![job_id, source_id, state, error, now])?;
    conn.execute("UPDATE prefetch_jobs SET state=CASE WHEN EXISTS(SELECT 1 FROM prefetch_items WHERE job_id=?1 AND state IN ('queued','running')) THEN 'running' WHEN EXISTS(SELECT 1 FROM prefetch_items WHERE job_id=?1 AND state='failed') THEN 'partial' ELSE 'completed' END, completed=(SELECT COUNT(*) FROM prefetch_items WHERE job_id=?1 AND state='completed'), failed=(SELECT COUNT(*) FROM prefetch_items WHERE job_id=?1 AND state='failed'), updated_at=?2 WHERE id=?1", params![job_id, now])?;
    Ok(())
}

pub fn prefetch_job(conn: &Connection, id: &str) -> rusqlite::Result<Option<serde_json::Value>> {
    let job = conn.query_row("SELECT id,book_id,priority,state,total,completed,failed,created_at,updated_at FROM prefetch_jobs WHERE id=?1", params![id], |r| {
        Ok(serde_json::json!({"taskId": r.get::<_,String>(0)?, "bookId": r.get::<_,i64>(1)?, "priority": r.get::<_,i64>(2)?, "state": r.get::<_,String>(3)?, "total": r.get::<_,i64>(4)?, "completed": r.get::<_,i64>(5)?, "failed": r.get::<_,i64>(6)?, "createdAt": r.get::<_,i64>(7)?, "updatedAt": r.get::<_,i64>(8)?}))
    }).optional()?;
    let Some(mut value) = job else {
        return Ok(None);
    };
    let mut stmt = conn.prepare(
        "SELECT source_id,state,error FROM prefetch_items WHERE job_id=?1 ORDER BY source_id",
    )?;
    let items = stmt.query_map(params![id], |r| Ok(serde_json::json!({"sourceId": r.get::<_,i64>(0)?, "state": r.get::<_,String>(1)?, "error": r.get::<_,Option<String>>(2)?})))?.collect::<rusqlite::Result<Vec<_>>>()?;
    value["items"] = serde_json::to_value(items).unwrap_or_default();
    Ok(Some(value))
}

fn now() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

/// 列表页字段先落库（meta_level=1），保证「最近更新」永不空缺。
pub fn upsert_list(conn: &Connection, b: &ListItem) -> rusqlite::Result<()> {
    conn.execute(
        "INSERT INTO books (id, title, author, category, status, latest_chapter_id, latest_chapter,
                            list_seen_at, first_seen_at, meta_level)
         VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?8,1)
         ON CONFLICT(id) DO UPDATE SET
            title=excluded.title,
            author=CASE WHEN excluded.author<>'' THEN excluded.author ELSE books.author END,
            category=excluded.category,
            status=excluded.status,
            latest_chapter_id=excluded.latest_chapter_id,
            latest_chapter=excluded.latest_chapter,
            list_seen_at=excluded.list_seen_at",
        params![
            b.id,
            b.title,
            b.author,
            b.category,
            b.status,
            b.latest_chapter_id,
            b.latest_chapter,
            now()
        ],
    )?;
    Ok(())
}

/// 详情页字段（meta_level=2）。
pub fn upsert_detail(conn: &Connection, d: &Detail) -> rusqlite::Result<()> {
    let tx = conn.unchecked_transaction()?;
    tx.execute(
        "INSERT INTO books (id, title, author, category, status, words, words_text, chapter_no,
                            latest_chapter_id, latest_chapter, last_update_at, detail_at,
                            first_seen_at, meta_level)
         VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?12,2)
         ON CONFLICT(id) DO UPDATE SET
            title=excluded.title,
            author=excluded.author,
            category=excluded.category,
            status=excluded.status,
            words=excluded.words,
            words_text=excluded.words_text,
            chapter_no=excluded.chapter_no,
            latest_chapter_id=excluded.latest_chapter_id,
            latest_chapter=excluded.latest_chapter,
            last_update_at=excluded.last_update_at,
            detail_at=excluded.detail_at,
            detail_failures=0,
            meta_level=2",
        params![
            d.id,
            d.title,
            d.author,
            d.category,
            d.status,
            d.words,
            d.words_text,
            d.chapter_no,
            d.latest_chapter_id,
            d.latest_chapter,
            d.last_update_at,
            now()
        ],
    )?;
    if d.chapters.is_empty() {
        return Err(rusqlite::Error::InvalidQuery);
    }
    let old = chapters(&tx, d.id, 100_000, None)?
        .into_iter()
        .map(|c| (c.source_id, c))
        .collect::<std::collections::HashMap<_, _>>();
    let mut seen = std::collections::HashSet::new();
    if d.chapters
        .iter()
        .any(|c| c.source_id <= 0 || c.title.is_empty() || !seen.insert(c.source_id))
    {
        return Err(rusqlite::Error::InvalidQuery);
    }
    tx.execute("DELETE FROM book_chapters WHERE book_id=?1", params![d.id])?;
    for (index, chapter) in d.chapters.iter().enumerate() {
        let hash = old
            .get(&chapter.source_id)
            .filter(|c| c.title == chapter.title)
            .and_then(|c| c.body_sha256.clone());
        tx.execute("INSERT INTO book_chapters(book_id,source_id,chapter_index,title,body_sha256) VALUES(?1,?2,?3,?4,?5)",params![d.id,chapter.source_id,index as i64,chapter.title,hash])?;
    }
    tx.execute("DELETE FROM chapter_bodies WHERE book_id=?1 AND NOT EXISTS(SELECT 1 FROM book_chapters c WHERE c.book_id=chapter_bodies.book_id AND c.source_id=chapter_bodies.source_id AND c.title=chapter_bodies.title)",params![d.id])?;
    tx.commit()?;
    Ok(())
}

#[derive(Serialize)]
pub struct ChapterRow {
    pub source_id: i64,
    pub chapter_index: i64,
    pub title: String,
    pub body_sha256: Option<String>,
}

pub fn chapters(
    conn: &Connection,
    book_id: i64,
    limit: usize,
    cursor: Option<i64>,
) -> rusqlite::Result<Vec<ChapterRow>> {
    let mut stmt = conn.prepare(
        "SELECT source_id,chapter_index,title,body_sha256 FROM book_chapters
         WHERE book_id=?1 AND (?2 IS NULL OR chapter_index>?2)
         ORDER BY chapter_index LIMIT ?3",
    )?;
    let rows = stmt.query_map(params![book_id, cursor, limit as i64], |r| {
        Ok(ChapterRow {
            source_id: r.get(0)?,
            chapter_index: r.get(1)?,
            title: r.get(2)?,
            body_sha256: r.get(3)?,
        })
    })?;
    rows.collect()
}

pub fn chapter_count(conn: &Connection, book_id: i64) -> rusqlite::Result<i64> {
    conn.query_row(
        "SELECT COUNT(*) FROM book_chapters WHERE book_id=?1",
        params![book_id],
        |r| r.get(0),
    )
}

pub fn has_chapter(conn: &Connection, book_id: i64, source_id: i64) -> rusqlite::Result<bool> {
    conn.query_row(
        "SELECT EXISTS(SELECT 1 FROM book_chapters WHERE book_id=?1 AND source_id=?2)",
        params![book_id, source_id],
        |r| r.get(0),
    )
}

pub fn set_chapter_hash(
    conn: &Connection,
    book_id: i64,
    source_id: i64,
    sha256: &str,
) -> rusqlite::Result<()> {
    conn.execute(
        "UPDATE book_chapters SET body_sha256=?3 WHERE book_id=?1 AND source_id=?2",
        params![book_id, source_id, sha256],
    )?;
    Ok(())
}

pub fn record_detail_failure(conn: &Connection, id: i64) -> rusqlite::Result<()> {
    conn.execute(
        "UPDATE books SET detail_failures = detail_failures + 1 WHERE id = ?1",
        params![id],
    )?;
    Ok(())
}

pub fn pending_detail_ids(conn: &Connection, limit: usize) -> rusqlite::Result<Vec<i64>> {
    let mut stmt = conn.prepare(
        "SELECT id FROM books WHERE meta_level < 2 ORDER BY detail_failures, id LIMIT ?1",
    )?;
    let rows = stmt.query_map(params![limit as i64], |r| r.get::<_, i64>(0))?;
    rows.collect()
}

/// Existing catalog databases created before the chapter manifest table need a
/// one-time detail refresh. Keep this separate from normal incremental sync so
/// the API never has to invent chapter references for an unfilled book.
pub fn pending_chapter_ids(conn: &Connection, limit: usize) -> rusqlite::Result<Vec<i64>> {
    let mut stmt = conn.prepare(
        "SELECT b.id FROM books b
         WHERE b.meta_level = 2
           AND (NOT EXISTS (SELECT 1 FROM book_chapters c WHERE c.book_id=b.id)
                OR (b.chapter_no > 0 AND (SELECT COUNT(*) FROM book_chapters c WHERE c.book_id=b.id) < b.chapter_no))
         ORDER BY b.id LIMIT ?1",
    )?;
    let rows = stmt.query_map(params![limit as i64], |r| r.get::<_, i64>(0))?;
    rows.collect()
}

pub fn known_latest_chapters(
    conn: &Connection,
) -> rusqlite::Result<std::collections::HashMap<i64, i64>> {
    let mut stmt = conn.prepare("SELECT id, COALESCE(latest_chapter_id,0) FROM books")?;
    let rows = stmt.query_map([], |r| Ok((r.get::<_, i64>(0)?, r.get::<_, i64>(1)?)))?;
    let mut map = std::collections::HashMap::new();
    for row in rows {
        let (id, cid) = row?;
        map.insert(id, cid);
    }
    Ok(map)
}

const RECENT_UPDATE_WINDOW: i64 = 12 * 60 * 60;

/// Mark a book observed with a newer chapter during the current catalog scan.
/// The short-lived watermark lets per-book checks detect books missed by the
/// front-page scan without treating the whole catalog as permanently fresh.
pub fn mark_recent_update(conn: &Connection, book_id: i64) -> rusqlite::Result<()> {
    conn.execute(
        "INSERT INTO recent_updates(book_id,observed_at) VALUES(?1,?2)
         ON CONFLICT(book_id) DO UPDATE SET observed_at=excluded.observed_at",
        params![book_id, now()],
    )?;
    Ok(())
}

pub fn is_recent_update(conn: &Connection, book_id: i64) -> rusqlite::Result<bool> {
    let cutoff = now() - RECENT_UPDATE_WINDOW;
    conn.query_row(
        "SELECT EXISTS(SELECT 1 FROM recent_updates WHERE book_id=?1 AND observed_at>=?2)",
        params![book_id, cutoff],
        |r| r.get(0),
    )
}

pub fn request_refresh(conn: &Connection, book_id: i64) -> rusqlite::Result<()> {
    conn.execute(
        "INSERT INTO refresh_requests(book_id,requested_at) VALUES(?1,?2)
         ON CONFLICT(book_id) DO UPDATE SET requested_at=excluded.requested_at",
        params![book_id, now()],
    )?;
    Ok(())
}

pub fn pending_refresh_ids(conn: &Connection, limit: usize) -> rusqlite::Result<Vec<i64>> {
    let mut stmt = conn.prepare(
        "SELECT book_id FROM refresh_requests ORDER BY requested_at,book_id LIMIT ?1",
    )?;
    let rows = stmt.query_map(params![limit as i64], |r| r.get::<_, i64>(0))?;
    rows.collect()
}

pub fn clear_refresh_requests(conn: &Connection, ids: &[i64]) -> rusqlite::Result<()> {
    for book_id in ids {
        conn.execute("DELETE FROM refresh_requests WHERE book_id=?1", params![book_id])?;
    }
    Ok(())
}

#[derive(Serialize)]
pub struct Stats {
    pub books: i64,
    pub detail_complete: i64,
    pub categories: Vec<(String, i64)>,
    pub last_sync: Option<String>,
}

pub fn stats(conn: &Connection) -> rusqlite::Result<Stats> {
    let books: i64 = conn.query_row("SELECT COUNT(*) FROM books", [], |r| r.get(0))?;
    let detail_complete: i64 =
        conn.query_row("SELECT COUNT(*) FROM books WHERE meta_level = 2", [], |r| {
            r.get(0)
        })?;
    let mut stmt = conn.prepare(
        "SELECT category, COUNT(*) FROM books WHERE category <> '' GROUP BY category ORDER BY 2 DESC",
    )?;
    let categories = stmt
        .query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?)))?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    let last_sync: Option<i64> = conn
        .query_row("SELECT MAX(finished_at) FROM sync_runs", [], |r| r.get(0))
        .optional()?
        .flatten();
    Ok(Stats {
        books,
        detail_complete,
        categories,
        last_sync: last_sync.map(|t| t.to_string()),
    })
}

#[derive(Serialize, Clone)]
pub struct BookRow {
    pub id: i64,
    pub title: String,
    pub author: String,
    pub category: String,
    pub status: String,
    pub words: i64,
    pub chapter_no: i64,
    pub last_update_at: String,
    pub latest_chapter_id: i64,
    pub latest_chapter: String,
}

const BOOK_COLUMNS: &str = "id, title, author, category, status, COALESCE(words,0),
    COALESCE(chapter_no,0), COALESCE(last_update_at,''), COALESCE(latest_chapter_id,0),
    COALESCE(latest_chapter,'')";

fn map_book(r: &rusqlite::Row<'_>) -> rusqlite::Result<BookRow> {
    Ok(BookRow {
        id: r.get(0)?,
        title: r.get(1)?,
        author: r.get(2)?,
        category: r.get(3)?,
        status: r.get(4)?,
        words: r.get(5)?,
        chapter_no: r.get(6)?,
        last_update_at: r.get(7)?,
        latest_chapter_id: r.get(8)?,
        latest_chapter: r.get(9)?,
    })
}

/// 关键词搜索：书名/作者 LIKE，按更新时间倒序，游标用 id。
pub fn search(
    conn: &Connection,
    q: &str,
    limit: usize,
    cursor: Option<i64>,
) -> rusqlite::Result<Vec<BookRow>> {
    let like = format!("%{}%", q);
    let sql = format!(
        "SELECT {BOOK_COLUMNS} FROM books
         WHERE (title LIKE ?1 OR author LIKE ?1) AND (?2 IS NULL OR id > ?2)
         ORDER BY last_update_at DESC, id DESC LIMIT ?3"
    );
    let mut stmt = conn.prepare(&sql)?;
    let rows = stmt.query_map(params![like, cursor, limit as i64], map_book)?;
    rows.collect()
}

pub fn list_by_category(
    conn: &Connection,
    category: Option<&str>,
    status: Option<&str>,
    sort: &str,
    limit: usize,
    cursor: Option<i64>,
) -> rusqlite::Result<Vec<BookRow>> {
    let order = if sort == "words" {
        "COALESCE(words,0) DESC, id DESC"
    } else {
        "COALESCE(last_update_at,'') DESC, id DESC"
    };
    let sql = format!(
        "SELECT {BOOK_COLUMNS} FROM books
         WHERE (?1 IS NULL OR category = ?1)
           AND (?2 IS NULL OR status = ?2)
           AND (?3 IS NULL OR id < ?3)
         ORDER BY {order} LIMIT ?4"
    );
    let mut stmt = conn.prepare(&sql)?;
    let rows = stmt.query_map(params![category, status, cursor, limit as i64], map_book)?;
    rows.collect()
}

pub fn get_book(conn: &Connection, id: i64) -> rusqlite::Result<Option<BookRow>> {
    let sql = format!("SELECT {BOOK_COLUMNS} FROM books WHERE id = ?1");
    conn.query_row(&sql, params![id], map_book).optional()
}

pub fn start_sync_run(conn: &Connection, kind: &str) -> rusqlite::Result<i64> {
    conn.execute(
        "INSERT INTO sync_runs (kind, started_at) VALUES (?1, ?2)",
        params![kind, now()],
    )?;
    Ok(conn.last_insert_rowid())
}

#[allow(clippy::too_many_arguments)]
pub fn finish_sync_run(
    conn: &Connection,
    run: i64,
    pages: i64,
    seen: i64,
    changed: i64,
    ok: i64,
    failed: i64,
    error: Option<&str>,
) -> rusqlite::Result<()> {
    conn.execute(
        "UPDATE sync_runs SET finished_at=?2, pages=?3, books_seen=?4, books_changed=?5,
         detail_ok=?6, detail_failed=?7, error=?8 WHERE id=?1",
        params![run, now(), pages, seen, changed, ok, failed, error],
    )?;
    Ok(())
}

pub fn manifest_revision(conn: &Connection, book_id: i64) -> rusqlite::Result<String> {
    use sha2::{Digest, Sha256};
    let mut hash = Sha256::new();
    for chapter in chapters(conn, book_id, 100_000, None)? {
        hash.update(
            format!(
                "{}:{}:{}\n",
                chapter.source_id, chapter.chapter_index, chapter.title
            )
            .as_bytes(),
        );
    }
    // Only body changes to an already published hash advance this generation;
    // discovering the body for the first time must not invalidate a fresh import.
    let generation: i64 = conn.query_row(
        "SELECT COALESCE(MAX(updated_at),0) FROM content_generations WHERE book_id=?1",
        params![book_id],
        |r| r.get(0),
    )?;
    hash.update(generation.to_le_bytes());
    Ok(format!("catalog-v2:{book_id}:{:x}", hash.finalize()))
}

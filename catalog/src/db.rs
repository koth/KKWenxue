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
        );",
    )
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
    conn.execute(
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

#[derive(Serialize)]
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

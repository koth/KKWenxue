//! SQLite 存储：元数据唯一真源的 schema、写入与查询。

use rusqlite::{params, Connection, OptionalExtension};
use serde::Serialize;
use sha2::Digest;
use unicode_normalization::UnicodeNormalization;

use crate::parsers::{Detail, ListItem};
use crate::sources::{ExternalDetail, ExternalListItem};

pub fn open(path: &str) -> rusqlite::Result<Connection> {
    let conn = Connection::open(path)?;
    init(&conn)?;
    Ok(conn)
}

/// Open a database connection for a worker after the process-level schema
/// initialization has already completed.  Detail workers must not rerun the
/// migration/DDL sequence concurrently: on a small SQLite host that turns a
/// harmless WAL write into a database-wide lock.
pub fn open_existing(path: &str) -> rusqlite::Result<Connection> {
    let conn = Connection::open(path)?;
    conn.busy_timeout(std::time::Duration::from_secs(30))?;
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
        CREATE TABLE IF NOT EXISTS source_recent_updates(
            book_source_id INTEGER PRIMARY KEY,
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
    init_multi_source_schema(conn)?;
    backfill_legacy_source_bindings(conn)?;
    migrate_legacy_task_bindings(conn)?;
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

fn table_columns(conn: &Connection, table: &str) -> rusqlite::Result<Vec<String>> {
    conn.prepare(&format!("PRAGMA table_info({table})"))?
        .query_map([], |row| row.get::<_, String>(1))?
        .collect()
}

fn ensure_column(
    conn: &Connection,
    table: &str,
    column: &str,
    definition: &str,
) -> rusqlite::Result<()> {
    if !table_columns(conn, table)?
        .iter()
        .any(|name| name == column)
    {
        conn.execute(
            &format!("ALTER TABLE {table} ADD COLUMN {column} {definition}"),
            [],
        )?;
    }
    Ok(())
}

/// The original catalog used the source site's numeric ID as the primary key.
/// Keep those legacy columns for a rolling compatibility window, but introduce
/// source-scoped identities beside them.  This migration is deliberately
/// additive so an old catalog can be opened without losing cached chapters.
fn init_multi_source_schema(conn: &Connection) -> rusqlite::Result<()> {
    conn.execute_batch(
        "CREATE TABLE IF NOT EXISTS catalog_sources (
            source_key TEXT PRIMARY KEY,
            display_name TEXT NOT NULL,
            base_url TEXT NOT NULL,
            adapter_version TEXT NOT NULL DEFAULT '1',
            enabled INTEGER NOT NULL DEFAULT 1,
            priority INTEGER NOT NULL DEFAULT 50,
            last_success_at INTEGER,
            last_failure_at INTEGER,
            last_failure_code TEXT,
            last_failure_message TEXT,
            created_at INTEGER NOT NULL DEFAULT 0,
            updated_at INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE IF NOT EXISTS book_sources (
            book_source_id INTEGER PRIMARY KEY AUTOINCREMENT,
            book_id INTEGER NOT NULL,
            source_key TEXT NOT NULL,
            external_book_id TEXT NOT NULL,
            canonical_url TEXT NOT NULL DEFAULT '',
            source_title TEXT NOT NULL DEFAULT '',
            source_author TEXT NOT NULL DEFAULT '',
            normalized_title TEXT NOT NULL DEFAULT '',
            normalized_author TEXT NOT NULL DEFAULT '',
            normalization_version TEXT NOT NULL DEFAULT 'nfkc-v1',
            binding_state TEXT NOT NULL DEFAULT 'candidate',
            is_preferred INTEGER NOT NULL DEFAULT 0,
            source_revision TEXT NOT NULL DEFAULT '',
            latest_external_chapter_id TEXT,
            last_update_at TEXT,
            last_checked_at INTEGER,
            created_at INTEGER NOT NULL DEFAULT 0,
            updated_at INTEGER NOT NULL DEFAULT 0,
            UNIQUE(source_key, external_book_id),
            UNIQUE(book_id, source_key)
        );
        CREATE INDEX IF NOT EXISTS book_sources_book ON book_sources(book_id, binding_state);
        CREATE INDEX IF NOT EXISTS book_sources_external ON book_sources(source_key, external_book_id);
        CREATE TABLE IF NOT EXISTS book_match_candidates (
            candidate_id INTEGER PRIMARY KEY AUTOINCREMENT,
            source_key TEXT NOT NULL,
            external_book_id TEXT NOT NULL,
            candidate_book_id INTEGER,
            score REAL NOT NULL DEFAULT 0,
            matched_rules TEXT NOT NULL DEFAULT '[]',
            source_title TEXT NOT NULL DEFAULT '',
            source_author TEXT NOT NULL DEFAULT '',
            normalized_title TEXT NOT NULL DEFAULT '',
            normalized_author TEXT NOT NULL DEFAULT '',
            normalization_version TEXT NOT NULL DEFAULT 'nfkc-v1',
            state TEXT NOT NULL DEFAULT 'pending',
            created_at INTEGER NOT NULL DEFAULT 0,
            resolved_at INTEGER,
            resolved_by TEXT
        );
        CREATE INDEX IF NOT EXISTS book_match_candidates_lookup
            ON book_match_candidates(source_key, external_book_id, state);
        CREATE TABLE IF NOT EXISTS catalog_audit_events (
            event_id INTEGER PRIMARY KEY AUTOINCREMENT,
            source_key TEXT,
            external_book_id TEXT,
            book_id INTEGER,
            book_source_id INTEGER,
            event_type TEXT NOT NULL,
            before_json TEXT,
            after_json TEXT,
            reason TEXT NOT NULL DEFAULT '',
            actor TEXT NOT NULL DEFAULT 'system',
            created_at INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE IF NOT EXISTS source_sync_state (
            source_key TEXT PRIMARY KEY,
            list_cursor TEXT,
            detail_cursor TEXT,
            refresh_cursor TEXT,
            retry_count INTEGER NOT NULL DEFAULT 0,
            rate_limit_ms INTEGER NOT NULL DEFAULT 500,
            last_success_at INTEGER,
            last_failure_at INTEGER,
            last_failure_code TEXT,
            last_failure_message TEXT,
            updated_at INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE IF NOT EXISTS source_chapters (
            source_chapter_id INTEGER PRIMARY KEY AUTOINCREMENT,
            book_source_id INTEGER NOT NULL,
            external_chapter_id TEXT NOT NULL,
            chapter_id INTEGER,
            ordinal INTEGER NOT NULL,
            source_title TEXT NOT NULL,
            source_url TEXT NOT NULL DEFAULT '',
            body_sha256 TEXT,
            source_revision TEXT NOT NULL DEFAULT '',
            mapping_state TEXT NOT NULL DEFAULT 'confirmed',
            last_seen_at INTEGER NOT NULL DEFAULT 0,
            UNIQUE(book_source_id, external_chapter_id)
        );
        CREATE INDEX IF NOT EXISTS source_chapters_order
            ON source_chapters(book_source_id, ordinal);
        CREATE TABLE IF NOT EXISTS catalog_chapters (
            chapter_id INTEGER PRIMARY KEY AUTOINCREMENT,
            book_id INTEGER NOT NULL,
            ordinal INTEGER NOT NULL,
            canonical_title TEXT NOT NULL,
            chapter_state TEXT NOT NULL DEFAULT 'active',
            UNIQUE(book_id, ordinal)
        );
        CREATE INDEX IF NOT EXISTS catalog_chapters_book_order
            ON catalog_chapters(book_id, ordinal);
        CREATE TABLE IF NOT EXISTS source_chapter_bodies (
            book_source_id INTEGER NOT NULL,
            external_chapter_id TEXT NOT NULL,
            source_revision TEXT NOT NULL,
            title TEXT NOT NULL,
            body TEXT NOT NULL,
            sha256 TEXT NOT NULL,
            updated_at INTEGER NOT NULL,
            PRIMARY KEY(book_source_id, external_chapter_id, source_revision)
        );
        CREATE TABLE IF NOT EXISTS source_content_generations (
            book_source_id INTEGER PRIMARY KEY,
            updated_at INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE IF NOT EXISTS source_refresh_requests (
            book_source_id INTEGER PRIMARY KEY,
            requested_at INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS source_prefetch_items (
            job_id TEXT NOT NULL,
            book_source_id INTEGER NOT NULL,
            external_chapter_id TEXT NOT NULL,
            state TEXT NOT NULL DEFAULT 'queued',
            error TEXT,
            updated_at INTEGER NOT NULL,
            PRIMARY KEY(job_id, book_source_id, external_chapter_id)
        );
        CREATE TABLE IF NOT EXISTS catalog_migration_audits (
            audit_key TEXT PRIMARY KEY,
            expected_count INTEGER NOT NULL,
            actual_count INTEGER NOT NULL,
            expected_sha256 TEXT NOT NULL,
            actual_sha256 TEXT NOT NULL,
            verified INTEGER NOT NULL,
            created_at INTEGER NOT NULL
        );",
    )?;
    conn.execute(
        "CREATE TABLE IF NOT EXISTS book_identity_migration (
            legacy_id INTEGER PRIMARY KEY,
            book_id INTEGER NOT NULL UNIQUE
        )",
        [],
    )?;

    for (table, column, definition) in [
        ("books", "book_id", "INTEGER"),
        ("books", "canonical_title", "TEXT NOT NULL DEFAULT ''"),
        ("books", "canonical_author", "TEXT NOT NULL DEFAULT ''"),
        ("books", "match_state", "TEXT NOT NULL DEFAULT 'confirmed'"),
        (
            "book_sources",
            "normalization_version",
            "TEXT NOT NULL DEFAULT 'nfkc-v1'",
        ),
        ("book_chapters", "chapter_id", "INTEGER"),
        ("book_chapters", "external_chapter_id", "TEXT"),
        ("book_chapters", "book_source_id", "INTEGER"),
        (
            "book_chapters",
            "chapter_state",
            "TEXT NOT NULL DEFAULT 'active'",
        ),
        ("sync_runs", "source_key", "TEXT"),
        ("refresh_requests", "book_source_id", "INTEGER"),
        ("prefetch_jobs", "book_source_id", "INTEGER"),
        ("prefetch_items", "book_source_id", "INTEGER"),
        ("prefetch_items", "external_chapter_id", "TEXT"),
        ("content_generations", "book_source_id", "INTEGER"),
        ("chapter_bodies", "book_source_id", "INTEGER"),
        ("chapter_bodies", "external_chapter_id", "TEXT"),
        ("chapter_bodies", "source_revision", "TEXT"),
        (
            "source_chapters",
            "mapping_state",
            "TEXT NOT NULL DEFAULT 'confirmed'",
        ),
    ] {
        ensure_column(conn, table, column, definition)?;
    }

    conn.execute(
        "INSERT OR IGNORE INTO catalog_sources
            (source_key,display_name,base_url,adapter_version,priority,created_at,updated_at)
         VALUES ('suduguu','速读谷','https://www.suduguu.com','1',10,?1,?1)",
        params![now()],
    )?;
    conn.execute(
        "INSERT OR IGNORE INTO source_sync_state(source_key,rate_limit_ms,updated_at)
         VALUES ('suduguu',500,?1)",
        params![now()],
    )?;
    conn.execute(
        "INSERT OR IGNORE INTO catalog_sources
            (source_key,display_name,base_url,adapter_version,priority,created_at,updated_at)
         VALUES ('sudugu520','速读谷 520','https://www.sudugu520.com','1',20,?1,?1)",
        params![now()],
    )?;
    conn.execute(
        "INSERT OR IGNORE INTO source_sync_state(source_key,rate_limit_ms,updated_at)
         VALUES ('sudugu520',700,?1)",
        params![now()],
    )?;
    let max_id: i64 = conn.query_row("SELECT COALESCE(MAX(id),0) FROM books", [], |row| {
        row.get(0)
    })?;
    conn.execute(
        "INSERT OR IGNORE INTO book_identity_migration(legacy_id,book_id)
         SELECT id,?1 + ROW_NUMBER() OVER (ORDER BY id)
         FROM books WHERE book_id IS NULL",
        params![max_id],
    )?;
    conn.execute(
        "UPDATE books SET book_id=(
            SELECT book_id FROM book_identity_migration
            WHERE legacy_id=books.id
        ) WHERE book_id IS NULL",
        [],
    )?;
    for table in [
        "book_chapters",
        "chapter_bodies",
        "content_generations",
        "refresh_requests",
        "prefetch_jobs",
    ] {
        conn.execute(
            &format!(
                "UPDATE {table} SET book_id=(
                    SELECT book_id FROM book_identity_migration
                    WHERE legacy_id={table}.book_id
                ) WHERE EXISTS(
                    SELECT 1 FROM book_identity_migration
                    WHERE legacy_id={table}.book_id
                )"
            ),
            [],
        )?;
    }
    // recent_updates is keyed only by book_id.  A rolling migration can see
    // both the legacy ID and its new internal ID after a previous process
    // already wrote a recent-update marker; merge those rows before updating
    // the legacy key or SQLite will reject the migration with a UNIQUE error.
    conn.execute(
        "INSERT INTO recent_updates(book_id,observed_at)
         SELECT m.book_id,MAX(r.observed_at)
         FROM recent_updates r
         JOIN book_identity_migration m ON m.legacy_id=r.book_id
         GROUP BY m.book_id
         ON CONFLICT(book_id) DO UPDATE SET observed_at=
            CASE WHEN excluded.observed_at > recent_updates.observed_at
                 THEN excluded.observed_at ELSE recent_updates.observed_at END",
        [],
    )?;
    conn.execute(
        "DELETE FROM recent_updates
         WHERE book_id IN (SELECT legacy_id FROM book_identity_migration)",
        [],
    )?;
    conn.execute(
        "UPDATE book_chapters SET
            chapter_id=COALESCE(chapter_id,rowid),
            external_chapter_id=COALESCE(external_chapter_id,CAST(source_id AS TEXT))
         WHERE chapter_id IS NULL OR external_chapter_id IS NULL",
        [],
    )?;
    Ok(())
}

/// Normalize only metadata used for matching.  Original source fields remain
/// stored in book_sources so a later rule version can be audited/replayed.
pub fn normalize_catalog_text(value: &str) -> String {
    value
        .nfkc()
        .map(|ch| match ch {
            '\u{3000}' => ' ',
            '（' | '）' | '【' | '】' | '《' | '》' | '「' | '」' => ' ',
            _ => ch,
        })
        .collect::<String>()
        .to_lowercase()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

fn next_internal_book_id(conn: &Connection) -> rusqlite::Result<i64> {
    conn.query_row(
        "SELECT COALESCE(MAX(COALESCE(book_id,id)),0)+1 FROM books",
        [],
        |r| r.get(0),
    )
}

fn source_book_binding(
    conn: &Connection,
    source_key: &str,
    external_book_id: &str,
) -> rusqlite::Result<Option<(i64, i64)>> {
    conn.query_row(
        "SELECT book_source_id,book_id FROM book_sources
         WHERE source_key=?1 AND external_book_id=?2",
        params![source_key, external_book_id],
        |r| Ok((r.get(0)?, r.get(1)?)),
    )
    .optional()
}

fn resolve_source_book(
    conn: &Connection,
    source_key: &str,
    external_book_id: &str,
    title: &str,
    author: &str,
) -> rusqlite::Result<(i64, i64)> {
    if let Some(binding) = source_book_binding(conn, source_key, external_book_id)
        .map_err(|error| contextual_db_error("find source binding", error))?
    {
        return Ok(binding);
    }
    let normalized_title = normalize_catalog_text(title);
    let normalized_author = normalize_catalog_text(author);
    let mut stmt = conn.prepare(
        "SELECT COALESCE(book_id,id) FROM books
         WHERE canonical_title=?1 AND canonical_author=?2
         ORDER BY COALESCE(book_id,id)",
    )?;
    let candidates = stmt
        .query_map(params![normalized_title, normalized_author], |r| {
            r.get::<_, i64>(0)
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    let unique_candidate = if candidates.len() == 1 {
        let occupied: bool = conn.query_row(
            "SELECT EXISTS(
                SELECT 1 FROM book_sources
                WHERE book_id=?1 AND source_key=?2
            )",
            params![candidates[0], source_key],
            |r| r.get(0),
        )?;
        (!occupied).then_some(candidates[0])
    } else {
        None
    };
    let unique_candidate_available = unique_candidate.is_some();
    let book_id = if let Some(candidate_book_id) = unique_candidate {
        conn.execute(
            "INSERT INTO book_match_candidates
                (source_key,external_book_id,candidate_book_id,score,matched_rules,
                 source_title,source_author,normalized_title,normalized_author,state,created_at)
             SELECT ?1,?2,?3,1.0,'[\"title\",\"author\"]',?4,?5,?6,?7,'pending',?8
             WHERE NOT EXISTS(
                SELECT 1 FROM book_match_candidates
                WHERE source_key=?1 AND external_book_id=?2
                  AND candidate_book_id=?3 AND state='pending'
             )",
            params![
                source_key,
                external_book_id,
                candidate_book_id,
                title,
                author,
                normalized_title,
                normalized_author,
                now()
            ],
        )?;
        candidate_book_id
    } else {
        let id = next_internal_book_id(conn)?;
        conn.execute(
            "INSERT INTO books
                (id,book_id,title,author,canonical_title,canonical_author,match_state,
                 meta_level,first_seen_at)
            VALUES (?1,?1,?2,?3,?4,?5,'pending',1,?6)",
            params![
                id,
                title,
                author,
                normalized_title,
                normalized_author,
                now()
            ],
        )?;
        let matched_rules = if candidates.is_empty() {
            "[\"new_book\"]"
        } else {
            "[\"title\",\"author\"]"
        };
        for candidate in &candidates {
            conn.execute(
                "INSERT INTO book_match_candidates
                    (source_key,external_book_id,candidate_book_id,score,matched_rules,
                     source_title,source_author,normalized_title,normalized_author,state,created_at)
                 SELECT ?1,?2,?3,?4,?5,?6,?7,?8,?9,'pending',?10
                 WHERE NOT EXISTS(
                    SELECT 1 FROM book_match_candidates
                    WHERE source_key=?1 AND external_book_id=?2
                      AND candidate_book_id=?3 AND state='pending'
                 )",
                params![
                    source_key,
                    external_book_id,
                    candidate,
                    if candidates.len() == 1 && !unique_candidate_available {
                        1.0
                    } else {
                        0.5
                    },
                    matched_rules,
                    title,
                    author,
                    normalized_title,
                    normalized_author,
                    now()
                ],
            )?;
        }
        id
    };
    let book_source_id = ensure_book_source(
        conn,
        book_id,
        source_key,
        external_book_id,
        title,
        author,
        "candidate",
    )
    .map_err(|error| contextual_db_error("ensure source binding", error))?;
    Ok((book_id, book_source_id))
}

fn contextual_db_error(context: &str, error: rusqlite::Error) -> rusqlite::Error {
    rusqlite::Error::ToSqlConversionFailure(Box::new(std::io::Error::other(format!(
        "{context}: {error}"
    ))))
}

pub fn upsert_source_list(
    conn: &Connection,
    source_key: &str,
    item: &ExternalListItem,
) -> rusqlite::Result<i64> {
    let (book_id, _) = resolve_source_book(
        conn,
        source_key,
        &item.external_book_id,
        &item.title,
        &item.author,
    )?;
    let now = now();
    let (book_source_id, binding_state): (i64, String) = conn
        .query_row(
            "SELECT book_source_id,binding_state FROM book_sources
             WHERE source_key=?1 AND external_book_id=?2",
            params![source_key, item.external_book_id],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .map_err(|error| contextual_db_error("load source binding by external ID", error))?;
    conn.execute(
        "UPDATE books SET
            title=CASE WHEN ?2<>'' THEN ?2 ELSE title END,
            author=CASE WHEN ?3<>'' THEN ?3 ELSE author END,
            canonical_title=CASE WHEN canonical_title='' THEN ?4 ELSE canonical_title END,
            canonical_author=CASE WHEN canonical_author='' THEN ?5 ELSE canonical_author END,
            category=?6,status=?7,latest_chapter_id=CAST(?8 AS INTEGER),
            latest_chapter=?9,list_seen_at=?10,meta_level=MAX(meta_level,1)
         WHERE COALESCE(book_id,id)=?1
           AND (match_state<>'confirmed' OR ?11='confirmed')",
        params![
            book_id,
            item.title,
            item.author,
            normalize_catalog_text(&item.title),
            normalize_catalog_text(&item.author),
            item.category,
            item.status,
            item.latest_external_chapter_id.as_deref().unwrap_or("0"),
            item.latest_chapter,
            now,
            binding_state
        ],
    )?;
    conn.execute(
        "UPDATE book_sources SET
            source_title=?3,source_author=?4,normalized_title=?5,normalized_author=?6,
            latest_external_chapter_id=?7,last_checked_at=?8,updated_at=?8
         WHERE book_source_id=?1 AND source_key=?2",
        params![
            book_source_id,
            source_key,
            item.title,
            item.author,
            normalize_catalog_text(&item.title),
            normalize_catalog_text(&item.author),
            item.latest_external_chapter_id,
            now
        ],
    )?;
    Ok(book_id)
}

pub fn upsert_source_detail(
    conn: &Connection,
    source_key: &str,
    detail: &ExternalDetail,
) -> rusqlite::Result<i64> {
    let prior = conn
        .query_row(
            "SELECT book_source_id,binding_state FROM book_sources
             WHERE source_key=?1 AND external_book_id=?2",
            params![source_key, detail.external_book_id],
            |r| Ok((r.get::<_, i64>(0)?, r.get::<_, String>(1)?)),
        )
        .optional()?;
    if let Some((book_source_id, state)) = prior {
        if state == "confirmed" {
            let old = conn
                .prepare(
                    "SELECT external_chapter_id,source_title FROM source_chapters
                     WHERE book_source_id=?1 ORDER BY ordinal",
                )?
                .query_map(params![book_source_id], |r| {
                    Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?))
                })?
                .collect::<rusqlite::Result<Vec<_>>>()?;
            let safe_append = !old.is_empty()
                && detail.chapters.len() >= old.len()
                && old.iter().enumerate().all(|(index, (id, title))| {
                    detail.chapters.get(index).is_some_and(|chapter| {
                        chapter.external_chapter_id == *id
                            && normalize_catalog_text(&chapter.title)
                                == normalize_catalog_text(title)
                    })
                });
            if !old.is_empty() && !safe_append {
                conn.execute(
                    "UPDATE book_sources SET binding_state='stale',last_checked_at=?2,
                        updated_at=?2 WHERE book_source_id=?1",
                    params![book_source_id, now()],
                )?;
                let after = serde_json::json!({
                    "bindingState": "stale",
                    "reason": "chapter_reorder_or_missing"
                });
                audit_event(
                    conn,
                    Some(source_key),
                    Some(&detail.external_book_id),
                    None,
                    Some(book_source_id),
                    "source_manifest_conflict",
                    None,
                    Some(&after),
                    "confirmed source directory changed order or removed chapters",
                    "system",
                )?;
                return Err(rusqlite::Error::InvalidQuery);
            }
        }
    }
    let tx = conn.unchecked_transaction()?;
    let list = ExternalListItem {
        external_book_id: detail.external_book_id.clone(),
        title: detail.title.clone(),
        author: detail.author.clone(),
        category: detail.category.clone(),
        status: detail.status.clone(),
        latest_external_chapter_id: detail.latest_external_chapter_id.clone(),
        latest_chapter: detail.latest_chapter.clone(),
    };
    let mut book_id = upsert_source_list(&tx, source_key, &list)?;
    let book_source_id: i64 = tx.query_row(
        "SELECT book_source_id FROM book_sources
         WHERE source_key=?1 AND external_book_id=?2",
        params![source_key, detail.external_book_id],
        |r| r.get(0),
    )?;
    let binding_state: String = tx.query_row(
        "SELECT binding_state FROM book_sources WHERE book_source_id=?1",
        params![book_source_id],
        |r| r.get(0),
    )?;
    if matches!(binding_state.as_str(), "rejected" | "disabled") {
        return Err(rusqlite::Error::InvalidQuery);
    }
    let normalized_title = normalize_catalog_text(&detail.title);
    let normalized_author = normalize_catalog_text(&detail.author);
    let matching_books = tx
        .prepare(
            "SELECT COALESCE(book_id,id) FROM books
             WHERE canonical_title=?1 AND canonical_author=?2
               AND COALESCE(book_id,id)<>?3
             ORDER BY COALESCE(book_id,id)",
        )?
        .query_map(params![normalized_title, normalized_author, book_id], |r| {
            r.get::<_, i64>(0)
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    let matching_books = matching_books
        .into_iter()
        .collect::<std::collections::HashSet<_>>();
    let mut can_confirm = matching_books.len() <= 1;
    if binding_state != "confirmed" && matching_books.len() == 1 {
        let target = *matching_books.iter().next().unwrap();
        let occupied: bool = tx.query_row(
            "SELECT EXISTS(SELECT 1 FROM book_sources
             WHERE book_id=?1 AND source_key=?2 AND book_source_id<>?3)",
            params![target, source_key, book_source_id],
            |r| r.get(0),
        )?;
        if !occupied {
            tx.execute(
                "UPDATE book_sources SET book_id=?1,updated_at=?3
                 WHERE book_source_id=?2",
                params![target, book_source_id, now()],
            )?;
            book_id = target;
        } else {
            can_confirm = false;
        }
    }
    let final_state = if binding_state == "confirmed" || can_confirm {
        "confirmed"
    } else {
        "candidate"
    };
    let mut duplicate_titles = std::collections::HashSet::new();
    let mut title_counts = std::collections::HashMap::new();
    for chapter in &detail.chapters {
        let key = normalize_catalog_text(&chapter.title);
        *title_counts.entry(key).or_insert(0usize) += 1;
    }
    for (title, count) in title_counts {
        if count > 1 {
            duplicate_titles.insert(title);
        }
    }
    let revision = format!(
        "{:x}",
        sha2::Sha256::digest(
            detail
                .chapters
                .iter()
                .map(|chapter| format!("{}:{}\n", chapter.external_chapter_id, chapter.title))
                .collect::<String>()
                .as_bytes(),
        )
    );
    if final_state == "confirmed" {
        tx.execute(
            "UPDATE books SET words=?2,words_text=?3,chapter_no=?4,
                latest_chapter_id=CAST(?5 AS INTEGER),latest_chapter=?6,
                last_update_at=?7,detail_at=?8,detail_failures=0,meta_level=2,
                match_state='confirmed',canonical_title=?9,canonical_author=?10
             WHERE COALESCE(book_id,id)=?1",
            params![
                book_id,
                detail.words,
                detail.words_text,
                detail.chapter_no,
                detail.latest_external_chapter_id.as_deref().unwrap_or("0"),
                detail.latest_chapter,
                detail.last_update_at,
                now(),
                normalized_title,
                normalized_author
            ],
        )?;
    } else {
        tx.execute(
            "UPDATE books SET detail_at=?2,detail_failures=0,meta_level=MAX(meta_level,1)
             WHERE COALESCE(book_id,id)=?1 AND match_state<>'confirmed'",
            params![book_id, now()],
        )?;
    }
    tx.execute(
        "UPDATE book_sources SET
            canonical_url=?3,source_revision=?4,latest_external_chapter_id=?5,
            last_update_at=?6,last_checked_at=?7,binding_state=?8,updated_at=?7
         WHERE book_source_id=?1 AND source_key=?2",
        params![
            book_source_id,
            source_key,
            format!("/{}/", detail.external_book_id),
            revision,
            detail.latest_external_chapter_id,
            detail.last_update_at,
            now(),
            final_state
        ],
    )?;
    if final_state == "confirmed" {
        tx.execute(
            "UPDATE book_match_candidates SET state='confirmed',resolved_at=?3,resolved_by='system'
             WHERE source_key=?1 AND external_book_id=?2
               AND candidate_book_id=?4 AND state='pending'",
            params![source_key, detail.external_book_id, now(), book_id],
        )?;
        audit_event(
            &tx,
            Some(source_key),
            Some(&detail.external_book_id),
            Some(book_id),
            Some(book_source_id),
            "source_auto_confirmed",
            None,
            Some(&serde_json::json!({"bindingState": "confirmed"})),
            "unique title and author with valid detail directory",
            "system",
        )?;
    }
    for chapter in &detail.chapters {
        let existing_source: Option<(Option<i64>, String)> = tx
            .query_row(
                "SELECT chapter_id,mapping_state FROM source_chapters
                 WHERE book_source_id=?1 AND external_chapter_id=?2",
                params![book_source_id, chapter.external_chapter_id],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .optional()?;
        let (chapter_id, mut mapping_state) = match existing_source {
            Some((id, state)) => (id, state),
            None => {
                let existing: Option<(i64, String)> = tx
                    .query_row(
                        "SELECT chapter_id FROM catalog_chapters
                         WHERE book_id=?1 AND ordinal=?2",
                        params![book_id, chapter.ordinal as i64],
                        |r| Ok((r.get(0)?, String::new())),
                    )
                    .optional()?;
                if let Some((id, _)) = existing {
                    let title: String = tx.query_row(
                        "SELECT canonical_title FROM catalog_chapters WHERE chapter_id=?1",
                        params![id],
                        |r| r.get(0),
                    )?;
                    if normalize_catalog_text(&title) == normalize_catalog_text(&chapter.title) {
                        (Some(id), "confirmed".to_string())
                    } else {
                        (None, "ambiguous".to_string())
                    }
                } else {
                    let title_candidates = tx
                        .prepare(
                            "SELECT chapter_id FROM catalog_chapters
                             WHERE book_id=?1 AND canonical_title=?2",
                        )?
                        .query_map(params![book_id, chapter.title], |r| r.get::<_, i64>(0))?
                        .collect::<rusqlite::Result<Vec<_>>>()?;
                    if title_candidates.len() == 1 {
                        (Some(title_candidates[0]), "ambiguous".to_string())
                    } else if title_candidates.is_empty() {
                        tx.execute(
                            "INSERT INTO catalog_chapters(book_id,ordinal,canonical_title)
                             VALUES(?1,?2,?3)",
                            params![book_id, chapter.ordinal as i64, chapter.title],
                        )?;
                        (Some(tx.last_insert_rowid()), "confirmed".to_string())
                    } else {
                        (None, "ambiguous".to_string())
                    }
                }
            }
        };
        if duplicate_titles.contains(&normalize_catalog_text(&chapter.title)) {
            mapping_state = "ambiguous".to_string();
        }
        tx.execute(
            "INSERT INTO source_chapters
                (book_source_id,external_chapter_id,chapter_id,ordinal,source_title,
                 source_revision,mapping_state,last_seen_at)
             VALUES (?1,?2,?3,?4,?5,?6,?7,?8)
             ON CONFLICT(book_source_id,external_chapter_id) DO UPDATE SET
                source_title=excluded.source_title,source_revision=excluded.source_revision,
                last_seen_at=excluded.last_seen_at,
                chapter_id=CASE WHEN source_chapters.mapping_state='confirmed'
                    THEN source_chapters.chapter_id ELSE excluded.chapter_id END,
                ordinal=CASE WHEN source_chapters.mapping_state='confirmed'
                    THEN source_chapters.ordinal ELSE excluded.ordinal END,
                mapping_state=CASE WHEN source_chapters.mapping_state='confirmed'
                    THEN source_chapters.mapping_state ELSE excluded.mapping_state END",
            params![
                book_source_id,
                chapter.external_chapter_id,
                chapter_id,
                chapter.ordinal as i64,
                chapter.title,
                revision,
                mapping_state,
                now()
            ],
        )?;
    }
    tx.commit()?;
    Ok(book_id)
}

fn backfill_legacy_source_bindings(conn: &Connection) -> rusqlite::Result<()> {
    let now = now();
    conn.execute(
        "INSERT OR IGNORE INTO book_sources
            (book_id,source_key,external_book_id,canonical_url,source_title,source_author,
             normalized_title,normalized_author,binding_state,is_preferred,
             latest_external_chapter_id,last_update_at,created_at,updated_at)
         SELECT book_id,'suduguu',CAST(id AS TEXT),('/' || id || '/'),title,author,
                lower(trim(title)),lower(trim(author)),'confirmed',1,
                CAST(COALESCE(latest_chapter_id,0) AS TEXT),last_update_at,?1,?1
         FROM books",
        params![now],
    )?;
    let legacy_metadata = conn
        .prepare("SELECT COALESCE(book_id,id),title,author FROM books")?
        .query_map([], |r| {
            Ok((
                r.get::<_, i64>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, String>(2)?,
            ))
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    for (book_id, title, author) in legacy_metadata {
        conn.execute(
            "UPDATE books SET canonical_title=?2,canonical_author=?3
             WHERE COALESCE(book_id,id)=?1",
            params![
                book_id,
                normalize_catalog_text(&title),
                normalize_catalog_text(&author)
            ],
        )?;
        conn.execute(
            "UPDATE book_sources SET normalized_title=?2,normalized_author=?3,
                normalization_version='nfkc-v1'
             WHERE book_id=?1 AND source_key='suduguu'",
            params![
                book_id,
                normalize_catalog_text(&title),
                normalize_catalog_text(&author)
            ],
        )?;
    }
    conn.execute(
        "UPDATE book_chapters
         SET book_source_id=(
             SELECT bs.book_source_id FROM book_sources bs
             WHERE bs.book_id=book_chapters.book_id AND bs.source_key='suduguu'
         )
         WHERE book_source_id IS NULL",
        [],
    )?;
    conn.execute(
        "INSERT OR IGNORE INTO source_chapters
            (book_source_id,external_chapter_id,chapter_id,ordinal,source_title,
             body_sha256,source_revision,last_seen_at)
         SELECT book_source_id,COALESCE(external_chapter_id,CAST(source_id AS TEXT)),
                chapter_id,chapter_index,title,body_sha256,'legacy',?1
         FROM book_chapters WHERE book_source_id IS NOT NULL",
        params![now],
    )?;
    conn.execute(
        "INSERT OR IGNORE INTO source_chapter_bodies
            (book_source_id,external_chapter_id,source_revision,title,body,sha256,updated_at)
         SELECT cb.book_source_id,COALESCE(cb.external_chapter_id,CAST(cb.source_id AS TEXT)),
                COALESCE(NULLIF(cb.source_revision,''),'legacy'),cb.title,cb.body,cb.sha256,
                cb.updated_at
         FROM chapter_bodies cb
         WHERE cb.book_source_id IS NOT NULL",
        [],
    )?;
    Ok(())
}

/// Complete the additive migration for work tables that existed before
/// source-scoped identities were introduced.  The legacy columns remain for
/// the rolling compatibility window, while every new worker-facing row gets a
/// source binding and a string external chapter identity.
fn migrate_legacy_task_bindings(conn: &Connection) -> rusqlite::Result<()> {
    conn.execute(
        "UPDATE content_generations SET book_source_id=(
            SELECT book_source_id FROM book_sources
            WHERE book_sources.book_id=content_generations.book_id
              AND source_key='suduguu'
            ORDER BY is_preferred DESC,book_source_id LIMIT 1
        ) WHERE book_source_id IS NULL",
        [],
    )?;
    conn.execute(
        "UPDATE refresh_requests SET book_source_id=(
            SELECT book_source_id FROM book_sources
            WHERE book_sources.book_id=refresh_requests.book_id
              AND source_key='suduguu'
            ORDER BY is_preferred DESC,book_source_id LIMIT 1
        ) WHERE book_source_id IS NULL",
        [],
    )?;
    conn.execute(
        "UPDATE prefetch_jobs SET book_source_id=(
            SELECT book_source_id FROM book_sources
            WHERE book_sources.book_id=prefetch_jobs.book_id
              AND source_key='suduguu'
            ORDER BY is_preferred DESC,book_source_id LIMIT 1
        ) WHERE book_source_id IS NULL",
        [],
    )?;
    conn.execute(
        "UPDATE prefetch_items SET
            book_source_id=(
                SELECT j.book_source_id FROM prefetch_jobs j
                WHERE j.id=prefetch_items.job_id
            ),
            external_chapter_id=CAST(source_id AS TEXT)
         WHERE book_source_id IS NULL OR external_chapter_id IS NULL",
        [],
    )?;
    conn.execute(
        "INSERT OR IGNORE INTO source_refresh_requests(book_source_id,requested_at)
         SELECT book_source_id,requested_at FROM refresh_requests
         WHERE book_source_id IS NOT NULL",
        [],
    )?;
    conn.execute(
        "INSERT OR IGNORE INTO source_prefetch_items
            (job_id,book_source_id,external_chapter_id,state,error,updated_at)
         SELECT job_id,book_source_id,external_chapter_id,state,error,updated_at
         FROM prefetch_items
         WHERE book_source_id IS NOT NULL AND external_chapter_id IS NOT NULL",
        [],
    )?;

    for (key, expected_sql, actual_sql) in [
        (
            "books",
            "SELECT COUNT(*) FROM books",
            "SELECT COUNT(*) FROM books WHERE book_id IS NOT NULL",
        ),
        (
            "book_sources",
            "SELECT COUNT(*) FROM books",
            "SELECT COUNT(*) FROM book_sources WHERE source_key='suduguu'",
        ),
        (
            "chapters",
            "SELECT COUNT(*) FROM book_chapters",
            "SELECT COUNT(*) FROM source_chapters WHERE book_source_id IS NOT NULL",
        ),
    ] {
        let expected: i64 = conn.query_row(expected_sql, [], |r| r.get(0))?;
        let actual: i64 = conn.query_row(actual_sql, [], |r| r.get(0))?;
        let expected_sha256 = format!(
            "{:x}",
            sha2::Sha256::digest(expected.to_string().as_bytes())
        );
        let actual_sha256 = format!("{:x}", sha2::Sha256::digest(actual.to_string().as_bytes()));
        conn.execute(
            "INSERT INTO catalog_migration_audits
                (audit_key,expected_count,actual_count,expected_sha256,actual_sha256,verified,created_at)
             VALUES(?1,?2,?3,?4,?5,?6,?7)
             ON CONFLICT(audit_key) DO UPDATE SET
                expected_count=excluded.expected_count,actual_count=excluded.actual_count,
                expected_sha256=excluded.expected_sha256,actual_sha256=excluded.actual_sha256,
                verified=excluded.verified,created_at=excluded.created_at",
            params![
                key,
                expected,
                actual,
                expected_sha256,
                actual_sha256,
                (expected == actual) as i64,
                now()
            ],
        )?;
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

/// Create a source-scoped prefetch job.  The request addresses internal
/// chapter IDs, while the persisted item keeps the source's external chapter
/// identity so a second source can never reuse the first source's cache row.
pub fn create_source_prefetch_job(
    conn: &Connection,
    id: &str,
    book_id: i64,
    book_source_id: i64,
    priority: i64,
    chapter_ids: &[i64],
) -> rusqlite::Result<usize> {
    let now = now();
    let tx = conn.unchecked_transaction()?;
    let mut external_ids = Vec::with_capacity(chapter_ids.len());
    for chapter_id in chapter_ids {
        let external: String = tx.query_row(
            "SELECT external_chapter_id FROM source_chapters
             WHERE book_source_id=?1
               AND (source_chapter_id=?2 OR chapter_id=?2)
             ORDER BY CASE WHEN source_chapter_id=?2 THEN 0 ELSE 1 END
             LIMIT 1",
            params![book_source_id, chapter_id],
            |row| row.get(0),
        )?;
        external_ids.push(external);
    }
    tx.execute(
        "INSERT INTO prefetch_jobs
            (id,book_id,book_source_id,priority,state,total,created_at,updated_at)
         VALUES(?1,?2,?3,?4,'queued',?5,?6,?6)",
        params![
            id,
            book_id,
            book_source_id,
            priority,
            external_ids.len() as i64,
            now
        ],
    )?;
    for external_id in external_ids {
        tx.execute(
            "INSERT INTO source_prefetch_items
                (job_id,book_source_id,external_chapter_id,updated_at)
             VALUES(?1,?2,?3,?4)",
            params![id, book_source_id, external_id, now],
        )?;
    }
    tx.commit()?;
    Ok(chapter_ids.len())
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

pub fn update_source_prefetch_item(
    conn: &Connection,
    job_id: &str,
    book_source_id: i64,
    external_chapter_id: &str,
    state: &str,
    error: Option<&str>,
) -> rusqlite::Result<()> {
    let now = now();
    conn.execute(
        "UPDATE source_prefetch_items
         SET state=?4,error=?5,updated_at=?6
         WHERE job_id=?1 AND book_source_id=?2 AND external_chapter_id=?3",
        params![
            job_id,
            book_source_id,
            external_chapter_id,
            state,
            error,
            now
        ],
    )?;
    conn.execute(
        "UPDATE prefetch_jobs SET
            state=CASE
                WHEN EXISTS(
                    SELECT 1 FROM source_prefetch_items
                    WHERE job_id=?1 AND state IN ('queued','running')
                ) THEN 'running'
                WHEN EXISTS(
                    SELECT 1 FROM source_prefetch_items
                    WHERE job_id=?1 AND state='failed'
                ) THEN 'partial'
                ELSE 'completed'
            END,
            completed=(
                SELECT COUNT(*) FROM source_prefetch_items
                WHERE job_id=?1 AND state='completed'
            ),
            failed=(
                SELECT COUNT(*) FROM source_prefetch_items
                WHERE job_id=?1 AND state='failed'
            ),
            updated_at=?2
         WHERE id=?1",
        params![job_id, now],
    )?;
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
        "SELECT source_id,NULL AS external_chapter_id,state,error
         FROM prefetch_items WHERE job_id=?1
         UNION ALL
         SELECT NULL,external_chapter_id,state,error
         FROM source_prefetch_items WHERE job_id=?1
         ORDER BY state,external_chapter_id,source_id",
    )?;
    let items = stmt
        .query_map(params![id], |r| {
            Ok(serde_json::json!({
                "sourceId": r.get::<_, Option<i64>>(0)?,
                "externalChapterId": r.get::<_, Option<String>>(1)?,
                "state": r.get::<_, String>(2)?,
                "error": r.get::<_, Option<String>>(3)?
            }))
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    value["items"] = serde_json::to_value(items).unwrap_or_default();
    let source_key: Option<String> = conn
        .query_row(
            "SELECT bs.source_key
             FROM prefetch_jobs j
             JOIN book_sources bs ON bs.book_source_id=j.book_source_id
             WHERE j.id=?1",
            params![id],
            |r| r.get(0),
        )
        .optional()?;
    value["sourceKey"] = serde_json::to_value(source_key).unwrap_or(serde_json::Value::Null);
    Ok(Some(value))
}

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct SourceRow {
    pub source_key: String,
    pub display_name: String,
    pub base_url: String,
    pub adapter_version: String,
    pub enabled: bool,
    pub priority: i64,
    pub last_success_at: Option<i64>,
    pub last_failure_at: Option<i64>,
    pub last_failure_code: Option<String>,
    pub last_failure_message: Option<String>,
}

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct BookSourceRow {
    pub book_source_id: i64,
    pub book_id: i64,
    pub source_key: String,
    pub external_book_id: String,
    pub canonical_url: String,
    pub source_title: String,
    pub source_author: String,
    pub binding_state: String,
    pub is_preferred: bool,
    pub source_revision: String,
    pub latest_external_chapter_id: Option<String>,
    pub last_update_at: Option<String>,
    pub last_checked_at: Option<i64>,
}

pub fn ensure_source(
    conn: &Connection,
    source_key: &str,
    display_name: &str,
    base_url: &str,
    priority: i64,
) -> rusqlite::Result<()> {
    let now = now();
    conn.execute(
        "INSERT INTO catalog_sources
            (source_key,display_name,base_url,adapter_version,priority,created_at,updated_at)
         VALUES (?1,?2,?3,'1',?4,?5,?5)
         ON CONFLICT(source_key) DO UPDATE SET
            display_name=excluded.display_name,
            base_url=excluded.base_url,
            priority=excluded.priority,
            updated_at=excluded.updated_at",
        params![source_key, display_name, base_url, priority, now],
    )?;
    conn.execute(
        "INSERT OR IGNORE INTO source_sync_state(source_key,rate_limit_ms,updated_at)
         VALUES (?1,500,?2)",
        params![source_key, now],
    )?;
    Ok(())
}

pub fn sources(conn: &Connection) -> rusqlite::Result<Vec<SourceRow>> {
    let mut stmt = conn.prepare(
        "SELECT source_key,display_name,base_url,adapter_version,enabled,priority,
                last_success_at,last_failure_at,last_failure_code,last_failure_message
         FROM catalog_sources ORDER BY priority,source_key",
    )?;
    let rows = stmt
        .query_map([], |r| {
            Ok(SourceRow {
                source_key: r.get(0)?,
                display_name: r.get(1)?,
                base_url: r.get(2)?,
                adapter_version: r.get(3)?,
                enabled: r.get::<_, i64>(4)? != 0,
                priority: r.get(5)?,
                last_success_at: r.get(6)?,
                last_failure_at: r.get(7)?,
                last_failure_code: r.get(8)?,
                last_failure_message: r.get(9)?,
            })
        })?
        .collect();
    rows
}

pub fn get_source(conn: &Connection, source_key: &str) -> rusqlite::Result<Option<SourceRow>> {
    let rows = sources(conn)?;
    Ok(rows
        .into_iter()
        .find(|source| source.source_key == source_key))
}

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct SourceMetrics {
    pub source_key: String,
    pub total_bindings: i64,
    pub confirmed_bindings: i64,
    pub candidate_bindings: i64,
    pub rejected_bindings: i64,
    pub candidate_count: i64,
    pub confirmed_candidate_count: i64,
    pub redirect_failures: i64,
    pub parse_failures: i64,
    pub conflicts: i64,
    pub sync_runs: i64,
    pub successful_sync_runs: i64,
    pub success_rate: f64,
    pub latest_update_at: Option<String>,
}

pub fn source_metrics(conn: &Connection, source_key: &str) -> rusqlite::Result<SourceMetrics> {
    let count = |sql: &str| -> rusqlite::Result<i64> {
        conn.query_row(sql, params![source_key], |r| r.get(0))
    };
    let sync_runs: i64 = count("SELECT COUNT(*) FROM sync_runs WHERE source_key=?1")?;
    let successful_sync_runs: i64 = count(
        "SELECT COUNT(*) FROM sync_runs
         WHERE source_key=?1 AND finished_at IS NOT NULL
           AND COALESCE(error,'')=''",
    )?;
    let latest_update_at: Option<String> = conn
        .query_row(
            "SELECT MAX(last_update_at) FROM book_sources
             WHERE source_key=?1 AND last_update_at IS NOT NULL",
            params![source_key],
            |r| r.get(0),
        )
        .optional()?
        .flatten();
    Ok(SourceMetrics {
        source_key: source_key.into(),
        total_bindings: count("SELECT COUNT(*) FROM book_sources WHERE source_key=?1")?,
        confirmed_bindings: count(
            "SELECT COUNT(*) FROM book_sources
             WHERE source_key=?1 AND binding_state='confirmed'",
        )?,
        candidate_bindings: count(
            "SELECT COUNT(*) FROM book_sources
             WHERE source_key=?1 AND binding_state='candidate'",
        )?,
        rejected_bindings: count(
            "SELECT COUNT(*) FROM book_sources
             WHERE source_key=?1 AND binding_state IN ('rejected','disabled')",
        )?,
        candidate_count: count(
            "SELECT COUNT(*) FROM book_match_candidates
             WHERE source_key=?1 AND state='pending'",
        )?,
        confirmed_candidate_count: count(
            "SELECT COUNT(*) FROM book_match_candidates
             WHERE source_key=?1 AND state='confirmed'",
        )?,
        redirect_failures: count(
            "SELECT COUNT(*) FROM catalog_audit_events
             WHERE source_key=?1 AND reason LIKE '%redirect%'",
        )?,
        parse_failures: count(
            "SELECT COUNT(*) FROM catalog_audit_events
             WHERE source_key=?1 AND event_type LIKE '%parse%'",
        )?,
        conflicts: count(
            "SELECT COUNT(*) FROM catalog_audit_events
             WHERE source_key=?1 AND event_type LIKE '%conflict%'",
        )?,
        sync_runs,
        successful_sync_runs,
        success_rate: if sync_runs == 0 {
            0.0
        } else {
            successful_sync_runs as f64 / sync_runs as f64
        },
        latest_update_at,
    })
}

pub fn record_source_health(
    conn: &Connection,
    source_key: &str,
    success: bool,
    code: Option<&str>,
    message: Option<&str>,
) -> rusqlite::Result<()> {
    let now = now();
    if success {
        conn.execute(
            "UPDATE catalog_sources SET last_success_at=?2,last_failure_code=NULL,
                last_failure_message=NULL,updated_at=?2 WHERE source_key=?1",
            params![source_key, now],
        )?;
        conn.execute(
            "UPDATE source_sync_state SET retry_count=0,last_success_at=?2,
                last_failure_code=NULL,last_failure_message=NULL,updated_at=?2
             WHERE source_key=?1",
            params![source_key, now],
        )?;
    } else {
        conn.execute(
            "UPDATE catalog_sources SET last_failure_at=?2,last_failure_code=?3,
                last_failure_message=?4,updated_at=?2 WHERE source_key=?1",
            params![source_key, now, code, message],
        )?;
        conn.execute(
            "UPDATE source_sync_state SET retry_count=retry_count+1,last_failure_at=?2,
                last_failure_code=?3,last_failure_message=?4,updated_at=?2
             WHERE source_key=?1",
            params![source_key, now, code, message],
        )?;
    }
    Ok(())
}

pub fn set_source_cursor(
    conn: &Connection,
    source_key: &str,
    list_cursor: Option<&str>,
    detail_cursor: Option<&str>,
) -> rusqlite::Result<()> {
    conn.execute(
        "INSERT INTO source_sync_state(source_key,list_cursor,detail_cursor,updated_at)
         VALUES(?1,?2,?3,?4)
         ON CONFLICT(source_key) DO UPDATE SET
            list_cursor=COALESCE(?2,list_cursor),
            detail_cursor=COALESCE(?3,detail_cursor),
            updated_at=?4",
        params![source_key, list_cursor, detail_cursor, now()],
    )?;
    Ok(())
}

pub fn source_rate_limit_ms(conn: &Connection, source_key: &str) -> rusqlite::Result<i64> {
    conn.query_row(
        "SELECT COALESCE(rate_limit_ms,500) FROM source_sync_state WHERE source_key=?1",
        params![source_key],
        |r| r.get(0),
    )
    .optional()
    .map(|value| value.unwrap_or(500))
}

pub fn ensure_book_source(
    conn: &Connection,
    book_id: i64,
    source_key: &str,
    external_book_id: &str,
    title: &str,
    author: &str,
    state: &str,
) -> rusqlite::Result<i64> {
    let now = now();
    conn.execute(
        "INSERT INTO book_sources
            (book_id,source_key,external_book_id,canonical_url,source_title,source_author,
             normalized_title,normalized_author,binding_state,is_preferred,created_at,updated_at)
         VALUES (?1,?2,?3,?4,?5,?6,lower(trim(?5)),lower(trim(?6)),?7,
                 CASE WHEN NOT EXISTS(
                    SELECT 1 FROM book_sources WHERE book_id=?1 AND binding_state='confirmed'
                 ) THEN 1 ELSE 0 END,?8,?8)
         ON CONFLICT(source_key,external_book_id) DO UPDATE SET
            source_title=CASE WHEN excluded.source_title<>'' THEN excluded.source_title
                              ELSE book_sources.source_title END,
            source_author=CASE WHEN excluded.source_author<>'' THEN excluded.source_author
                               ELSE book_sources.source_author END,
            normalized_title=CASE WHEN excluded.source_title<>'' THEN lower(trim(excluded.source_title))
                                  ELSE book_sources.normalized_title END,
            normalized_author=CASE WHEN excluded.source_author<>'' THEN lower(trim(excluded.source_author))
                                   ELSE book_sources.normalized_author END,
            normalization_version='nfkc-v1',
            binding_state=CASE
                WHEN book_sources.binding_state IN ('confirmed','rejected','disabled')
                    THEN book_sources.binding_state
                ELSE excluded.binding_state
            END,
            updated_at=excluded.updated_at",
        params![
            book_id,
            source_key,
            external_book_id,
            format!("/{external_book_id}/"),
            title,
            author,
            state,
            now
        ],
    )?;
    conn.query_row(
        "SELECT book_source_id FROM book_sources
         WHERE source_key=?1 AND external_book_id=?2",
        params![source_key, external_book_id],
        |r| r.get(0),
    )
}

/// Remove a source directory before an explicit operator rebuild. This keeps
/// a stale/partially imported directory from poisoning the next source
/// mapping, while preserving the source binding row and canonical book.
pub fn reset_source_binding(
    conn: &Connection,
    source_key: &str,
    external_book_id: &str,
) -> rusqlite::Result<bool> {
    let tx = conn.unchecked_transaction()?;
    let Some(book_source_id) = tx
        .query_row(
            "SELECT book_source_id FROM book_sources
             WHERE source_key=?1 AND external_book_id=?2",
            params![source_key, external_book_id],
            |r| r.get::<_, i64>(0),
        )
        .optional()?
    else {
        return Ok(false);
    };
    tx.execute(
        "DELETE FROM source_prefetch_items WHERE book_source_id=?1",
        params![book_source_id],
    )?;
    tx.execute(
        "DELETE FROM prefetch_items WHERE book_source_id=?1",
        params![book_source_id],
    )?;
    tx.execute(
        "DELETE FROM source_refresh_requests WHERE book_source_id=?1",
        params![book_source_id],
    )?;
    tx.execute(
        "DELETE FROM source_chapter_bodies WHERE book_source_id=?1",
        params![book_source_id],
    )?;
    tx.execute(
        "DELETE FROM source_content_generations WHERE book_source_id=?1",
        params![book_source_id],
    )?;
    tx.execute(
        "DELETE FROM source_chapters WHERE book_source_id=?1",
        params![book_source_id],
    )?;
    tx.execute(
        "UPDATE book_sources SET
            binding_state='candidate',source_revision='',
            latest_external_chapter_id=NULL,last_update_at=NULL,
            last_checked_at=NULL,updated_at=?3
         WHERE book_source_id=?1 AND source_key=?2",
        params![book_source_id, source_key, now()],
    )?;
    tx.commit()?;
    Ok(true)
}

pub fn book_sources(conn: &Connection, book_id: i64) -> rusqlite::Result<Vec<BookSourceRow>> {
    let mut stmt = conn.prepare(
        "SELECT book_source_id,book_id,source_key,external_book_id,canonical_url,
                source_title,source_author,binding_state,is_preferred,source_revision,
                latest_external_chapter_id,last_update_at,last_checked_at
         FROM book_sources WHERE book_id=?1 ORDER BY is_preferred DESC,source_key",
    )?;
    let rows = stmt
        .query_map(params![book_id], |r| {
            Ok(BookSourceRow {
                book_source_id: r.get(0)?,
                book_id: r.get(1)?,
                source_key: r.get(2)?,
                external_book_id: r.get(3)?,
                canonical_url: r.get(4)?,
                source_title: r.get(5)?,
                source_author: r.get(6)?,
                binding_state: r.get(7)?,
                is_preferred: r.get::<_, i64>(8)? != 0,
                source_revision: r.get(9)?,
                latest_external_chapter_id: r.get(10)?,
                last_update_at: r.get(11)?,
                last_checked_at: r.get(12)?,
            })
        })?
        .collect();
    rows
}

pub fn find_book_source(
    conn: &Connection,
    book_id: i64,
    source_key: &str,
) -> rusqlite::Result<Option<BookSourceRow>> {
    Ok(book_sources(conn, book_id)?
        .into_iter()
        .find(|source| source.source_key == source_key))
}

pub fn find_book_source_by_id(
    conn: &Connection,
    book_source_id: i64,
) -> rusqlite::Result<Option<BookSourceRow>> {
    conn.query_row(
        "SELECT book_source_id,book_id,source_key,external_book_id,canonical_url,
                source_title,source_author,binding_state,is_preferred,source_revision,
                latest_external_chapter_id,last_update_at,last_checked_at
         FROM book_sources WHERE book_source_id=?1",
        params![book_source_id],
        |r| {
            Ok(BookSourceRow {
                book_source_id: r.get(0)?,
                book_id: r.get(1)?,
                source_key: r.get(2)?,
                external_book_id: r.get(3)?,
                canonical_url: r.get(4)?,
                source_title: r.get(5)?,
                source_author: r.get(6)?,
                binding_state: r.get(7)?,
                is_preferred: r.get::<_, i64>(8)? != 0,
                source_revision: r.get(9)?,
                latest_external_chapter_id: r.get(10)?,
                last_update_at: r.get(11)?,
                last_checked_at: r.get(12)?,
            })
        },
    )
    .optional()
}

pub fn source_prefetch_item(
    conn: &Connection,
    job_id: &str,
    book_source_id: i64,
    external_chapter_id: &str,
) -> rusqlite::Result<Option<(BookSourceRow, String, String)>> {
    let Some(binding) = find_book_source_by_id(conn, book_source_id)? else {
        return Ok(None);
    };
    conn.query_row(
        "SELECT sc.external_chapter_id,sc.source_title
         FROM source_prefetch_items i
         JOIN source_chapters sc
           ON sc.book_source_id=i.book_source_id
          AND sc.external_chapter_id=i.external_chapter_id
         WHERE i.job_id=?1 AND i.book_source_id=?2 AND i.external_chapter_id=?3",
        params![job_id, book_source_id, external_chapter_id],
        |r| Ok((binding.clone(), r.get(0)?, r.get(1)?)),
    )
    .optional()
}

pub fn find_external_book(
    conn: &Connection,
    source_key: &str,
    external_book_id: &str,
) -> rusqlite::Result<Option<BookSourceRow>> {
    conn.query_row(
        "SELECT book_source_id,book_id,source_key,external_book_id,canonical_url,
                source_title,source_author,binding_state,is_preferred,source_revision,
                latest_external_chapter_id,last_update_at,last_checked_at
         FROM book_sources WHERE source_key=?1 AND external_book_id=?2",
        params![source_key, external_book_id],
        |r| {
            Ok(BookSourceRow {
                book_source_id: r.get(0)?,
                book_id: r.get(1)?,
                source_key: r.get(2)?,
                external_book_id: r.get(3)?,
                canonical_url: r.get(4)?,
                source_title: r.get(5)?,
                source_author: r.get(6)?,
                binding_state: r.get(7)?,
                is_preferred: r.get::<_, i64>(8)? != 0,
                source_revision: r.get(9)?,
                latest_external_chapter_id: r.get(10)?,
                last_update_at: r.get(11)?,
                last_checked_at: r.get(12)?,
            })
        },
    )
    .optional()
}

/// Resolve an external book ID for clients created before catalog books got
/// their own internal IDs.  Those clients only know the old source ID and do
/// not send a source key, so prefer the historical suduguu binding and then a
/// confirmed/preferred binding deterministically.
pub fn find_external_book_legacy(
    conn: &Connection,
    external_book_id: &str,
) -> rusqlite::Result<Option<BookSourceRow>> {
    conn.query_row(
        "SELECT book_source_id,book_id,source_key,external_book_id,canonical_url,
                source_title,source_author,binding_state,is_preferred,source_revision,
                latest_external_chapter_id,last_update_at,last_checked_at
         FROM book_sources
         WHERE external_book_id=?1
         ORDER BY CASE WHEN source_key='suduguu' THEN 0 ELSE 1 END,
                  CASE WHEN binding_state='confirmed' THEN 0 ELSE 1 END,
                  is_preferred DESC,book_source_id
         LIMIT 1",
        params![external_book_id],
        |r| {
            Ok(BookSourceRow {
                book_source_id: r.get(0)?,
                book_id: r.get(1)?,
                source_key: r.get(2)?,
                external_book_id: r.get(3)?,
                canonical_url: r.get(4)?,
                source_title: r.get(5)?,
                source_author: r.get(6)?,
                binding_state: r.get(7)?,
                is_preferred: r.get::<_, i64>(8)? != 0,
                source_revision: r.get(9)?,
                latest_external_chapter_id: r.get(10)?,
                last_update_at: r.get(11)?,
                last_checked_at: r.get(12)?,
            })
        },
    )
    .optional()
}

pub fn set_preferred_source(
    conn: &Connection,
    book_id: i64,
    source_key: &str,
) -> rusqlite::Result<bool> {
    let tx = conn.unchecked_transaction()?;
    let exists: bool = tx.query_row(
        "SELECT EXISTS(SELECT 1 FROM book_sources
         WHERE book_id=?1 AND source_key=?2 AND binding_state='confirmed')",
        params![book_id, source_key],
        |r| r.get(0),
    )?;
    if !exists {
        return Ok(false);
    }
    tx.execute(
        "UPDATE book_sources SET is_preferred=0 WHERE book_id=?1",
        params![book_id],
    )?;
    tx.execute(
        "UPDATE book_sources SET is_preferred=1,updated_at=?3
         WHERE book_id=?1 AND source_key=?2",
        params![book_id, source_key, now()],
    )?;
    tx.commit()?;
    Ok(true)
}

pub fn candidate_books(
    conn: &Connection,
    title: &str,
    author: &str,
) -> rusqlite::Result<Vec<BookRow>> {
    let normalized_title = normalize_catalog_text(title);
    let normalized_author = normalize_catalog_text(author);
    let mut stmt = conn.prepare(&format!(
        "SELECT {BOOK_COLUMNS} FROM books
         WHERE canonical_title=?1
           AND (?2='' OR canonical_author=?2)
         ORDER BY id"
    ))?;
    let rows = stmt.query_map(params![normalized_title, normalized_author], map_book)?;
    rows.collect()
}

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct MatchCandidateRow {
    pub candidate_id: i64,
    pub candidate_book_id: Option<i64>,
    pub score: f64,
    pub matched_rules: String,
    pub source_title: String,
    pub source_author: String,
    pub state: String,
}

pub fn match_candidates(
    conn: &Connection,
    source_key: &str,
    external_book_id: &str,
) -> rusqlite::Result<Vec<MatchCandidateRow>> {
    let mut stmt = conn.prepare(
        "SELECT candidate_id,candidate_book_id,score,matched_rules,
                source_title,source_author,state
         FROM book_match_candidates
         WHERE source_key=?1 AND external_book_id=?2
         ORDER BY score DESC,candidate_id",
    )?;
    let rows = stmt.query_map(params![source_key, external_book_id], |r| {
        Ok(MatchCandidateRow {
            candidate_id: r.get(0)?,
            candidate_book_id: r.get(1)?,
            score: r.get(2)?,
            matched_rules: r.get(3)?,
            source_title: r.get(4)?,
            source_author: r.get(5)?,
            state: r.get(6)?,
        })
    })?;
    rows.collect()
}

pub fn audit_event(
    conn: &Connection,
    source_key: Option<&str>,
    external_book_id: Option<&str>,
    book_id: Option<i64>,
    book_source_id: Option<i64>,
    event_type: &str,
    before: Option<&serde_json::Value>,
    after: Option<&serde_json::Value>,
    reason: &str,
    actor: &str,
) -> rusqlite::Result<()> {
    conn.execute(
        "INSERT INTO catalog_audit_events
            (source_key,external_book_id,book_id,book_source_id,event_type,
             before_json,after_json,reason,actor,created_at)
         VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10)",
        params![
            source_key,
            external_book_id,
            book_id,
            book_source_id,
            event_type,
            before.map(|value| value.to_string()),
            after.map(|value| value.to_string()),
            reason,
            actor,
            now()
        ],
    )?;
    Ok(())
}

pub fn record_source_error(
    conn: &Connection,
    source_key: &str,
    external_book_id: Option<&str>,
    event_type: &str,
    reason: &str,
) -> rusqlite::Result<()> {
    audit_event(
        conn,
        Some(source_key),
        external_book_id,
        None,
        None,
        event_type,
        None,
        None,
        reason,
        "crawler",
    )
}

pub fn resolve_confirmed_source(
    conn: &Connection,
    book_id: i64,
    source_key: &str,
    external_book_id: &str,
    actor: &str,
) -> rusqlite::Result<bool> {
    let tx = conn.unchecked_transaction()?;
    let Some(binding) = find_external_book(&tx, source_key, external_book_id)? else {
        return Ok(false);
    };
    let target_exists: bool = tx.query_row(
        "SELECT EXISTS(SELECT 1 FROM books WHERE COALESCE(book_id,id)=?1)",
        params![book_id],
        |r| r.get(0),
    )?;
    if !target_exists {
        return Ok(false);
    }
    let conflict: bool = tx.query_row(
        "SELECT EXISTS(SELECT 1 FROM book_sources
         WHERE book_id=?1 AND source_key=?2 AND book_source_id<>?3)",
        params![book_id, source_key, binding.book_source_id],
        |r| r.get(0),
    )?;
    if conflict {
        return Ok(false);
    }
    let before = serde_json::to_value(&binding).unwrap_or_default();
    tx.execute(
        "UPDATE book_sources SET book_id=?1,binding_state='confirmed',updated_at=?4
         WHERE book_source_id=?2 AND source_key=?3",
        params![book_id, binding.book_source_id, source_key, now()],
    )?;
    tx.execute(
        "UPDATE books SET match_state='confirmed' WHERE COALESCE(book_id,id)=?1",
        params![book_id],
    )?;
    tx.execute(
        "UPDATE book_match_candidates SET
            state=CASE WHEN candidate_book_id=?3 THEN 'confirmed' ELSE 'rejected' END,
            resolved_at=?4,resolved_by=?5
         WHERE source_key=?1 AND external_book_id=?2 AND state='pending'",
        params![source_key, external_book_id, book_id, now(), actor],
    )?;
    let after =
        serde_json::to_value(find_book_source(&tx, book_id, source_key)?).unwrap_or_default();
    audit_event(
        &tx,
        Some(source_key),
        Some(external_book_id),
        Some(book_id),
        Some(binding.book_source_id),
        "source_confirmed",
        Some(&before),
        Some(&after),
        "manual confirmation",
        actor,
    )?;
    tx.commit()?;
    Ok(true)
}

pub fn reject_source(
    conn: &Connection,
    source_key: &str,
    external_book_id: &str,
    actor: &str,
    reason: &str,
) -> rusqlite::Result<bool> {
    let tx = conn.unchecked_transaction()?;
    let Some(binding) = find_external_book(&tx, source_key, external_book_id)? else {
        return Ok(false);
    };
    tx.execute(
        "UPDATE book_sources SET binding_state='rejected',updated_at=?3
         WHERE source_key=?1 AND external_book_id=?2",
        params![source_key, external_book_id, now()],
    )?;
    tx.execute(
        "UPDATE book_match_candidates SET state='rejected',resolved_at=?3,resolved_by=?4
         WHERE source_key=?1 AND external_book_id=?2 AND state='pending'",
        params![source_key, external_book_id, now(), actor],
    )?;
    audit_event(
        &tx,
        Some(source_key),
        Some(external_book_id),
        Some(binding.book_id),
        Some(binding.book_source_id),
        "source_rejected",
        Some(&serde_json::to_value(&binding).unwrap_or_default()),
        None,
        reason,
        actor,
    )?;
    tx.commit()?;
    Ok(true)
}

/// Explicitly split a source from its current logical book.  This is never
/// performed by a crawler; it is an operator action with an immutable audit
/// event and a fresh internal identity.
pub fn split_source(
    conn: &Connection,
    source_key: &str,
    external_book_id: &str,
    actor: &str,
    reason: &str,
) -> rusqlite::Result<Option<i64>> {
    let tx = conn.unchecked_transaction()?;
    let Some(binding) = find_external_book(&tx, source_key, external_book_id)? else {
        return Ok(None);
    };
    let title = binding.source_title.clone();
    let author = binding.source_author.clone();
    let new_id = next_internal_book_id(&tx)?;
    tx.execute(
        "INSERT INTO books
            (id,book_id,title,author,canonical_title,canonical_author,match_state,meta_level,first_seen_at)
         VALUES(?1,?1,?2,?3,?4,?5,'split',1,?6)",
        params![
            new_id,
            title,
            author,
            normalize_catalog_text(&binding.source_title),
            normalize_catalog_text(&binding.source_author),
            now()
        ],
    )?;
    tx.execute(
        "UPDATE book_sources SET book_id=?1,binding_state='confirmed',
            is_preferred=1,updated_at=?3
         WHERE book_source_id=?2",
        params![new_id, binding.book_source_id, now()],
    )?;
    tx.execute(
        "UPDATE source_chapters SET chapter_id=NULL,mapping_state='ambiguous'
         WHERE book_source_id=?1",
        params![binding.book_source_id],
    )?;
    tx.execute(
        "UPDATE book_match_candidates SET state='rejected',resolved_at=?3,resolved_by=?4
         WHERE source_key=?1 AND external_book_id=?2 AND state='pending'",
        params![source_key, external_book_id, now(), actor],
    )?;
    let after = serde_json::json!({"bookId": new_id, "state": "confirmed"});
    audit_event(
        &tx,
        Some(source_key),
        Some(external_book_id),
        Some(new_id),
        Some(binding.book_source_id),
        "source_split",
        Some(&serde_json::to_value(&binding).unwrap_or_default()),
        Some(&after),
        reason,
        actor,
    )?;
    tx.commit()?;
    Ok(Some(new_id))
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

pub fn known_latest_chapters_for_source(
    conn: &Connection,
    source_key: &str,
) -> rusqlite::Result<std::collections::HashMap<String, i64>> {
    let mut stmt = conn.prepare(
        "SELECT external_book_id,COALESCE(CAST(latest_external_chapter_id AS INTEGER),0)
         FROM book_sources WHERE source_key=?1",
    )?;
    let rows = stmt.query_map(params![source_key], |r| {
        Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?))
    })?;
    rows.collect()
}

pub fn pending_source_detail_ids(
    conn: &Connection,
    source_key: &str,
    limit: usize,
) -> rusqlite::Result<Vec<String>> {
    let mut stmt = conn.prepare(
        "SELECT bs.external_book_id FROM book_sources bs
         JOIN books b ON COALESCE(b.book_id,b.id)=bs.book_id
         WHERE bs.source_key=?1 AND b.meta_level<2
         ORDER BY b.detail_failures,bs.external_book_id LIMIT ?2",
    )?;
    let rows = stmt.query_map(params![source_key, limit as i64], |r| r.get(0))?;
    rows.collect()
}

pub fn pending_source_manifest_ids(
    conn: &Connection,
    source_key: &str,
    limit: usize,
) -> rusqlite::Result<Vec<String>> {
    let mut stmt = conn.prepare(
        "SELECT bs.external_book_id FROM book_sources bs
         JOIN books b ON COALESCE(b.book_id,b.id)=bs.book_id
         WHERE bs.source_key=?1
           AND (b.meta_level<2 OR
                (b.chapter_no>0 AND
                 (SELECT COUNT(*) FROM source_chapters sc
                  WHERE sc.book_source_id=bs.book_source_id)<b.chapter_no))
         ORDER BY b.detail_failures,bs.external_book_id LIMIT ?2",
    )?;
    let rows = stmt.query_map(params![source_key, limit as i64], |r| r.get(0))?;
    rows.collect()
}

pub fn source_external_ids_for_books(
    conn: &Connection,
    source_key: &str,
    book_ids: &[i64],
) -> rusqlite::Result<Vec<String>> {
    if book_ids.is_empty() {
        return Ok(Vec::new());
    }
    let placeholders = (0..book_ids.len())
        .map(|index| format!("?{}", index + 2))
        .collect::<Vec<_>>()
        .join(",");
    let sql = format!(
        "SELECT external_book_id FROM book_sources
         WHERE source_key=?1 AND book_id IN ({placeholders})
         ORDER BY external_book_id"
    );
    let mut stmt = conn.prepare(&sql)?;
    let mut values: Vec<&dyn rusqlite::ToSql> = Vec::with_capacity(book_ids.len() + 1);
    values.push(&source_key);
    for book_id in book_ids {
        values.push(book_id);
    }
    let rows = stmt
        .query_map(rusqlite::params_from_iter(values), |r| r.get(0))?
        .collect();
    rows
}

pub fn source_chapters(
    conn: &Connection,
    book_id: i64,
    source_key: &str,
    limit: usize,
    cursor: Option<i64>,
) -> rusqlite::Result<Vec<serde_json::Value>> {
    let mut stmt = conn.prepare(
        "SELECT sc.source_chapter_id,sc.chapter_id,sc.external_chapter_id,sc.ordinal,
                sc.source_title,sc.body_sha256
         FROM source_chapters sc
         JOIN book_sources bs ON bs.book_source_id=sc.book_source_id
         WHERE bs.book_id=?1 AND bs.source_key=?2
           AND (?3 IS NULL OR sc.ordinal>?3)
         ORDER BY sc.ordinal LIMIT ?4",
    )?;
    let rows = stmt.query_map(params![book_id, source_key, cursor, limit as i64], |r| {
        Ok(serde_json::json!({
            // Source manifests must use a source-scoped, unique ID.  A
            // canonical chapter ID is not sufficient because two source
            // chapters may legitimately have the same title and therefore
            // map to the same canonical chapter.
            "chapterId": r.get::<_, i64>(0)?,
            "canonicalChapterId": r.get::<_, Option<i64>>(1)?,
            "externalChapterId": r.get::<_, String>(2)?,
            "index": r.get::<_, i64>(3)?,
            "title": r.get::<_, String>(4)?,
            "bodySha256": r.get::<_, Option<String>>(5)?
        }))
    })?;
    rows.collect()
}

pub fn source_chapter_count(
    conn: &Connection,
    book_id: i64,
    source_key: &str,
) -> rusqlite::Result<i64> {
    conn.query_row(
        "SELECT COUNT(*) FROM source_chapters sc
         JOIN book_sources bs ON bs.book_source_id=sc.book_source_id
         WHERE bs.book_id=?1 AND bs.source_key=?2",
        params![book_id, source_key],
        |r| r.get(0),
    )
}

pub fn source_has_ambiguity(
    conn: &Connection,
    book_id: i64,
    source_key: &str,
) -> rusqlite::Result<bool> {
    // Mapping ambiguity only means that a source chapter could not be
    // matched to the canonical cross-source chapter.  Source-scoped
    // manifests and chapter requests use source_chapter_id and can still
    // serve these chapters safely, so duplicate titles or a missing
    // canonical mapping must not make an otherwise valid source unusable.
    let _ = (conn, book_id, source_key);
    Ok(false)
}

pub fn source_chapter(
    conn: &Connection,
    book_id: i64,
    source_key: &str,
    chapter_id: i64,
) -> rusqlite::Result<Option<(BookSourceRow, String, String, i64, Option<String>)>> {
    conn.query_row(
        "SELECT bs.book_source_id,bs.book_id,bs.source_key,bs.external_book_id,bs.canonical_url,
                bs.source_title,bs.source_author,bs.binding_state,bs.is_preferred,
                bs.source_revision,bs.latest_external_chapter_id,bs.last_update_at,
                bs.last_checked_at,sc.external_chapter_id,sc.source_title,sc.ordinal,
                sc.body_sha256
         FROM book_sources bs
         JOIN source_chapters sc ON sc.book_source_id=bs.book_source_id
         WHERE bs.book_id=?1 AND bs.source_key=?2
           AND (sc.source_chapter_id=?3 OR sc.chapter_id=?3)
         ORDER BY CASE WHEN sc.source_chapter_id=?3 THEN 0 ELSE 1 END
         LIMIT 1",
        params![book_id, source_key, chapter_id],
        |r| {
            Ok((
                BookSourceRow {
                    book_source_id: r.get(0)?,
                    book_id: r.get(1)?,
                    source_key: r.get(2)?,
                    external_book_id: r.get(3)?,
                    canonical_url: r.get(4)?,
                    source_title: r.get(5)?,
                    source_author: r.get(6)?,
                    binding_state: r.get(7)?,
                    is_preferred: r.get::<_, i64>(8)? != 0,
                    source_revision: r.get(9)?,
                    latest_external_chapter_id: r.get(10)?,
                    last_update_at: r.get(11)?,
                    last_checked_at: r.get(12)?,
                },
                r.get(13)?,
                r.get(14)?,
                r.get(15)?,
                r.get(16)?,
            ))
        },
    )
    .optional()
}

pub fn source_chapter_by_external_id(
    conn: &Connection,
    book_id: i64,
    source_key: &str,
    external_chapter_id: &str,
) -> rusqlite::Result<Option<(BookSourceRow, String, String, i64, Option<String>)>> {
    conn.query_row(
        "SELECT bs.book_source_id,bs.book_id,bs.source_key,bs.external_book_id,bs.canonical_url,
                bs.source_title,bs.source_author,bs.binding_state,bs.is_preferred,
                bs.source_revision,bs.latest_external_chapter_id,bs.last_update_at,
                bs.last_checked_at,sc.external_chapter_id,sc.source_title,sc.ordinal,
                sc.body_sha256
         FROM book_sources bs
         JOIN source_chapters sc ON sc.book_source_id=bs.book_source_id
         WHERE bs.book_id=?1 AND bs.source_key=?2 AND sc.external_chapter_id=?3
         LIMIT 1",
        params![book_id, source_key, external_chapter_id],
        |r| {
            Ok((
                BookSourceRow {
                    book_source_id: r.get(0)?,
                    book_id: r.get(1)?,
                    source_key: r.get(2)?,
                    external_book_id: r.get(3)?,
                    canonical_url: r.get(4)?,
                    source_title: r.get(5)?,
                    source_author: r.get(6)?,
                    binding_state: r.get(7)?,
                    is_preferred: r.get::<_, i64>(8)? != 0,
                    source_revision: r.get(9)?,
                    latest_external_chapter_id: r.get(10)?,
                    last_update_at: r.get(11)?,
                    last_checked_at: r.get(12)?,
                },
                r.get(13)?,
                r.get(14)?,
                r.get(15)?,
                r.get(16)?,
            ))
        },
    )
    .optional()
}

pub fn source_manifest_revision(
    conn: &Connection,
    book_id: i64,
    source_key: &str,
) -> rusqlite::Result<String> {
    use sha2::{Digest, Sha256};
    let mut hash = Sha256::new();
    let source = find_book_source(conn, book_id, source_key)?;
    let Some(source) = source else {
        return Ok(format!("catalog-source-v1:{book_id}:{source_key}:missing"));
    };
    let mut stmt = conn.prepare(
        "SELECT external_chapter_id,ordinal,source_title,COALESCE(body_sha256,'')
         FROM source_chapters WHERE book_source_id=?1 ORDER BY ordinal",
    )?;
    let rows = stmt.query_map(params![source.book_source_id], |r| {
        Ok((
            r.get::<_, String>(0)?,
            r.get::<_, i64>(1)?,
            r.get::<_, String>(2)?,
            r.get::<_, String>(3)?,
        ))
    })?;
    for row in rows {
        let (id, ordinal, title, body) = row?;
        hash.update(format!("{id}:{ordinal}:{title}:{body}\n").as_bytes());
    }
    hash.update(source.source_revision.as_bytes());
    Ok(format!(
        "catalog-source-v1:{book_id}:{source_key}:{:x}",
        hash.finalize()
    ))
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

pub fn mark_source_recent_update(conn: &Connection, book_source_id: i64) -> rusqlite::Result<()> {
    conn.execute(
        "INSERT INTO source_recent_updates(book_source_id,observed_at) VALUES(?1,?2)
         ON CONFLICT(book_source_id) DO UPDATE SET observed_at=excluded.observed_at",
        params![book_source_id, now()],
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

pub fn is_source_recent_update(conn: &Connection, book_source_id: i64) -> rusqlite::Result<bool> {
    let cutoff = now() - RECENT_UPDATE_WINDOW;
    conn.query_row(
        "SELECT EXISTS(SELECT 1 FROM source_recent_updates
         WHERE book_source_id=?1 AND observed_at>=?2)",
        params![book_source_id, cutoff],
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

pub fn request_source_refresh(conn: &Connection, book_source_id: i64) -> rusqlite::Result<()> {
    conn.execute(
        "INSERT INTO source_refresh_requests(book_source_id,requested_at) VALUES(?1,?2)
         ON CONFLICT(book_source_id) DO UPDATE SET requested_at=excluded.requested_at",
        params![book_source_id, now()],
    )?;
    Ok(())
}

pub fn pending_source_refreshes(
    conn: &Connection,
    source_key: &str,
    limit: usize,
) -> rusqlite::Result<Vec<(i64, i64)>> {
    let mut stmt = conn.prepare(
        "SELECT r.book_source_id,bs.book_id
         FROM source_refresh_requests r
         JOIN book_sources bs ON bs.book_source_id=r.book_source_id
         WHERE bs.source_key=?1
         ORDER BY r.requested_at,r.book_source_id LIMIT ?2",
    )?;
    let rows = stmt.query_map(params![source_key, limit as i64], |r| {
        Ok((r.get(0)?, r.get(1)?))
    })?;
    rows.collect()
}

pub fn clear_source_refreshes(
    conn: &Connection,
    source_key: &str,
    book_source_ids: &[i64],
) -> rusqlite::Result<()> {
    for book_source_id in book_source_ids {
        conn.execute(
            "DELETE FROM source_refresh_requests
             WHERE book_source_id=?1 AND EXISTS(
                SELECT 1 FROM book_sources WHERE book_source_id=?1 AND source_key=?2
             )",
            params![book_source_id, source_key],
        )?;
    }
    Ok(())
}

pub fn pending_refresh_ids(conn: &Connection, limit: usize) -> rusqlite::Result<Vec<i64>> {
    let mut stmt = conn
        .prepare("SELECT book_id FROM refresh_requests ORDER BY requested_at,book_id LIMIT ?1")?;
    let rows = stmt.query_map(params![limit as i64], |r| r.get::<_, i64>(0))?;
    rows.collect()
}

pub fn clear_refresh_requests(conn: &Connection, ids: &[i64]) -> rusqlite::Result<()> {
    for book_id in ids {
        conn.execute(
            "DELETE FROM refresh_requests WHERE book_id=?1",
            params![book_id],
        )?;
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

const BOOK_COLUMNS: &str =
    "COALESCE(book_id,id) AS id, title, author, category, status, COALESCE(words,0),
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
         WHERE (title LIKE ?1 OR author LIKE ?1)
           AND (?2 IS NULL OR COALESCE(book_id,id) > ?2)
         ORDER BY last_update_at DESC, COALESCE(book_id,id) DESC LIMIT ?3"
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
           AND (?3 IS NULL OR COALESCE(book_id,id) < ?3)
         ORDER BY {order} LIMIT ?4"
    );
    let mut stmt = conn.prepare(&sql)?;
    let rows = stmt.query_map(params![category, status, cursor, limit as i64], map_book)?;
    rows.collect()
}

pub fn get_book(conn: &Connection, id: i64) -> rusqlite::Result<Option<BookRow>> {
    let sql = format!("SELECT {BOOK_COLUMNS} FROM books WHERE COALESCE(book_id,id) = ?1");
    conn.query_row(&sql, params![id], map_book).optional()
}

pub fn start_sync_run(conn: &Connection, kind: &str) -> rusqlite::Result<i64> {
    conn.execute(
        "INSERT INTO sync_runs (kind, started_at) VALUES (?1, ?2)",
        params![kind, now()],
    )?;
    Ok(conn.last_insert_rowid())
}

pub fn start_source_sync_run(
    conn: &Connection,
    kind: &str,
    source_key: &str,
) -> rusqlite::Result<i64> {
    conn.execute(
        "INSERT INTO sync_runs (kind,source_key,started_at) VALUES (?1,?2,?3)",
        params![kind, source_key, now()],
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sources::{SourceAdapter, Sudugu520Adapter, SuduguuAdapter};

    #[test]
    fn migration_keeps_legacy_book_and_chapter_data_source_scoped() {
        let conn = Connection::open_in_memory().unwrap();
        init(&conn).unwrap();
        conn.execute(
            "INSERT INTO books(id,title,author,chapter_no) VALUES(203,'测试书','作者',2)",
            [],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO book_chapters(book_id,source_id,chapter_index,title)
             VALUES(203,407,0,'第一章')",
            [],
        )
        .unwrap();
        init(&conn).unwrap();
        let binding = find_external_book(&conn, "suduguu", "203")
            .unwrap()
            .expect("legacy source binding");
        assert_ne!(binding.book_id, 203);
        assert_eq!(binding.external_book_id, "203");
        assert!(conn
            .query_row(
                "SELECT EXISTS(SELECT 1 FROM source_chapters
                 WHERE book_source_id=?1 AND external_chapter_id='407')",
                params![binding.book_source_id],
                |r| r.get::<_, bool>(0),
            )
            .unwrap());
    }

    #[test]
    fn migration_is_idempotent_and_keeps_internal_identity_stable() {
        let conn = Connection::open_in_memory().unwrap();
        init(&conn).unwrap();
        conn.execute(
            "INSERT INTO books(id,title,author,chapter_no)
             VALUES(203,'稳定书','作者',1)",
            [],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO book_chapters(book_id,source_id,chapter_index,title)
             VALUES(203,407,0,'第一章')",
            [],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO recent_updates(book_id,observed_at) VALUES(203,1)",
            [],
        )
        .unwrap();
        init(&conn).unwrap();
        let first = find_external_book(&conn, "suduguu", "203")
            .unwrap()
            .unwrap();
        init(&conn).unwrap();
        let second = find_external_book(&conn, "suduguu", "203")
            .unwrap()
            .unwrap();
        assert_eq!(first.book_id, second.book_id);
        assert_eq!(first.book_source_id, second.book_source_id);
        assert_eq!(
            conn.query_row(
                "SELECT COUNT(*) FROM book_sources
                 WHERE source_key='suduguu' AND external_book_id='203'",
                [],
                |r| r.get::<_, i64>(0)
            )
            .unwrap(),
            1
        );
        assert_eq!(
            conn.query_row(
                "SELECT COUNT(*) FROM source_chapters WHERE book_source_id=?1",
                params![first.book_source_id],
                |r| r.get::<_, i64>(0)
            )
            .unwrap(),
            1
        );
        assert_eq!(
            conn.query_row(
                "SELECT verified FROM catalog_migration_audits WHERE audit_key='books'",
                [],
                |r| r.get::<_, i64>(0)
            )
            .unwrap(),
            1
        );
        assert_eq!(
            conn.query_row(
                "SELECT COUNT(*) FROM recent_updates
                 WHERE book_id=?1",
                params![first.book_id],
                |r| r.get::<_, i64>(0)
            )
            .unwrap(),
            1
        );
        assert_eq!(
            conn.query_row(
                "SELECT COUNT(*) FROM recent_updates
                 WHERE book_id=203",
                [],
                |r| r.get::<_, i64>(0)
            )
            .unwrap(),
            0
        );
    }

    #[test]
    fn restart_duplicate_sync_and_reused_source_ids_keep_identity_stable() {
        let path = std::env::temp_dir().join(format!(
            "shanye-catalog-stability-{}-{}.sqlite",
            std::process::id(),
            now()
        ));
        let detail = |source_id: &str, chapter_ids: &[(&str, &str)]| ExternalDetail {
            external_book_id: source_id.into(),
            title: "稳定书".into(),
            author: "稳定作者".into(),
            category: "分类".into(),
            status: "连载中".into(),
            words_text: "1万字".into(),
            words: 10_000,
            last_update_at: "2026-11-26 10:00:00".into(),
            latest_external_chapter_id: chapter_ids.last().map(|(id, _)| (*id).into()),
            latest_chapter: chapter_ids
                .last()
                .map(|(_, title)| (*title).into())
                .unwrap_or_default(),
            chapter_no: chapter_ids.len() as i64,
            chapters: chapter_ids
                .iter()
                .enumerate()
                .map(|(ordinal, (id, title))| crate::sources::ExternalChapter {
                    external_chapter_id: (*id).into(),
                    ordinal,
                    title: (*title).into(),
                })
                .collect(),
        };

        let first = {
            let conn = open(path.to_str().unwrap()).unwrap();
            let book_id = upsert_source_detail(
                &conn,
                "suduguu",
                &detail("203", &[("407", "第一章"), ("408", "第二章")]),
            )
            .unwrap();
            let binding = find_book_source(&conn, book_id, "suduguu")
                .unwrap()
                .unwrap();
            let chapters = source_chapters(&conn, book_id, "suduguu", 10, None).unwrap();
            (
                book_id,
                binding.book_source_id,
                chapters
                    .into_iter()
                    .map(|chapter| {
                        (
                            chapter["chapterId"].as_i64().unwrap(),
                            chapter["externalChapterId"].as_str().unwrap().to_owned(),
                        )
                    })
                    .collect::<Vec<_>>(),
            )
        };

        let second = {
            let conn = open(path.to_str().unwrap()).unwrap();
            let book_id = upsert_source_detail(
                &conn,
                "suduguu",
                &detail("203", &[("407", "第一章"), ("408", "第二章")]),
            )
            .unwrap();
            let binding = find_book_source(&conn, book_id, "suduguu")
                .unwrap()
                .unwrap();
            let chapters = source_chapters(&conn, book_id, "suduguu", 10, None).unwrap();
            assert_eq!(first.0, book_id);
            assert_eq!(first.1, binding.book_source_id);
            assert_eq!(first.2.len(), chapters.len());
            for (expected, actual) in first.2.iter().zip(chapters) {
                assert_eq!(Some(expected.0), actual["chapterId"].as_i64());
                assert_eq!(expected.1, actual["externalChapterId"]);
            }

            // The same external ID on another source is a separate binding,
            // even when both sources describe the same logical book.
            let other_book = upsert_source_detail(
                &conn,
                "sudugu520",
                &detail("203", &[("900", "第一章"), ("901", "第二章")]),
            )
            .unwrap();
            assert_eq!(book_id, other_book);
            assert_eq!(book_sources(&conn, book_id).unwrap().len(), 2);
            (book_id, binding.book_source_id)
        };

        assert_eq!(first.0, second.0);
        assert_eq!(first.1, second.1);

        {
            let conn = open(path.to_str().unwrap()).unwrap();
            // If a source reuses an external book ID for a different directory,
            // preserve the existing internal book/chapter identity and mark
            // the source stale instead of replacing the confirmed manifest.
            assert!(upsert_source_detail(
                &conn,
                "suduguu",
                &detail("203", &[("990", "新第一章"), ("991", "新第二章")]),
            )
            .is_err());
            let binding = find_external_book(&conn, "suduguu", "203")
                .unwrap()
                .unwrap();
            assert_eq!(binding.book_id, first.0);
            assert_eq!(binding.book_source_id, first.1);
            assert_eq!(binding.binding_state, "stale");
            let chapters = source_chapters(&conn, first.0, "suduguu", 10, None).unwrap();
            assert_eq!(
                chapters
                    .iter()
                    .map(|chapter| chapter["externalChapterId"].as_str().unwrap())
                    .collect::<Vec<_>>(),
                vec!["407", "408"]
            );
        }

        let _ = std::fs::remove_file(&path);
        let _ = std::fs::remove_file(path.with_extension("sqlite-wal"));
        let _ = std::fs::remove_file(path.with_extension("sqlite-shm"));
    }

    #[test]
    fn same_external_id_can_exist_in_two_sources_without_overwrite() {
        let conn = Connection::open_in_memory().unwrap();
        init(&conn).unwrap();
        ensure_source(
            &conn,
            "sudugu520",
            "速读谷 520",
            "https://www.sudugu520.com",
            20,
        )
        .unwrap();
        let a = ExternalListItem {
            external_book_id: "407".into(),
            title: "同一本书".into(),
            author: "作者".into(),
            category: "分类".into(),
            status: "连载中".into(),
            latest_external_chapter_id: Some("1".into()),
            latest_chapter: "第一章".into(),
        };
        let first = upsert_source_list(&conn, "suduguu", &a).unwrap();
        let second = upsert_source_list(&conn, "sudugu520", &a).unwrap();
        assert_eq!(first, second);
        let count: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM book_sources WHERE external_book_id='407'",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(count, 2);
        assert_eq!(
            find_external_book(&conn, "suduguu", "407")
                .unwrap()
                .unwrap()
                .source_key,
            "suduguu"
        );
        assert_eq!(
            find_external_book(&conn, "sudugu520", "407")
                .unwrap()
                .unwrap()
                .source_key,
            "sudugu520"
        );
    }

    #[test]
    fn normalized_title_and_author_merge_only_unique_matches() {
        let conn = Connection::open_in_memory().unwrap();
        init(&conn).unwrap();
        let first = ExternalListItem {
            external_book_id: "203".into(),
            title: "《测试书》".into(),
            author: "作者".into(),
            category: "分类".into(),
            status: "连载中".into(),
            latest_external_chapter_id: Some("1".into()),
            latest_chapter: "第一章".into(),
        };
        let first_id = upsert_source_list(&conn, "suduguu", &first).unwrap();
        let second = ExternalListItem {
            external_book_id: "407".into(),
            title: "测试书".into(),
            author: "作者".into(),
            ..first.clone()
        };
        let second_id = upsert_source_list(&conn, "sudugu520", &second).unwrap();
        assert_eq!(first_id, second_id);
        assert_eq!(
            normalize_catalog_text("《测试书》　"),
            normalize_catalog_text("测试书")
        );
        assert_eq!(normalize_catalog_text("作　者"), "作 者");
    }

    #[test]
    fn same_book_on_two_sources_is_confirmed_without_rebinding() {
        let conn = Connection::open_in_memory().unwrap();
        init(&conn).unwrap();
        let detail = |id: &str, title: &str| ExternalDetail {
            external_book_id: id.into(),
            title: title.into(),
            author: "作者".into(),
            category: "分类".into(),
            status: "连载中".into(),
            words_text: "1万字".into(),
            words: 10_000,
            last_update_at: "2026-11-26 10:00:00".into(),
            latest_external_chapter_id: Some("2".into()),
            latest_chapter: "第二章".into(),
            chapter_no: 2,
            chapters: vec![
                crate::sources::ExternalChapter {
                    external_chapter_id: "1".into(),
                    ordinal: 0,
                    title: "第一章".into(),
                },
                crate::sources::ExternalChapter {
                    external_chapter_id: "2".into(),
                    ordinal: 1,
                    title: title.into(),
                },
            ],
        };
        let first = upsert_source_detail(&conn, "suduguu", &detail("203", "第二章")).unwrap();
        let second = upsert_source_detail(&conn, "sudugu520", &detail("407", "第二章")).unwrap();
        assert_eq!(first, second);
        assert_eq!(book_sources(&conn, first).unwrap().len(), 2);
        assert!(book_sources(&conn, first)
            .unwrap()
            .iter()
            .all(|source| source.binding_state == "confirmed"));
    }

    #[test]
    fn confirmed_source_manifest_conflict_does_not_replace_old_chapters() {
        let conn = Connection::open_in_memory().unwrap();
        init(&conn).unwrap();
        let make = |chapters: Vec<(&str, usize, &str)>| ExternalDetail {
            external_book_id: "203".into(),
            title: "测试书".into(),
            author: "作者".into(),
            category: "分类".into(),
            status: "连载中".into(),
            words_text: "1万字".into(),
            words: 10_000,
            last_update_at: "2026-11-26 10:00:00".into(),
            latest_external_chapter_id: Some("2".into()),
            latest_chapter: "第二章".into(),
            chapter_no: 2,
            chapters: chapters
                .into_iter()
                .map(|(id, ordinal, title)| crate::sources::ExternalChapter {
                    external_chapter_id: id.into(),
                    ordinal,
                    title: title.into(),
                })
                .collect(),
        };
        let book = upsert_source_detail(
            &conn,
            "suduguu",
            &make(vec![("1", 0, "第一章"), ("2", 1, "第二章")]),
        )
        .unwrap();
        assert!(upsert_source_detail(
            &conn,
            "suduguu",
            &make(vec![("2", 0, "第二章"), ("1", 1, "第一章")]),
        )
        .is_err());
        let source = find_book_source(&conn, book, "suduguu").unwrap().unwrap();
        assert_eq!(source.binding_state, "stale");
        let ids = source_chapters(&conn, book, "suduguu", 10, None).unwrap();
        assert_eq!(ids[0]["externalChapterId"], "1");
    }

    #[test]
    fn source_prefetch_keeps_external_chapters_bound_to_the_selected_source() {
        let conn = Connection::open_in_memory().unwrap();
        init(&conn).unwrap();
        let detail = |source_id: &str| ExternalDetail {
            external_book_id: source_id.into(),
            title: "测试书".into(),
            author: "作者".into(),
            category: "分类".into(),
            status: "连载中".into(),
            words_text: "1万字".into(),
            words: 10_000,
            last_update_at: "2026-11-26 10:00:00".into(),
            latest_external_chapter_id: Some("20".into()),
            latest_chapter: "第二十章".into(),
            chapter_no: 20,
            chapters: vec![
                crate::sources::ExternalChapter {
                    external_chapter_id: "10".into(),
                    ordinal: 0,
                    title: "第一章".into(),
                },
                crate::sources::ExternalChapter {
                    external_chapter_id: "20".into(),
                    ordinal: 1,
                    title: "第二十章".into(),
                },
            ],
        };
        let book = upsert_source_detail(&conn, "suduguu", &detail("203")).unwrap();
        let source = find_book_source(&conn, book, "suduguu").unwrap().unwrap();
        let chapter_id: i64 = conn
            .query_row(
                "SELECT chapter_id FROM source_chapters
                 WHERE book_source_id=?1 AND external_chapter_id='20'",
                params![source.book_source_id],
                |r| r.get(0),
            )
            .unwrap();
        let count = create_source_prefetch_job(
            &conn,
            "source-prefetch",
            book,
            source.book_source_id,
            50,
            &[chapter_id],
        )
        .unwrap();
        assert_eq!(count, 1);
        let job = prefetch_job(&conn, "source-prefetch").unwrap().unwrap();
        assert_eq!(job["sourceKey"], "suduguu");
        assert_eq!(job["items"][0]["externalChapterId"], "20");
        assert!(job["items"][0]["sourceId"].is_null());
    }

    #[test]
    fn ambiguous_source_match_stays_candidate() {
        let conn = Connection::open_in_memory().unwrap();
        init(&conn).unwrap();
        for id in [1001_i64, 1002_i64] {
            conn.execute(
                "INSERT INTO books(id,book_id,title,author,canonical_title,canonical_author,match_state)
                 VALUES(?1,?1,'同名书','甲','同名书','甲','confirmed')",
                params![id],
            )
            .unwrap();
        }
        let detail = ExternalDetail {
            external_book_id: "407".into(),
            title: "同名书".into(),
            author: "甲".into(),
            category: "分类".into(),
            status: "连载中".into(),
            words_text: "1万字".into(),
            words: 10_000,
            last_update_at: "2026-11-26 10:00:00".into(),
            latest_external_chapter_id: Some("1".into()),
            latest_chapter: "第一章".into(),
            chapter_no: 1,
            chapters: vec![crate::sources::ExternalChapter {
                external_chapter_id: "1".into(),
                ordinal: 0,
                title: "第一章".into(),
            }],
        };
        let book = upsert_source_detail(&conn, "sudugu520", &detail).unwrap();
        assert_eq!(
            find_book_source(&conn, book, "sudugu520")
                .unwrap()
                .unwrap()
                .binding_state,
            "candidate"
        );
        assert_eq!(
            match_candidates(&conn, "sudugu520", "407").unwrap().len(),
            2
        );
    }

    #[test]
    fn manual_source_actions_are_transactional_and_audited() {
        let conn = Connection::open_in_memory().unwrap();
        init(&conn).unwrap();
        let item = ExternalListItem {
            external_book_id: "407".into(),
            title: "待确认".into(),
            author: "作者".into(),
            category: "分类".into(),
            status: "连载中".into(),
            latest_external_chapter_id: Some("1".into()),
            latest_chapter: "第一章".into(),
        };
        let book = upsert_source_list(&conn, "sudugu520", &item).unwrap();
        assert!(resolve_confirmed_source(&conn, book, "sudugu520", "407", "tester").unwrap());
        assert_eq!(
            find_external_book(&conn, "sudugu520", "407")
                .unwrap()
                .unwrap()
                .binding_state,
            "confirmed"
        );
        assert_eq!(
            conn.query_row(
                "SELECT COUNT(*) FROM catalog_audit_events WHERE event_type='source_confirmed'",
                [],
                |r| r.get::<_, i64>(0)
            )
            .unwrap(),
            1
        );
        assert!(reject_source(&conn, "sudugu520", "407", "tester", "not the same book").unwrap());
        assert_eq!(
            find_external_book(&conn, "sudugu520", "407")
                .unwrap()
                .unwrap()
                .binding_state,
            "rejected"
        );
    }

    #[test]
    fn adapter_parses_both_configured_hosts() {
        let html = include_str!("../fixtures/book_51.html");
        let suduguu = SuduguuAdapter
            .parse_detail(html, "51")
            .expect("suduguu detail");
        let sudugu520 = Sudugu520Adapter
            .parse_detail(&html.replace("www.suduguu.com", "www.sudugu520.com"), "51")
            .expect("sudugu520 detail");
        assert_eq!(suduguu.external_book_id, sudugu520.external_book_id);
        assert_eq!(suduguu.chapters.len(), sudugu520.chapters.len());
    }
}

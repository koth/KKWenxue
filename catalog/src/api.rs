//! 目录与正文任务 API。
//!
//! 元信息和目录引用读 SQLite，交互阅读按章代理并校验缓存。
//! 显式整本导出才通过 relay 建立 content.fetch/content.serve 任务。

use axum::{
    body::Body,
    extract::{Path, Query, State},
    http::{HeaderMap, HeaderValue, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use futures_util::StreamExt;
use rusqlite::{params, OptionalExtension};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::collections::{hash_map::DefaultHasher, HashMap, HashSet};
use std::hash::{Hash, Hasher};
use std::sync::Arc;
use tokio::sync::Mutex;

use crate::db;

#[derive(Clone)]
pub struct AppState {
    pub db: Arc<Mutex<rusqlite::Connection>>,
    pub relay_url: String,
    pub relay_token: String,
    pub relay_client: reqwest::Client,
    pub source_url: String,
    pub chapter_cache: Arc<Mutex<ChapterCache>>,
    pub chapter_fetches: Arc<Mutex<HashMap<ChapterKey, std::sync::Weak<Mutex<()>>>>>,
}

pub type ChapterKey = (i64, String, String);

#[derive(Clone)]
pub struct CachedChapter {
    pub revision: String,
    pub body: String,
    pub sha256: String,
    pub etag: String,
    pub updated_at: u64,
}

pub struct ChapterCache {
    entries: HashMap<ChapterKey, CachedChapter>,
    bytes: usize,
    max_bytes: usize,
}

impl Default for ChapterCache {
    fn default() -> Self {
        Self {
            entries: HashMap::new(),
            bytes: 0,
            max_bytes: 64 * 1024 * 1024,
        }
    }
}

impl ChapterCache {
    fn get(&mut self, key: &ChapterKey) -> Option<CachedChapter> {
        let value = self.entries.get_mut(key)?;
        if now() as u64 > value.updated_at + 21600 {
            return None;
        }
        Some(value.clone())
    }

    fn insert(&mut self, key: ChapterKey, value: CachedChapter) {
        if value.body.len() > self.max_bytes {
            return;
        }
        if let Some(previous) = self.entries.insert(key, value.clone()) {
            self.bytes = self.bytes.saturating_sub(previous.body.len());
        }
        self.bytes += value.body.len();
        while self.bytes > self.max_bytes {
            let Some(oldest) = self
                .entries
                .iter()
                .min_by_key(|(_, item)| item.updated_at)
                .map(|(key, _)| key.clone())
            else {
                break;
            };
            if let Some(evicted) = self.entries.remove(&oldest) {
                self.bytes = self.bytes.saturating_sub(evicted.body.len());
            }
        }
    }
}

type ApiResult = Result<Json<Value>, (StatusCode, String)>;

fn db_err(e: rusqlite::Error) -> (StatusCode, String) {
    eprintln!("catalog db: {e}");
    (StatusCode::INTERNAL_SERVER_ERROR, "目录数据库错误".into())
}

fn book_value(conn: &rusqlite::Connection, book: &db::BookRow) -> rusqlite::Result<Value> {
    let mut value = serde_json::to_value(book).unwrap_or(Value::Null);
    let sources = db::book_sources(conn, book.id)?;
    value["bookId"] = json!(book.id);
    value["sources"] = serde_json::to_value(&sources).unwrap_or_default();
    value["recommendedSource"] = sources
        .iter()
        .find(|source| source.is_preferred && source.binding_state == "confirmed")
        .or_else(|| {
            sources
                .iter()
                .find(|source| source.binding_state == "confirmed")
        })
        .map(|source| json!(source.source_key))
        .unwrap_or(Value::Null);
    value["matchState"] = conn
        .query_row(
            "SELECT COALESCE(match_state,'confirmed') FROM books
             WHERE COALESCE(book_id,id)=?1",
            rusqlite::params![book.id],
            |r| r.get::<_, String>(0),
        )
        .unwrap_or_else(|_| "confirmed".into())
        .into();
    Ok(value)
}

#[derive(Deserialize)]
pub struct SearchParams {
    q: Option<String>,
    limit: Option<usize>,
    cursor: Option<i64>,
}

#[derive(Deserialize)]
pub struct ListParams {
    category: Option<String>,
    status: Option<String>,
    sort: Option<String>,
    limit: Option<usize>,
    cursor: Option<i64>,
}

#[derive(Deserialize)]
struct SourceParams {
    id: i64,
    p: u32,
}

#[derive(Deserialize)]
struct SourceSelect {
    source: Option<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct SourceBindingAction {
    source_key: String,
    external_book_id: String,
    reason: Option<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct SourceSplitAction {
    source_key: String,
    external_book_id: String,
    reason: Option<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ResolveBookRequest {
    source_key: String,
    external_book_id: String,
    title: String,
    author: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdatePlanRequest {
    from_revision: String,
    target_revision: String,
    chapters: Vec<ChapterSummary>,
}

#[derive(Deserialize, Serialize, Hash)]
#[serde(rename_all = "camelCase")]
struct ChapterSummary {
    chapter_id: String,
    index: usize,
    title: String,
    body_sha256: String,
}

struct UpdateError(StatusCode, &'static str, &'static str);

impl IntoResponse for UpdateError {
    fn into_response(self) -> Response {
        (
            self.0,
            Json(json!({"stage": "plan", "code": self.1, "error": self.2})),
        )
            .into_response()
    }
}

fn digest(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

fn validate_update_request(
    book: &db::BookRow,
    request: &UpdatePlanRequest,
    expected_revision: &str,
) -> Result<(), UpdateError> {
    if !digest(&request.from_revision) {
        return Err(UpdateError(
            StatusCode::BAD_REQUEST,
            "invalid_base",
            "前置正文版本无效",
        ));
    }
    if request.target_revision != expected_revision {
        return Err(UpdateError(
            StatusCode::CONFLICT,
            "stale_target",
            "目录版本已变化，请重新检查更新",
        ));
    }
    if request.chapters.len() > 2000 {
        return Err(UpdateError(
            StatusCode::PAYLOAD_TOO_LARGE,
            "chapter_limit",
            "本地章节数量超过更新限制",
        ));
    }
    let prefix = format!("online:catalog:{}:chapter:", book.id);
    let mut ids = HashSet::new();
    if request.chapters.is_empty()
        || request.chapters.iter().enumerate().any(|(i, chapter)| {
            let id = chapter.chapter_id.strip_prefix(&prefix).unwrap_or("");
            let stable = id
                .strip_prefix("sha256:")
                .and_then(|v| v.split_once(':'))
                .is_some_and(|(hash, occurrence)| {
                    digest(hash) && occurrence.parse::<u16>().is_ok_and(|v| v > 0 && v <= 2000)
                });
            chapter.index != i
                || chapter.title.is_empty()
                || chapter.title.len() > 440
                || !digest(&chapter.body_sha256)
                || !(stable || id == i.to_string())
                || !ids.insert(&chapter.chapter_id)
        })
    {
        return Err(UpdateError(
            StatusCode::BAD_REQUEST,
            "invalid_manifest",
            "本地章节清单无效",
        ));
    }
    Ok(())
}

async fn healthz() -> Json<Value> {
    Json(json!({"ok": true}))
}

async fn stats(State(st): State<AppState>) -> ApiResult {
    let conn = st.db.lock().await;
    let s = db::stats(&conn).map_err(db_err)?;
    Ok(Json(serde_json::to_value(s).unwrap_or(Value::Null)))
}

async fn search(State(st): State<AppState>, Query(p): Query<SearchParams>) -> ApiResult {
    let q = p.q.unwrap_or_default().trim().to_string();
    if q.is_empty() {
        return Ok(Json(json!({"items": [], "nextCursor": null})));
    }
    let limit = p.limit.unwrap_or(20).clamp(1, 200);
    let conn = st.db.lock().await;
    let rows = db::search(&conn, &q, limit, p.cursor).map_err(db_err)?;
    let next = rows.last().map(|r| r.id);
    let items = rows
        .iter()
        .map(|book| book_value(&conn, book))
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(db_err)?;
    Ok(Json(json!({"items": items, "nextCursor": next})))
}

async fn list(State(st): State<AppState>, Query(p): Query<ListParams>) -> ApiResult {
    let limit = p.limit.unwrap_or(20).clamp(1, 200);
    let sort = p.sort.unwrap_or_else(|| "updated".into());
    let conn = st.db.lock().await;
    let rows = db::list_by_category(
        &conn,
        p.category.as_deref().filter(|s| !s.is_empty()),
        p.status.as_deref().filter(|s| !s.is_empty()),
        &sort,
        limit,
        p.cursor,
    )
    .map_err(db_err)?;
    let next = rows.last().map(|r| r.id);
    let items = rows
        .iter()
        .map(|book| book_value(&conn, book))
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(db_err)?;
    Ok(Json(json!({"items": items, "nextCursor": next})))
}

async fn book(
    State(st): State<AppState>,
    Path(id): Path<i64>,
    Query(selection): Query<SourceSelect>,
) -> Result<Json<Value>, CatalogError> {
    let conn = st.db.lock().await;
    if let Some(source_key) = selection.source.filter(|value| !value.trim().is_empty()) {
        let binding = db::find_external_book(&conn, &source_key, &id.to_string())
            .map_err(CatalogError::db)?
            .ok_or_else(|| {
                CatalogError::new(
                    StatusCode::NOT_FOUND,
                    "legacy",
                    "book_not_found",
                    "外站书籍不存在",
                )
            })?;
        let logical = db::get_book(&conn, binding.book_id)
            .map_err(CatalogError::db)?
            .ok_or_else(|| {
                CatalogError::new(
                    StatusCode::NOT_FOUND,
                    "legacy",
                    "book_not_found",
                    "内部书籍不存在",
                )
            })?;
        let mut value = book_value(&conn, &logical).map_err(CatalogError::db)?;
        value["sourceKey"] = json!(source_key);
        value["externalBookId"] = json!(id.to_string());
        return Ok(Json(value));
    }
    let external_exists: bool = conn
        .query_row(
            "SELECT EXISTS(SELECT 1 FROM book_sources WHERE external_book_id=?1)",
            params![id.to_string()],
            |r| r.get(0),
        )
        .map_err(CatalogError::db)?;
    if external_exists && db::get_book(&conn, id).map_err(CatalogError::db)?.is_none() {
        return Err(CatalogError::new(
            StatusCode::BAD_REQUEST,
            "legacy",
            "source_required",
            "访问外站书籍 ID 时必须指定 source",
        ));
    }
    match db::get_book(&conn, id).map_err(CatalogError::db)? {
        Some(b) => Ok(Json(book_value(&conn, &b).map_err(CatalogError::db)?)),
        None => Err(CatalogError::new(
            StatusCode::NOT_FOUND,
            "book",
            "book_not_found",
            "没有这本书",
        )),
    }
}

async fn legacy_book(
    State(st): State<AppState>,
    Path(external_id): Path<String>,
    Query(selection): Query<SourceSelect>,
) -> Result<Json<Value>, CatalogError> {
    let source_key = selection
        .source
        .filter(|value| !value.trim().is_empty())
        .ok_or_else(|| {
            CatalogError::new(
                StatusCode::BAD_REQUEST,
                "legacy",
                "source_required",
                "访问外站书籍 ID 时必须指定 source",
            )
        })?;
    let conn = st.db.lock().await;
    let binding = db::find_external_book(&conn, &source_key, &external_id)
        .map_err(CatalogError::db)?
        .ok_or_else(|| {
            CatalogError::new(
                StatusCode::NOT_FOUND,
                "legacy",
                "book_not_found",
                "外站书籍不存在",
            )
        })?;
    let book = db::get_book(&conn, binding.book_id)
        .map_err(CatalogError::db)?
        .ok_or_else(|| {
            CatalogError::new(
                StatusCode::NOT_FOUND,
                "legacy",
                "book_not_found",
                "内部书籍不存在",
            )
        })?;
    let mut value = book_value(&conn, &book).map_err(CatalogError::db)?;
    value["sourceKey"] = json!(source_key);
    value["externalBookId"] = json!(external_id);
    Ok(Json(value))
}

async fn book_sources(
    State(st): State<AppState>,
    Path(id): Path<i64>,
    Query(selection): Query<SourceSelect>,
) -> Result<Json<Value>, CatalogError> {
    let conn = st.db.lock().await;
    // A source-scoped external ID can collide with an internal catalog ID.
    // When a source is supplied, resolve the (source, external ID) pair
    // before falling back to the canonical internal ID.
    let logical_id = if let Some(source_key) = selection
        .source
        .as_deref()
        .filter(|value| !value.trim().is_empty())
    {
        if let Some(binding) = db::find_external_book(&conn, source_key, &id.to_string())
            .map_err(CatalogError::db)?
            .filter(|binding| binding.binding_state == "confirmed")
        {
            binding.book_id
        } else if db::get_book(&conn, id).map_err(CatalogError::db)?.is_some() {
            id
        } else {
            legacy_binding(&conn, id)
                .map_err(CatalogError::db)?
                .map(|binding| binding.book_id)
                .ok_or_else(|| {
                    CatalogError::new(
                        StatusCode::NOT_FOUND,
                        "sources",
                        "book_not_found",
                        "没有这本书",
                    )
                })?
        }
    } else if db::get_book(&conn, id).map_err(CatalogError::db)?.is_some() {
        id
    } else {
        legacy_binding(&conn, id)
            .map_err(CatalogError::db)?
            .map(|binding| binding.book_id)
            .ok_or_else(|| {
                CatalogError::new(
                    StatusCode::NOT_FOUND,
                    "sources",
                    "book_not_found",
                    "没有这本书",
                )
            })?
    };
    let items = db::book_sources(&conn, logical_id).map_err(CatalogError::db)?;
    let recommended = items
        .iter()
        .find(|item| item.is_preferred && item.binding_state == "confirmed")
        .or_else(|| items.iter().find(|item| item.binding_state == "confirmed"))
        .map(|item| item.source_key.clone());
    Ok(Json(json!({
        "bookId": logical_id,
        "requestedBookId": id,
        "recommendedSource": recommended,
        "items": items
    })))
}

async fn source_metrics(
    State(st): State<AppState>,
    Path(source_key): Path<String>,
) -> Result<Json<Value>, CatalogError> {
    let conn = st.db.lock().await;
    if db::get_source(&conn, &source_key)
        .map_err(CatalogError::db)?
        .is_none()
    {
        return Err(CatalogError::new(
            StatusCode::NOT_FOUND,
            "sources",
            "source_not_found",
            "来源不存在",
        ));
    }
    Ok(Json(
        serde_json::to_value(db::source_metrics(&conn, &source_key).map_err(CatalogError::db)?)
            .unwrap_or(Value::Null),
    ))
}

async fn preferred_source(
    State(st): State<AppState>,
    Path(id): Path<i64>,
    Json(action): Json<SourceSelect>,
) -> Result<Json<Value>, CatalogError> {
    let source_key = action
        .source
        .filter(|value| !value.trim().is_empty())
        .ok_or_else(|| {
            CatalogError::new(
                StatusCode::BAD_REQUEST,
                "sources",
                "source_required",
                "必须指定来源",
            )
        })?;
    let conn = st.db.lock().await;
    let logical_id = if db::get_book(&conn, id).map_err(CatalogError::db)?.is_some() {
        id
    } else {
        legacy_binding(&conn, id)
            .map_err(CatalogError::db)?
            .map(|binding| binding.book_id)
            .ok_or_else(|| {
                CatalogError::new(
                    StatusCode::NOT_FOUND,
                    "sources",
                    "book_not_found",
                    "没有这本书",
                )
            })?
    };
    if !db::set_preferred_source(&conn, logical_id, &source_key).map_err(CatalogError::db)? {
        return Err(CatalogError::new(
            StatusCode::NOT_FOUND,
            "sources",
            "source_not_bound",
            "该来源未绑定到这本书",
        ));
    }
    Ok(Json(json!({
        "bookId": logical_id,
        "requestedBookId": id,
        "sourceKey": source_key,
        "items": db::book_sources(&conn,logical_id).map_err(CatalogError::db)?
    })))
}

async fn confirm_source(
    State(st): State<AppState>,
    Path(id): Path<i64>,
    Json(action): Json<SourceBindingAction>,
) -> Result<Json<Value>, CatalogError> {
    let conn = st.db.lock().await;
    if !db::resolve_confirmed_source(
        &conn,
        id,
        &action.source_key,
        &action.external_book_id,
        "api",
    )
    .map_err(CatalogError::db)?
    {
        return Err(CatalogError::new(
            StatusCode::NOT_FOUND,
            "sources",
            "source_not_found",
            "待确认来源不存在",
        ));
    }
    Ok(Json(json!({
        "bookId": id,
        "sourceKey": action.source_key,
        "externalBookId": action.external_book_id,
        "state": "confirmed"
    })))
}

async fn reject_source(
    State(st): State<AppState>,
    Json(action): Json<SourceBindingAction>,
) -> Result<Json<Value>, CatalogError> {
    let conn = st.db.lock().await;
    if !db::reject_source(
        &conn,
        &action.source_key,
        &action.external_book_id,
        "api",
        action.reason.as_deref().unwrap_or("manual rejection"),
    )
    .map_err(CatalogError::db)?
    {
        return Err(CatalogError::new(
            StatusCode::NOT_FOUND,
            "sources",
            "source_not_found",
            "来源记录不存在",
        ));
    }
    Ok(Json(json!({
        "sourceKey": action.source_key,
        "externalBookId": action.external_book_id,
        "state": "rejected"
    })))
}

async fn split_source(
    State(st): State<AppState>,
    Json(action): Json<SourceSplitAction>,
) -> Result<Json<Value>, CatalogError> {
    let conn = st.db.lock().await;
    let Some(book_id) = db::split_source(
        &conn,
        &action.source_key,
        &action.external_book_id,
        "api",
        action.reason.as_deref().unwrap_or("manual split"),
    )
    .map_err(CatalogError::db)?
    else {
        return Err(CatalogError::new(
            StatusCode::NOT_FOUND,
            "sources",
            "source_not_found",
            "来源记录不存在",
        ));
    };
    Ok(Json(json!({
        "bookId": book_id,
        "sourceKey": action.source_key,
        "externalBookId": action.external_book_id,
        "state": "confirmed"
    })))
}

async fn resolve_book(
    State(st): State<AppState>,
    Json(request): Json<ResolveBookRequest>,
) -> Result<Json<Value>, CatalogError> {
    if request.source_key.trim().is_empty()
        || request.external_book_id.trim().is_empty()
        || request.title.trim().is_empty()
    {
        return Err(CatalogError::new(
            StatusCode::BAD_REQUEST,
            "resolve",
            "invalid_request",
            "来源、外站 ID 和书名不能为空",
        ));
    }
    let conn = st.db.lock().await;
    if let Some(binding) =
        db::find_external_book(&conn, &request.source_key, &request.external_book_id)
            .map_err(CatalogError::db)?
    {
        return Ok(Json(json!({
            "state": binding.binding_state,
            "bookId": binding.book_id,
            "source": binding
        })));
    }
    let candidates =
        db::candidate_books(&conn, &request.title, &request.author).map_err(CatalogError::db)?;
    let match_candidates =
        db::match_candidates(&conn, &request.source_key, &request.external_book_id)
            .map_err(CatalogError::db)?;
    let state = if candidates.len() == 1 {
        "candidate"
    } else {
        "ambiguous"
    };
    Ok(Json(json!({
        "state": state,
        "sourceKey": request.source_key,
        "externalBookId": request.external_book_id,
        "candidates": candidates,
        "matchCandidates": match_candidates
    })))
}

#[derive(Deserialize)]
struct ManifestParams {
    limit: Option<usize>,
    cursor: Option<i64>,
    revision: Option<String>,
    source: Option<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct PrefetchRequest {
    chapter_ids: Vec<i64>,
    source_key: Option<String>,
    priority: Option<i64>,
}

#[derive(Debug)]
struct CatalogError {
    status: StatusCode,
    stage: &'static str,
    code: &'static str,
    message: String,
}

impl CatalogError {
    fn new(
        status: StatusCode,
        stage: &'static str,
        code: &'static str,
        message: impl Into<String>,
    ) -> Self {
        Self {
            status,
            stage,
            code,
            message: message.into(),
        }
    }

    fn db(error: rusqlite::Error) -> Self {
        eprintln!("catalog db: {error}");
        Self::new(
            StatusCode::INTERNAL_SERVER_ERROR,
            "catalog",
            "database_error",
            "目录数据库错误",
        )
    }
}

impl IntoResponse for CatalogError {
    fn into_response(self) -> Response {
        (
            self.status,
            Json(json!({"stage": self.stage, "code": self.code, "error": self.message})),
        )
            .into_response()
    }
}

fn legacy_binding(
    conn: &rusqlite::Connection,
    external_book_id: i64,
) -> rusqlite::Result<Option<db::BookSourceRow>> {
    let Some(binding) = db::find_external_book_legacy(conn, &external_book_id.to_string())? else {
        return Ok(None);
    };
    if binding.binding_state != "confirmed" {
        return Ok(None);
    }
    Ok(Some(binding))
}

async fn manifest(
    State(st): State<AppState>,
    Path(id): Path<i64>,
    Query(p): Query<ManifestParams>,
    headers: HeaderMap,
) -> Result<Response, CatalogError> {
    if let Some(source_key) = p.source.as_deref().filter(|value| !value.is_empty()) {
        let legacy = {
            let conn = st.db.lock().await;
            if db::get_book(&conn, id).map_err(CatalogError::db)?.is_none() {
                db::find_external_book(&conn, source_key, &id.to_string())
                    .map_err(CatalogError::db)?
                    .filter(|binding| binding.binding_state == "confirmed")
            } else {
                None
            }
        };
        if let Some(binding) = legacy {
            return legacy_manifest(&st, id, &binding, &p, headers).await;
        }
        return source_manifest(&st, id, source_key, &p, headers).await;
    }
    let legacy = {
        let conn = st.db.lock().await;
        if db::get_book(&conn, id).map_err(CatalogError::db)?.is_none() {
            legacy_binding(&conn, id).map_err(CatalogError::db)?
        } else {
            None
        }
    };
    if let Some(binding) = legacy {
        return legacy_manifest(&st, id, &binding, &p, headers).await;
    }
    ensure_manifest(&st, id).await?;
    let conn = st.db.lock().await;
    let _book = db::get_book(&conn, id)
        .map_err(CatalogError::db)?
        .ok_or_else(|| {
            CatalogError::new(
                StatusCode::NOT_FOUND,
                "manifest",
                "book_not_found",
                "没有这本书",
            )
        })?;
    let limit = p.limit.unwrap_or(100).clamp(1, 500);
    let items = db::chapters(&conn, id, limit, p.cursor).map_err(CatalogError::db)?;
    let chapter_count = db::chapter_count(&conn, id).map_err(CatalogError::db)?;
    if chapter_count == 0 {
        return Err(CatalogError::new(
            StatusCode::SERVICE_UNAVAILABLE,
            "manifest",
            "manifest_unavailable",
            "章节目录暂不可用，请稍后重试",
        ));
    }
    let next = items
        .last()
        .filter(|row| row.chapter_index + 1 < chapter_count)
        .map(|row| row.chapter_index);
    let revision = db::manifest_revision(&conn, id).map_err(CatalogError::db)?;
    if p.revision.is_some_and(|r| r != revision) {
        return Err(CatalogError::new(
            StatusCode::CONFLICT,
            "manifest",
            "stale_revision",
            "章节目录已更新，请重新加载",
        ));
    }
    let etag = format!(
        "\"manifest:{id}:{revision}:{}:{}:{}\"",
        p.cursor.unwrap_or(-1),
        limit,
        format!("{:x}", Sha256::digest(serde_json::to_vec(&items).unwrap()))
    );
    if headers
        .get("If-None-Match")
        .and_then(|value| value.to_str().ok())
        == Some(etag.as_str())
    {
        let mut response = StatusCode::NOT_MODIFIED.into_response();
        response
            .headers_mut()
            .insert("etag", HeaderValue::from_str(&etag).unwrap());
        return Ok(response);
    }
    let mut response = Json(json!({
        "bookId": id,
        "revision": revision,
        "chapterCount": chapter_count,
        "items": items,
        "nextCursor": next
    }))
    .into_response();
    response
        .headers_mut()
        .insert("etag", HeaderValue::from_str(&etag).unwrap());
    Ok(response)
}

/// Compatibility manifest for pre-source clients.  Before multi-source
/// cataloging, the app used the source book ID (for example 203) as the
/// catalog book ID and sent chapter external IDs directly.  Keep that
/// contract working while new clients use the internal book/chapter IDs.
async fn legacy_manifest(
    st: &AppState,
    legacy_book_id: i64,
    binding: &db::BookSourceRow,
    params: &ManifestParams,
    headers: HeaderMap,
) -> Result<Response, CatalogError> {
    let conn = st.db.lock().await;
    if db::source_has_ambiguity(&conn, binding.book_id, &binding.source_key)
        .map_err(CatalogError::db)?
    {
        return Err(CatalogError::new(
            StatusCode::CONFLICT,
            "manifest",
            "chapter_ambiguous",
            "该来源章节目录存在歧义，暂不能发布",
        ));
    }
    let limit = params.limit.unwrap_or(100).clamp(1, 500);
    let source_items = db::source_chapters(
        &conn,
        binding.book_id,
        &binding.source_key,
        limit,
        params.cursor,
    )
    .map_err(CatalogError::db)?;
    let chapter_count = db::source_chapter_count(&conn, binding.book_id, &binding.source_key)
        .map_err(CatalogError::db)?;
    if chapter_count == 0 {
        return Err(CatalogError::new(
            StatusCode::SERVICE_UNAVAILABLE,
            "manifest",
            "manifest_unavailable",
            "章节目录暂不可用，请稍后重试",
        ));
    }
    let revision = db::source_manifest_revision(&conn, binding.book_id, &binding.source_key)
        .map_err(CatalogError::db)?;
    // An old client may send its pre-migration catalog-v2 revision here.  It
    // is only an identity hint for the compatibility route, not a reason to
    // reject a valid source directory.
    let items = source_items
        .into_iter()
        .map(|item| {
            let external = item
                .get("externalChapterId")
                .and_then(Value::as_str)
                .ok_or_else(|| {
                    CatalogError::new(
                        StatusCode::BAD_GATEWAY,
                        "manifest",
                        "invalid_source_id",
                        "来源章节 ID 无效",
                    )
                })?;
            let source_id = external.parse::<i64>().map_err(|_| {
                CatalogError::new(
                    StatusCode::BAD_GATEWAY,
                    "manifest",
                    "invalid_source_id",
                    "旧版客户端不支持非数字章节 ID",
                )
            })?;
            Ok(json!({
                "source_id": source_id,
                "chapter_index": item["index"],
                "title": item["title"],
                "body_sha256": item["bodySha256"]
            }))
        })
        .collect::<Result<Vec<_>, CatalogError>>()?;
    let next = items
        .last()
        .and_then(|item| item.get("chapter_index").and_then(Value::as_i64))
        .filter(|index| index + 1 < chapter_count);
    let etag = format!(
        "\"legacy-manifest:{}:{}:{}\"",
        legacy_book_id,
        revision,
        format!("{:x}", Sha256::digest(serde_json::to_vec(&items).unwrap()))
    );
    if headers
        .get("If-None-Match")
        .and_then(|value| value.to_str().ok())
        == Some(etag.as_str())
    {
        let mut response = StatusCode::NOT_MODIFIED.into_response();
        response
            .headers_mut()
            .insert("etag", HeaderValue::from_str(&etag).unwrap());
        return Ok(response);
    }
    let mut response = Json(json!({
        "bookId": legacy_book_id,
        "revision": revision,
        "chapterCount": chapter_count,
        "items": items,
        "nextCursor": next
    }))
    .into_response();
    response
        .headers_mut()
        .insert("etag", HeaderValue::from_str(&etag).unwrap());
    Ok(response)
}

async fn source_manifest(
    st: &AppState,
    id: i64,
    source_key: &str,
    params: &ManifestParams,
    headers: HeaderMap,
) -> Result<Response, CatalogError> {
    let conn = st.db.lock().await;
    let _book = db::get_book(&conn, id)
        .map_err(CatalogError::db)?
        .ok_or_else(|| {
            CatalogError::new(
                StatusCode::NOT_FOUND,
                "manifest",
                "book_not_found",
                "没有这本书",
            )
        })?;
    let source = db::find_book_source(&conn, id, source_key)
        .map_err(CatalogError::db)?
        .ok_or_else(|| {
            CatalogError::new(
                StatusCode::NOT_FOUND,
                "manifest",
                "source_not_bound",
                "该来源未绑定到这本书",
            )
        })?;
    if source.binding_state != "confirmed" {
        return Err(CatalogError::new(
            StatusCode::CONFLICT,
            "manifest",
            "source_unresolved",
            "该来源尚未完成书籍匹配确认",
        ));
    }
    if db::source_has_ambiguity(&conn, id, source_key).map_err(CatalogError::db)? {
        return Err(CatalogError::new(
            StatusCode::CONFLICT,
            "manifest",
            "chapter_ambiguous",
            "该来源章节目录存在歧义，暂不能发布",
        ));
    }
    let limit = params.limit.unwrap_or(100).clamp(1, 500);
    let items = db::source_chapters(&conn, id, source_key, limit, params.cursor)
        .map_err(CatalogError::db)?;
    let chapter_count =
        db::source_chapter_count(&conn, id, source_key).map_err(CatalogError::db)?;
    if chapter_count == 0 {
        return Err(CatalogError::new(
            StatusCode::SERVICE_UNAVAILABLE,
            "manifest",
            "manifest_unavailable",
            "该来源章节目录暂不可用，请稍后重试",
        ));
    }
    let revision = db::source_manifest_revision(&conn, id, source_key).map_err(CatalogError::db)?;
    if params.revision.as_deref().is_some_and(|r| r != revision) {
        return Err(CatalogError::new(
            StatusCode::CONFLICT,
            "manifest",
            "stale_revision",
            "该来源章节目录已更新，请重新加载",
        ));
    }
    let next = items
        .last()
        .and_then(|item| item.get("index").and_then(Value::as_i64))
        .filter(|index| index + 1 < chapter_count);
    let etag = format!(
        "\"manifest:{}:{}:{}:{}\"",
        id,
        source_key,
        revision,
        format!("{:x}", Sha256::digest(serde_json::to_vec(&items).unwrap()))
    );
    if headers
        .get("If-None-Match")
        .and_then(|value| value.to_str().ok())
        == Some(etag.as_str())
    {
        let mut response = StatusCode::NOT_MODIFIED.into_response();
        response
            .headers_mut()
            .insert("etag", HeaderValue::from_str(&etag).unwrap());
        return Ok(response);
    }
    let mut response = Json(json!({
        "bookId": id,
        "sourceKey": source.source_key,
        "externalBookId": source.external_book_id,
        "revision": revision,
        "chapterCount": chapter_count,
        "items": items,
        "nextCursor": next
    }))
    .into_response();
    response
        .headers_mut()
        .insert("etag", HeaderValue::from_str(&etag).unwrap());
    Ok(response)
}

async fn ensure_manifest(st: &AppState, id: i64) -> Result<(), CatalogError> {
    let needed = {
        let conn = st.db.lock().await;
        let book = db::get_book(&conn, id)
            .map_err(CatalogError::db)?
            .ok_or_else(|| {
                CatalogError::new(
                    StatusCode::NOT_FOUND,
                    "manifest",
                    "book_not_found",
                    "没有这本书",
                )
            })?;
        let count = db::chapter_count(&conn, id).map_err(CatalogError::db)?;
        count == 0 || (count == 999 && book.chapter_no > 999)
    };
    if !needed {
        return Ok(());
    }
    let key = (id, "manifest".to_string(), "manifest".to_string());
    let lock = {
        let mut locks = st.chapter_fetches.lock().await;
        match locks.get(&key).and_then(|l| l.upgrade()) {
            Some(l) => l,
            None => {
                let l = Arc::new(Mutex::new(()));
                locks.insert(key, Arc::downgrade(&l));
                l
            }
        }
    };
    let _guard = lock.lock().await;
    let unavailable = || {
        CatalogError::new(
            StatusCode::SERVICE_UNAVAILABLE,
            "manifest",
            "manifest_unavailable",
            "章节目录暂不可用，请稍后重试",
        )
    };
    let mut path = format!("/{id}/");
    let mut detail = None;
    let mut visited = HashSet::new();
    for _ in 0..100 {
        if !visited.insert(path.clone()) {
            return Err(unavailable());
        }
        let response = st
            .relay_client
            .get(format!("{}{}", st.source_url, path))
            .send()
            .await
            .map_err(|_| unavailable())?;
        if !response.status().is_success() {
            return Err(unavailable());
        }
        let mut stream = response.bytes_stream();
        let mut bytes = Vec::new();
        while let Some(chunk) = stream.next().await {
            let chunk = chunk.map_err(|_| unavailable())?;
            if bytes.len() + chunk.len() > 16 * 1024 * 1024 {
                return Err(unavailable());
            }
            bytes.extend_from_slice(&chunk);
        }
        let html = String::from_utf8(bytes).map_err(|_| unavailable())?;
        let page = crate::parsers::parse_detail(&html, id).ok_or_else(unavailable)?;
        match detail.as_mut() {
            Some(d) => {
                let d: &mut crate::parsers::Detail = d;
                d.chapters.extend(page.chapters);
            }
            None => detail = Some(page),
        }
        match crate::parsers::next_directory_page(&html) {
            Some(page) if page <= 100 => path = format!("/{id}/p-{page}.html"),
            Some(_) => return Err(unavailable()),
            None => {
                let conn = st.db.lock().await;
                db::upsert_detail(&conn, &detail.unwrap()).map_err(|_| unavailable())?;
                return Ok(());
            }
        }
    }
    Err(unavailable())
}

fn remote_revision(book: &db::BookRow) -> String {
    format!(
        "catalog-v1:{}:{}:{}:{}",
        book.id, book.latest_chapter_id, book.chapter_no, book.last_update_at
    )
}

async fn version(
    State(st): State<AppState>,
    Path(id): Path<i64>,
    Query(selection): Query<SourceSelect>,
) -> ApiResult {
    let conn = st.db.lock().await;
    let Some(book) = db::get_book(&conn, id).map_err(db_err)? else {
        if let Some(binding) = legacy_binding(&conn, id).map_err(db_err)? {
            let chapter_count =
                db::source_chapter_count(&conn, binding.book_id, &binding.source_key)
                    .map_err(db_err)?;
            let remote_revision =
                db::source_manifest_revision(&conn, binding.book_id, &binding.source_key)
                    .map_err(db_err)?;
            let refresh_queued =
                !db::is_source_recent_update(&conn, binding.book_source_id).map_err(db_err)?;
            let logical = db::get_book(&conn, binding.book_id)
                .map_err(db_err)?
                .ok_or((StatusCode::NOT_FOUND, "没有这本书".into()))?;
            return Ok(Json(json!({
                "bookId": id,
                "externalBookId": binding.external_book_id,
                "latestChapterId": binding.latest_external_chapter_id
                    .as_deref()
                    .and_then(|value| value.parse::<i64>().ok())
                    .unwrap_or_default(),
                "chapterCount": chapter_count,
                "remoteRevision": remote_revision,
                "lastUpdateAt": binding.last_update_at,
                "latestChapter": logical.latest_chapter,
                "refreshQueued": refresh_queued
            })));
        }
        return Err((StatusCode::NOT_FOUND, "没有这本书".into()));
    };
    if let Some(source_key) = selection
        .source
        .as_deref()
        .filter(|value| !value.is_empty())
    {
        let source = db::find_book_source(&conn, id, source_key)
            .map_err(db_err)?
            .ok_or((StatusCode::NOT_FOUND, "该来源未绑定到这本书".into()))?;
        let recent = db::is_source_recent_update(&conn, source.book_source_id).map_err(db_err)?;
        if !recent {
            db::request_source_refresh(&conn, source.book_source_id).map_err(db_err)?;
        }
        return Ok(Json(json!({
            "bookId": id,
            "sourceKey": source_key,
            "externalBookId": source.external_book_id,
            "latestChapterId": source.latest_external_chapter_id
                .as_deref()
                .and_then(|value| value.parse::<i64>().ok())
                .unwrap_or_default(),
            "chapterCount": db::source_chapter_count(&conn,id,source_key).map_err(db_err)?,
            "remoteRevision": db::source_manifest_revision(&conn,id,source_key).map_err(db_err)?,
            "lastUpdateAt": source.last_update_at,
            "latestChapter": book.latest_chapter,
            "refreshQueued": !recent
        })));
    }
    let recent = db::is_recent_update(&conn, id).map_err(db_err)?;
    if !recent {
        // A per-book version check is also a freshness hint. Queue a detail
        // fetch for the next incremental run when this book was not observed
        // in the rolling recent-updates window.
        db::request_refresh(&conn, id).map_err(db_err)?;
    }
    Ok(Json(json!({
        "bookId": book.id,
        "latestChapterId": book.latest_chapter_id,
        "chapterCount": db::chapter_count(&conn,id).map_err(db_err)?,
        "remoteRevision": db::manifest_revision(&conn,id).map_err(db_err)?,
        "lastUpdateAt": book.last_update_at,
        "latestChapter": book.latest_chapter,
        "refreshQueued": !recent
    })))
}

/// Proxy the source TXT endpoint from the public host. The Jetson cannot reach
/// the source directly, while the catalog host already has the required route.
async fn source_txt(
    State(st): State<AppState>,
    Query(p): Query<SourceParams>,
    headers: HeaderMap,
) -> Result<Response, (StatusCode, String)> {
    if st.relay_token.is_empty()
        || headers
            .get("X-Relay-Token")
            .and_then(|value| value.to_str().ok())
            != Some(st.relay_token.as_str())
    {
        return Err((StatusCode::UNAUTHORIZED, "需要有效中转凭据".into()));
    }
    if p.id <= 0 || !(1..=100).contains(&p.p) {
        return Err((StatusCode::BAD_REQUEST, "书源请求参数无效".into()));
    }
    let url = format!(
        "{}/txt/?id={}&p={}",
        st.source_url.trim_end_matches('/'),
        p.id,
        p.p
    );
    let response = st
        .relay_client
        .get(url)
        .header("User-Agent", "shanye-content/1.0")
        .header("Accept", "text/plain")
        .send()
        .await
        .map_err(|e| (StatusCode::BAD_GATEWAY, format!("书源请求失败: {e}")))?;
    if !response.status().is_success() {
        return Err((
            StatusCode::BAD_GATEWAY,
            format!("书源 HTTP {}", response.status().as_u16()),
        ));
    }
    let content_type = response
        .headers()
        .get("content-type")
        .cloned()
        .unwrap_or_else(|| axum::http::HeaderValue::from_static("text/plain; charset=utf-8"));
    let body = response
        .bytes()
        .await
        .map_err(|e| (StatusCode::BAD_GATEWAY, format!("书源响应失败: {e}")))?;
    if body.len() > 64 * 1024 * 1024 {
        return Err((StatusCode::PAYLOAD_TOO_LARGE, "书源页面过大".into()));
    }
    let mut out = Body::from(body).into_response();
    out.headers_mut().insert("content-type", content_type);
    Ok(out)
}

#[derive(Deserialize)]
struct ChapterParams {
    revision: Option<String>,
    source: Option<String>,
}

async fn chapter_body(
    State(st): State<AppState>,
    Path((id, source_id)): Path<(i64, i64)>,
    Query(params): Query<ChapterParams>,
    headers: HeaderMap,
) -> Result<Response, CatalogError> {
    if let Some(source_key) = params.source.as_deref().filter(|value| !value.is_empty()) {
        let legacy = {
            let conn = st.db.lock().await;
            if db::get_book(&conn, id).map_err(CatalogError::db)?.is_none() {
                db::find_external_book(&conn, source_key, &id.to_string())
                    .map_err(CatalogError::db)?
                    .filter(|binding| binding.binding_state == "confirmed")
            } else {
                None
            }
        };
        if let Some(binding) = legacy {
            return legacy_chapter_body(&st, id, source_id, &binding, &params, headers).await;
        }
        return source_chapter_body(&st, id, source_id, source_key, &params, headers).await;
    }
    let conn = st.db.lock().await;
    let Some(book) = db::get_book(&conn, id).map_err(CatalogError::db)? else {
        if let Some(binding) = legacy_binding(&conn, id).map_err(CatalogError::db)? {
            drop(conn);
            return legacy_chapter_body(&st, id, source_id, &binding, &params, headers).await;
        }
        return Err(CatalogError::new(
            StatusCode::NOT_FOUND,
            "chapter",
            "book_not_found",
            "没有这本书",
        ));
    };
    let known = db::has_chapter(&conn, id, source_id).map_err(CatalogError::db)?;
    let revision = db::manifest_revision(&conn, id).map_err(CatalogError::db)?;
    drop(conn);
    if params.revision.is_some_and(|r| r != revision) {
        return Err(CatalogError::new(
            StatusCode::CONFLICT,
            "chapter",
            "stale_revision",
            "章节目录已更新，请刷新目录",
        ));
    }
    if !known {
        return Err(CatalogError::new(
            StatusCode::NOT_FOUND,
            "chapter",
            "chapter_not_found",
            "章节不存在",
        ));
    }
    let cached = get_cached_chapter(&st, id, source_id, &book, revision).await?;
    let etag = cached.etag.clone();
    if headers.get("If-None-Match").and_then(|v| v.to_str().ok()) == Some(etag.as_str()) {
        let mut response = StatusCode::NOT_MODIFIED.into_response();
        response
            .headers_mut()
            .insert("etag", HeaderValue::from_str(&etag).unwrap());
        return Ok(response);
    }
    let body = cached.body.clone();
    let mut out = Json(json!({
        "bookId": id,
        "chapterId": format!("online:catalog:{id}:chapter:source:{source_id}"),
        "sourceId": source_id,
        "revision": cached.revision,
        "body": body,
        "bytes": cached.body.len(),
        "sha256": cached.sha256
    }))
    .into_response();
    out.headers_mut()
        .insert("etag", HeaderValue::from_str(&etag).unwrap());
    Ok(out)
}

async fn legacy_chapter_body(
    st: &AppState,
    legacy_book_id: i64,
    external_chapter_id: i64,
    binding: &db::BookSourceRow,
    params: &ChapterParams,
    headers: HeaderMap,
) -> Result<Response, CatalogError> {
    let external_chapter_id = external_chapter_id.to_string();
    let (title, ordinal, revision) = {
        let conn = st.db.lock().await;
        let (_, _, title, ordinal, _) = db::source_chapter_by_external_id(
            &conn,
            binding.book_id,
            &binding.source_key,
            &external_chapter_id,
        )
        .map_err(CatalogError::db)?
        .ok_or_else(|| {
            CatalogError::new(
                StatusCode::NOT_FOUND,
                "chapter",
                "chapter_not_found",
                "章节不存在",
            )
        })?;
        let revision = db::source_manifest_revision(&conn, binding.book_id, &binding.source_key)
            .map_err(CatalogError::db)?;
        (title, ordinal, revision)
    };
    let cached = get_cached_source_chapter(
        st,
        binding.book_id,
        &binding.source_key,
        binding,
        &external_chapter_id,
        &title,
        &revision,
    )
    .await?;
    if headers
        .get("If-None-Match")
        .and_then(|value| value.to_str().ok())
        == Some(cached.etag.as_str())
    {
        let mut response = StatusCode::NOT_MODIFIED.into_response();
        response
            .headers_mut()
            .insert("etag", HeaderValue::from_str(&cached.etag).unwrap());
        return Ok(response);
    }
    // Keep the revision supplied by an old client so its existing local book
    // remains readable during the one-time ID migration. New clients use the
    // source-scoped route and receive the canonical source revision.
    let client_revision = params
        .revision
        .clone()
        .unwrap_or_else(|| cached.revision.clone());
    let mut out = Json(json!({
        "bookId": legacy_book_id,
        "chapterId": format!("online:catalog:{legacy_book_id}:chapter:{external_chapter_id}"),
        "sourceId": external_chapter_id.parse::<i64>().unwrap_or_default(),
        "index": ordinal,
        "title": title,
        "revision": client_revision,
        "body": cached.body,
        "bytes": cached.body.len(),
        "sha256": cached.sha256
    }))
    .into_response();
    out.headers_mut()
        .insert("etag", HeaderValue::from_str(&cached.etag).unwrap());
    Ok(out)
}

async fn source_chapter_body(
    st: &AppState,
    id: i64,
    chapter_id: i64,
    source_key: &str,
    params: &ChapterParams,
    headers: HeaderMap,
) -> Result<Response, CatalogError> {
    let (book, binding, external_chapter_id, title, ordinal, revision) = {
        let conn = st.db.lock().await;
        let book = db::get_book(&conn, id)
            .map_err(CatalogError::db)?
            .ok_or_else(|| {
                CatalogError::new(
                    StatusCode::NOT_FOUND,
                    "chapter",
                    "book_not_found",
                    "没有这本书",
                )
            })?;
        let (binding, external, title, ordinal, _) =
            db::source_chapter(&conn, id, source_key, chapter_id)
                .map_err(CatalogError::db)?
                .ok_or_else(|| {
                    CatalogError::new(
                        StatusCode::NOT_FOUND,
                        "chapter",
                        "chapter_not_found",
                        "该来源章节不存在",
                    )
                })?;
        if binding.binding_state != "confirmed" {
            return Err(CatalogError::new(
                StatusCode::CONFLICT,
                "chapter",
                "source_unresolved",
                "该来源尚未完成书籍匹配确认",
            ));
        }
        let revision =
            db::source_manifest_revision(&conn, id, source_key).map_err(CatalogError::db)?;
        (book, binding, external, title, ordinal, revision)
    };
    if params.revision.as_deref().is_some_and(|r| r != revision) {
        return Err(CatalogError::new(
            StatusCode::CONFLICT,
            "chapter",
            "stale_revision",
            "该来源章节目录已更新，请刷新目录",
        ));
    }
    let cached = get_cached_source_chapter(
        st,
        id,
        source_key,
        &binding,
        &external_chapter_id,
        &title,
        &revision,
    )
    .await?;
    let etag = cached.etag.clone();
    if headers.get("If-None-Match").and_then(|v| v.to_str().ok()) == Some(etag.as_str()) {
        let mut response = StatusCode::NOT_MODIFIED.into_response();
        response
            .headers_mut()
            .insert("etag", HeaderValue::from_str(&etag).unwrap());
        return Ok(response);
    }
    let mut out = Json(json!({
        "bookId": id,
        "chapterId": format!("online:catalog:{id}:chapter:{chapter_id}"),
        "sourceKey": source_key,
        "externalBookId": binding.external_book_id,
        "externalChapterId": external_chapter_id,
        "index": ordinal,
        "title": title,
        "revision": cached.revision,
        "body": cached.body,
        "bytes": cached.body.len(),
        "sha256": cached.sha256
    }))
    .into_response();
    out.headers_mut()
        .insert("etag", HeaderValue::from_str(&etag).unwrap());
    let _ = book;
    Ok(out)
}

async fn get_cached_source_chapter(
    st: &AppState,
    book_id: i64,
    source_key: &str,
    binding: &db::BookSourceRow,
    external_chapter_id: &str,
    title: &str,
    revision: &str,
) -> Result<CachedChapter, CatalogError> {
    let cache_id = format!("{source_key}:{external_chapter_id}");
    let key = (book_id, cache_id, revision.to_string());
    let lock = {
        let mut locks = st.chapter_fetches.lock().await;
        locks.retain(|_, value| value.strong_count() > 0);
        match locks.get(&key).and_then(|value| value.upgrade()) {
            Some(lock) => lock,
            None => {
                let lock = Arc::new(Mutex::new(()));
                locks.insert(key.clone(), Arc::downgrade(&lock));
                lock
            }
        }
    };
    let _guard = lock.lock().await;
    if let Some(value) = st.chapter_cache.lock().await.get(&key) {
        return Ok(value);
    }
    {
        let conn = st.db.lock().await;
        let cached = conn
            .query_row(
                "SELECT body,sha256,updated_at FROM source_chapter_bodies
                 WHERE book_source_id=?1 AND external_chapter_id=?2
                   AND source_revision=?3 AND updated_at>?4",
                rusqlite::params![
                    binding.book_source_id,
                    external_chapter_id,
                    binding.source_revision,
                    now() - 21600
                ],
                |r| {
                    Ok((
                        r.get::<_, String>(0)?,
                        r.get::<_, String>(1)?,
                        r.get::<_, i64>(2)?,
                    ))
                },
            )
            .optional()
            .map_err(CatalogError::db)?;
        if let Some((body, sha, updated)) = cached {
            if format!("{:x}", Sha256::digest(body.as_bytes())) == sha {
                return Ok(CachedChapter {
                    revision: revision.to_string(),
                    body,
                    sha256: sha.clone(),
                    etag: format!("\"{source_key}:{external_chapter_id}:{sha}\""),
                    updated_at: updated as u64,
                });
            }
        }
    }
    let value = fetch_source_chapter(
        st,
        book_id,
        source_key,
        binding,
        external_chapter_id,
        title,
        revision,
    )
    .await?;
    st.chapter_cache.lock().await.insert(key, value.clone());
    Ok(value)
}

async fn fetch_source_chapter(
    st: &AppState,
    book_id: i64,
    source_key: &str,
    binding: &db::BookSourceRow,
    external_chapter_id: &str,
    title: &str,
    revision: &str,
) -> Result<CachedChapter, CatalogError> {
    let adapter = crate::sources::built_in_adapters()
        .into_iter()
        .find(|adapter| adapter.source_key() == source_key)
        .ok_or_else(|| {
            CatalogError::new(
                StatusCode::NOT_FOUND,
                "chapter",
                "source_unknown",
                "来源适配器不存在",
            )
        })?;
    let base_url = {
        let conn = st.db.lock().await;
        db::get_source(&conn, source_key)
            .map_err(CatalogError::db)?
            .map(|source| source.base_url)
            .unwrap_or_else(|| adapter.base_url().to_string())
    };
    let mut path = adapter
        .chapter_path(&binding.external_book_id, external_chapter_id, None)
        .map_err(|error| {
            CatalogError::new(
                StatusCode::BAD_REQUEST,
                "chapter",
                "invalid_source_id",
                error.message,
            )
        })?;
    let expected_prefix = format!("/{}/{external_chapter_id}-", binding.external_book_id);
    let mut seen = HashSet::new();
    let mut paragraphs = Vec::new();
    for _ in 0..50 {
        if !seen.insert(path.clone()) {
            return Err(CatalogError::new(
                StatusCode::BAD_GATEWAY,
                "chapter",
                "parse_failed",
                "章节分页重复",
            ));
        }
        let response = st
            .relay_client
            .get(format!("{}{}", base_url.trim_end_matches('/'), path))
            .header("User-Agent", "shanye-catalog/0.2")
            .send()
            .await
            .map_err(|_| {
                CatalogError::new(
                    StatusCode::BAD_GATEWAY,
                    "chapter",
                    "source_unavailable",
                    "书源暂不可用",
                )
            })?;
        let status = response.status().as_u16();
        let final_url = response.url().to_string();
        let body = response.text().await.map_err(|_| {
            CatalogError::new(
                StatusCode::BAD_GATEWAY,
                "chapter",
                "source_unavailable",
                "书源响应中断",
            )
        })?;
        adapter
            .validate_response(status, &final_url, &body)
            .map_err(|error| {
                CatalogError::new(
                    StatusCode::BAD_GATEWAY,
                    "chapter",
                    "source_rejected",
                    error.message,
                )
            })?;
        let (lines, next) = parse_chapter_page_for_prefix(&body, &expected_prefix)?;
        paragraphs.extend(lines);
        if paragraphs.iter().map(String::len).sum::<usize>() > 2 * 1024 * 1024 {
            return Err(CatalogError::new(
                StatusCode::PAYLOAD_TOO_LARGE,
                "chapter",
                "body_too_large",
                "章节正文超过 2MB",
            ));
        }
        match next {
            Some(next) => path = next,
            None => {
                path.clear();
                break;
            }
        }
    }
    if !path.is_empty() {
        return Err(CatalogError::new(
            StatusCode::BAD_GATEWAY,
            "chapter",
            "parse_failed",
            "章节分页超过限制",
        ));
    }
    let body = paragraphs.join("\n\n");
    if body.trim().is_empty() {
        return Err(CatalogError::new(
            StatusCode::BAD_GATEWAY,
            "chapter",
            "empty_body",
            "章节正文为空",
        ));
    }
    let sha256 = format!("{:x}", Sha256::digest(body.as_bytes()));
    let published_revision = {
        let conn = st.db.lock().await;
        let tx = conn.unchecked_transaction().map_err(CatalogError::db)?;
        if db::source_manifest_revision(&tx, book_id, source_key).map_err(CatalogError::db)?
            != revision
        {
            return Err(CatalogError::new(
                StatusCode::CONFLICT,
                "chapter",
                "stale_revision",
                "章节目录已更新，请刷新目录",
            ));
        }
        let old: Option<String> = tx
            .query_row(
                "SELECT body_sha256 FROM source_chapters
                 WHERE book_source_id=?1 AND external_chapter_id=?2",
                rusqlite::params![binding.book_source_id, external_chapter_id],
                |r| r.get(0),
            )
            .map_err(CatalogError::db)?;
        if old.as_ref().is_some_and(|hash| hash != &sha256) {
            tx.execute(
                "INSERT INTO source_content_generations(book_source_id,updated_at)
                 VALUES(?1,1) ON CONFLICT(book_source_id)
                 DO UPDATE SET updated_at=updated_at+1",
                rusqlite::params![binding.book_source_id],
            )
            .map_err(CatalogError::db)?;
        }
        tx.execute(
            "UPDATE source_chapters SET body_sha256=?3
             WHERE book_source_id=?1 AND external_chapter_id=?2",
            rusqlite::params![binding.book_source_id, external_chapter_id, sha256],
        )
        .map_err(CatalogError::db)?;
        tx.execute(
            "INSERT OR REPLACE INTO source_chapter_bodies
                (book_source_id,external_chapter_id,source_revision,title,body,sha256,updated_at)
             VALUES(?1,?2,?3,?4,?5,?6,?7)",
            rusqlite::params![
                binding.book_source_id,
                external_chapter_id,
                binding.source_revision,
                title,
                body,
                sha256,
                now()
            ],
        )
        .map_err(CatalogError::db)?;
        let published =
            db::source_manifest_revision(&tx, book_id, source_key).map_err(CatalogError::db)?;
        tx.commit().map_err(CatalogError::db)?;
        published
    };
    Ok(CachedChapter {
        revision: published_revision,
        body,
        sha256: sha256.clone(),
        etag: format!("\"{source_key}:{external_chapter_id}:{sha256}\""),
        updated_at: now() as u64,
    })
}

async fn get_cached_chapter(
    st: &AppState,
    id: i64,
    source_id: i64,
    book: &db::BookRow,
    revision: String,
) -> Result<CachedChapter, CatalogError> {
    let key = (id, source_id.to_string(), revision.clone());
    let lock = {
        let mut locks = st.chapter_fetches.lock().await;
        locks.retain(|_, value| value.strong_count() > 0);
        match locks.get(&key).and_then(|value| value.upgrade()) {
            Some(lock) => lock,
            None => {
                let lock = Arc::new(Mutex::new(()));
                locks.insert(key.clone(), Arc::downgrade(&lock));
                lock
            }
        }
    };
    let _guard = lock.lock().await;
    if let Some(value) = st.chapter_cache.lock().await.get(&key) {
        return Ok(value);
    }
    {
        let conn = st.db.lock().await;
        let cached=conn.query_row("SELECT body,sha256,updated_at FROM chapter_bodies WHERE book_id=?1 AND source_id=?2 AND updated_at>?3",rusqlite::params![id,source_id,now()-21600],|r|Ok((r.get::<_,String>(0)?,r.get::<_,String>(1)?,r.get::<_,i64>(2)?))).ok();
        if let Some((body, sha, updated)) = cached {
            if format!("{:x}", Sha256::digest(body.as_bytes())) == sha {
                return Ok(CachedChapter {
                    revision,
                    body,
                    sha256: sha.clone(),
                    etag: format!("\"{id}:{source_id}:{sha}\""),
                    updated_at: updated as u64,
                });
            }
        }
    }
    let value = fetch_chapter(st, id, source_id, book, revision).await?;
    st.chapter_cache.lock().await.insert(key, value.clone());
    Ok(value)
}

async fn prefetch(
    State(st): State<AppState>,
    Path(id): Path<i64>,
    Json(request): Json<PrefetchRequest>,
) -> Result<Json<Value>, CatalogError> {
    let conn = st.db.lock().await;
    let _book = db::get_book(&conn, id)
        .map_err(CatalogError::db)?
        .ok_or_else(|| {
            CatalogError::new(
                StatusCode::NOT_FOUND,
                "prefetch",
                "book_not_found",
                "没有这本书",
            )
        })?;
    let mut chapter_ids = request.chapter_ids;
    chapter_ids.sort_unstable();
    chapter_ids.dedup();
    if chapter_ids.is_empty() || chapter_ids.len() > 2000 {
        return Err(CatalogError::new(
            StatusCode::BAD_REQUEST,
            "prefetch",
            "chapter_limit",
            "预取章节数量应为 1–2000",
        ));
    }
    let source_key = request
        .source_key
        .filter(|value| !value.trim().is_empty() && value != "catalog");
    if let Some(source_key) = source_key {
        let source = db::find_book_source(&conn, id, &source_key)
            .map_err(CatalogError::db)?
            .ok_or_else(|| {
                CatalogError::new(
                    StatusCode::NOT_FOUND,
                    "prefetch",
                    "source_not_bound",
                    "该来源未绑定到这本书",
                )
            })?;
        if source.binding_state != "confirmed" {
            return Err(CatalogError::new(
                StatusCode::CONFLICT,
                "prefetch",
                "source_unresolved",
                "该来源尚未完成书籍匹配确认",
            ));
        }
        let signature = format!(
            "{id}:{source_key}:{}:{chapter_ids:?}",
            db::source_manifest_revision(&conn, id, &source_key).map_err(CatalogError::db)?
        );
        let task_id = format!("prefetch-{:x}", Sha256::digest(signature.as_bytes()));
        if let Some(existing) = db::prefetch_job(&conn, &task_id).map_err(CatalogError::db)? {
            return Ok(Json(existing));
        }
        let total = db::create_source_prefetch_job(
            &conn,
            &task_id,
            id,
            source.book_source_id,
            request.priority.unwrap_or(50).clamp(0, 100),
            &chapter_ids,
        )
        .map_err(|error| {
            if matches!(error, rusqlite::Error::QueryReturnedNoRows) {
                CatalogError::new(
                    StatusCode::NOT_FOUND,
                    "prefetch",
                    "chapter_not_found",
                    "来源章节不存在或尚未完成映射",
                )
            } else {
                CatalogError::db(error)
            }
        })?;
        drop(conn);
        return Ok(Json(json!({
            "taskId": task_id,
            "bookId": id,
            "sourceKey": source_key,
            "state": "queued",
            "total": total
        })));
    }
    for chapter_id in &chapter_ids {
        if !db::has_chapter(&conn, id, *chapter_id).map_err(CatalogError::db)? {
            return Err(CatalogError::new(
                StatusCode::NOT_FOUND,
                "prefetch",
                "chapter_not_found",
                format!("章节 {} 不存在", chapter_id),
            ));
        }
    }
    let signature = format!(
        "{id}:{}:{chapter_ids:?}",
        db::manifest_revision(&conn, id).map_err(CatalogError::db)?
    );
    let task_id = format!("prefetch-{:x}", Sha256::digest(signature.as_bytes()));
    if let Some(existing) = db::prefetch_job(&conn, &task_id).map_err(CatalogError::db)? {
        return Ok(Json(existing));
    }
    db::create_prefetch_job(
        &conn,
        &task_id,
        id,
        request.priority.unwrap_or(50).clamp(0, 100),
        &chapter_ids,
    )
    .map_err(CatalogError::db)?;
    drop(conn);
    Ok(Json(
        json!({"taskId": task_id, "bookId": id, "state": "queued", "total": chapter_ids.len()}),
    ))
}

async fn prefetch_status(
    State(st): State<AppState>,
    Path(task_id): Path<String>,
) -> Result<Json<Value>, CatalogError> {
    let conn = st.db.lock().await;
    let task = db::prefetch_job(&conn, &task_id)
        .map_err(CatalogError::db)?
        .ok_or_else(|| {
            CatalogError::new(
                StatusCode::NOT_FOUND,
                "prefetch",
                "task_not_found",
                "预取任务不存在",
            )
        })?;
    Ok(Json(task))
}

async fn retry_prefetch(
    State(st): State<AppState>,
    Path(task_id): Path<String>,
) -> Result<Json<Value>, CatalogError> {
    let conn = st.db.lock().await;
    if db::prefetch_job(&conn, &task_id)
        .map_err(CatalogError::db)?
        .is_none()
    {
        return Err(CatalogError::new(
            StatusCode::NOT_FOUND,
            "prefetch",
            "task_not_found",
            "预取任务不存在",
        ));
    }
    conn.execute(
        "UPDATE prefetch_items SET state='queued',error=NULL WHERE job_id=?1 AND state='failed'",
        rusqlite::params![task_id],
    )
    .map_err(CatalogError::db)?;
    conn.execute(
        "UPDATE source_prefetch_items SET state='queued',error=NULL
         WHERE job_id=?1 AND state='failed'",
        rusqlite::params![task_id],
    )
    .map_err(CatalogError::db)?;
    conn.execute(
        "UPDATE prefetch_jobs SET state='queued',failed=0 WHERE id=?1",
        rusqlite::params![task_id],
    )
    .map_err(CatalogError::db)?;
    Ok(Json(
        db::prefetch_job(&conn, &task_id)
            .map_err(CatalogError::db)?
            .unwrap(),
    ))
}

/// A bounded persistent worker resumes queued/running items after service restart.
pub async fn run_prefetch(st: AppState) {
    {
        let conn = st.db.lock().await;
        let _ = conn.execute(
            "UPDATE prefetch_items SET state='queued' WHERE state='running'",
            [],
        );
        let _ = conn.execute(
            "UPDATE source_prefetch_items SET state='queued' WHERE state='running'",
            [],
        );
    }
    loop {
        let source_next = {
            let conn = st.db.lock().await;
            conn.query_row(
                "SELECT i.job_id,j.book_id,i.book_source_id,i.external_chapter_id
                 FROM source_prefetch_items i
                 JOIN prefetch_jobs j ON j.id=i.job_id
                 WHERE i.state='queued'
                 ORDER BY j.priority DESC,j.created_at,i.external_chapter_id
                 LIMIT 1",
                [],
                |r| {
                    Ok((
                        r.get::<_, String>(0)?,
                        r.get::<_, i64>(1)?,
                        r.get::<_, i64>(2)?,
                        r.get::<_, String>(3)?,
                    ))
                },
            )
            .optional()
        };
        let legacy_next = {
            let conn = st.db.lock().await;
            conn.query_row(
                "SELECT i.job_id,j.book_id,i.source_id
                 FROM prefetch_items i
                 JOIN prefetch_jobs j ON j.id=i.job_id
                 WHERE i.state='queued'
                 ORDER BY j.priority DESC,j.created_at,i.source_id
                 LIMIT 1",
                [],
                |r| {
                    Ok((
                        r.get::<_, String>(0)?,
                        r.get::<_, i64>(1)?,
                        r.get::<_, i64>(2)?,
                    ))
                },
            )
            .optional()
        };
        let source_next = source_next.ok().flatten();
        let legacy_next = legacy_next.ok().flatten();
        let Some(work) = source_next
            .map(|(job, id, book_source_id, external)| PrefetchWork::Source {
                job,
                id,
                book_source_id,
                external,
            })
            .or_else(|| {
                legacy_next.map(|(job, id, source)| PrefetchWork::Legacy { job, id, source })
            })
        else {
            tokio::time::sleep(std::time::Duration::from_millis(300)).await;
            continue;
        };
        let result = async {
            match &work {
                PrefetchWork::Source {
                    job,
                    id,
                    book_source_id,
                    external,
                } => {
                    let (binding, title, revision) = {
                        let conn = st.db.lock().await;
                        db::update_source_prefetch_item(
                            &conn,
                            job,
                            *book_source_id,
                            external,
                            "running",
                            None,
                        )
                        .map_err(CatalogError::db)?;
                        let (binding, _, title) =
                            db::source_prefetch_item(&conn, job, *book_source_id, external)
                                .map_err(CatalogError::db)?
                                .ok_or_else(|| {
                                    CatalogError::new(
                                        StatusCode::NOT_FOUND,
                                        "prefetch",
                                        "chapter_not_found",
                                        "来源章节已移除",
                                    )
                                })?;
                        if binding.book_id != *id || binding.binding_state != "confirmed" {
                            return Err(CatalogError::new(
                                StatusCode::CONFLICT,
                                "prefetch",
                                "source_unresolved",
                                "来源绑定已失效",
                            ));
                        }
                        let revision =
                            db::source_manifest_revision(&conn, *id, &binding.source_key)
                                .map_err(CatalogError::db)?;
                        (binding, title, revision)
                    };
                    get_cached_source_chapter(
                        &st,
                        *id,
                        &binding.source_key,
                        &binding,
                        external,
                        &title,
                        &revision,
                    )
                    .await
                }
                PrefetchWork::Legacy { job, id, source } => {
                    let (book, revision) = {
                        let conn = st.db.lock().await;
                        db::update_prefetch_item(&conn, job, *source, "running", None)
                            .map_err(CatalogError::db)?;
                        let book = db::get_book(&conn, *id)
                            .map_err(CatalogError::db)?
                            .ok_or_else(|| {
                                CatalogError::new(
                                    StatusCode::NOT_FOUND,
                                    "prefetch",
                                    "book_not_found",
                                    "书籍不存在",
                                )
                            })?;
                        if !db::has_chapter(&conn, *id, *source).map_err(CatalogError::db)? {
                            return Err(CatalogError::new(
                                StatusCode::NOT_FOUND,
                                "prefetch",
                                "chapter_not_found",
                                "章节已移除",
                            ));
                        }
                        (
                            book,
                            db::manifest_revision(&conn, *id).map_err(CatalogError::db)?,
                        )
                    };
                    get_cached_chapter(&st, *id, *source, &book, revision).await
                }
            }
        }
        .await;
        let conn = st.db.lock().await;
        let _ = match (&work, result) {
            (
                PrefetchWork::Source {
                    job,
                    book_source_id,
                    external,
                    ..
                },
                Ok(_),
            ) => db::update_source_prefetch_item(
                &conn,
                job,
                *book_source_id,
                external,
                "completed",
                None,
            ),
            (
                PrefetchWork::Source {
                    job,
                    book_source_id,
                    external,
                    ..
                },
                Err(e),
            ) => db::update_source_prefetch_item(
                &conn,
                job,
                *book_source_id,
                external,
                "failed",
                Some(&e.message),
            ),
            (PrefetchWork::Legacy { job, source, .. }, Ok(_)) => {
                db::update_prefetch_item(&conn, job, *source, "completed", None)
            }
            (PrefetchWork::Legacy { job, source, .. }, Err(e)) => {
                db::update_prefetch_item(&conn, job, *source, "failed", Some(&e.message))
            }
        };
    }
}

enum PrefetchWork {
    Source {
        job: String,
        id: i64,
        book_source_id: i64,
        external: String,
    },
    Legacy {
        job: String,
        id: i64,
        source: i64,
    },
}

async fn fetch_chapter(
    st: &AppState,
    id: i64,
    source_id: i64,
    _book: &db::BookRow,
    revision: String,
) -> Result<CachedChapter, CatalogError> {
    let mut path = format!("/{id}/{source_id}.html");
    let mut seen = HashSet::new();
    let mut paragraphs = Vec::new();
    for _ in 0..50 {
        if !seen.insert(path.clone()) {
            return Err(CatalogError::new(
                StatusCode::BAD_GATEWAY,
                "chapter",
                "parse_failed",
                "章节分页重复",
            ));
        }
        let response = st
            .relay_client
            .get(format!("{}{}", st.source_url, path))
            .header("User-Agent", "shanye-catalog/0.1")
            .send()
            .await
            .map_err(|e| {
                eprintln!("chapter source: {e}");
                CatalogError::new(
                    StatusCode::BAD_GATEWAY,
                    "chapter",
                    "source_unavailable",
                    "书源暂不可用",
                )
            })?;
        if !response.status().is_success() {
            return Err(CatalogError::new(
                StatusCode::BAD_GATEWAY,
                "chapter",
                "source_http",
                "书源章节暂不可用",
            ));
        }
        let mut stream = response.bytes_stream();
        let mut bytes = Vec::new();
        while let Some(chunk) = stream.next().await {
            let chunk = chunk.map_err(|_| {
                CatalogError::new(
                    StatusCode::BAD_GATEWAY,
                    "chapter",
                    "source_unavailable",
                    "书源响应中断",
                )
            })?;
            if bytes.len() + chunk.len() > 4 * 1024 * 1024 {
                return Err(CatalogError::new(
                    StatusCode::PAYLOAD_TOO_LARGE,
                    "chapter",
                    "body_too_large",
                    "章节页面过大",
                ));
            }
            bytes.extend_from_slice(&chunk);
        }
        let html = String::from_utf8(bytes).map_err(|_| {
            CatalogError::new(
                StatusCode::BAD_GATEWAY,
                "chapter",
                "parse_failed",
                "章节编码无效",
            )
        })?;
        let (lines, next) = parse_chapter_page(&html, id, source_id)?;
        paragraphs.extend(lines);
        if paragraphs.iter().map(String::len).sum::<usize>() > 2 * 1024 * 1024 {
            return Err(CatalogError::new(
                StatusCode::PAYLOAD_TOO_LARGE,
                "chapter",
                "body_too_large",
                "章节正文超过 2MB",
            ));
        }
        match next {
            Some(next) => path = next,
            None => {
                path.clear();
                break;
            }
        }
    }
    if !path.is_empty() {
        return Err(CatalogError::new(
            StatusCode::BAD_GATEWAY,
            "chapter",
            "parse_failed",
            "章节分页超过限制",
        ));
    }
    let body = paragraphs.join("\n\n");
    if body.trim().is_empty() {
        return Err(CatalogError::new(
            StatusCode::BAD_GATEWAY,
            "chapter",
            "empty_body",
            "章节正文为空",
        ));
    }
    let mut hasher = Sha256::new();
    hasher.update(body.as_bytes());
    let sha256 = format!("{:x}", hasher.finalize());
    let published_revision = {
        let conn = st.db.lock().await;
        let tx = conn.unchecked_transaction().map_err(CatalogError::db)?;
        if db::manifest_revision(&tx, id).map_err(CatalogError::db)? != revision {
            return Err(CatalogError::new(
                StatusCode::CONFLICT,
                "chapter",
                "stale_revision",
                "章节目录已更新，请刷新目录",
            ));
        }
        let title: String = tx
            .query_row(
                "SELECT title FROM book_chapters WHERE book_id=?1 AND source_id=?2",
                rusqlite::params![id, source_id],
                |r| r.get(0),
            )
            .map_err(CatalogError::db)?;
        let old: Option<String> = tx
            .query_row(
                "SELECT body_sha256 FROM book_chapters WHERE book_id=?1 AND source_id=?2",
                rusqlite::params![id, source_id],
                |r| r.get(0),
            )
            .map_err(CatalogError::db)?;
        if old.as_ref().is_some_and(|hash| hash != &sha256) {
            tx.execute("INSERT INTO content_generations(book_id,updated_at) VALUES(?1,1) ON CONFLICT(book_id) DO UPDATE SET updated_at=updated_at+1",rusqlite::params![id]).map_err(CatalogError::db)?;
        }
        db::set_chapter_hash(&tx, id, source_id, &sha256).map_err(CatalogError::db)?;
        tx.execute("INSERT OR REPLACE INTO chapter_bodies(book_id,source_id,title,body,sha256,updated_at) VALUES(?1,?2,?3,?4,?5,?6)",rusqlite::params![id,source_id,title,body,sha256,now()]).map_err(CatalogError::db)?;
        let used: i64 = tx
            .query_row(
                "SELECT COALESCE(SUM(length(CAST(body AS BLOB))),0) FROM chapter_bodies",
                [],
                |r| r.get(0),
            )
            .map_err(CatalogError::db)?;
        if used > 256 * 1024 * 1024 {
            tx.execute("DELETE FROM chapter_bodies WHERE rowid IN (SELECT rowid FROM chapter_bodies ORDER BY updated_at LIMIT 100)",[]).map_err(CatalogError::db)?;
        }
        let published = db::manifest_revision(&tx, id).map_err(CatalogError::db)?;
        tx.commit().map_err(CatalogError::db)?;
        published
    };
    Ok(CachedChapter {
        revision: published_revision,
        body,
        sha256: sha256.clone(),
        etag: format!("\"{}:{}:{}\"", id, source_id, sha256),
        updated_at: now() as u64,
    })
}

fn parse_chapter_page(
    html: &str,
    book_id: i64,
    source_id: i64,
) -> Result<(Vec<String>, Option<String>), CatalogError> {
    parse_chapter_page_for_prefix(html, &format!("/{book_id}/{source_id}-"))
}

fn parse_chapter_page_for_prefix(
    html: &str,
    expected_prefix: &str,
) -> Result<(Vec<String>, Option<String>), CatalogError> {
    let doc = scraper::Html::parse_document(html);
    let container = doc
        .select(&scraper::Selector::parse("div.con").unwrap())
        .next()
        .ok_or_else(|| {
            CatalogError::new(
                StatusCode::BAD_GATEWAY,
                "chapter",
                "parse_failed",
                "章节正文无法解析",
            )
        })?;
    let lines = container
        .select(&scraper::Selector::parse("p").unwrap())
        .map(|p| p.text().collect::<String>().trim().replace('\r', ""))
        .filter(|p| !p.is_empty() && !p.starts_with("记住我们网") && !p.starts_with("記住我們網"))
        .collect::<Vec<_>>();
    let mut next = None;
    for a in doc.select(&scraper::Selector::parse(".prenext a[href]").unwrap()) {
        if a.text().collect::<String>().trim() != "下一页" {
            continue;
        }
        let href = a.value().attr("href").unwrap_or("");
        let suffix = href
            .strip_prefix(expected_prefix)
            .and_then(|s| s.strip_suffix(".html"));
        if suffix.and_then(|s| s.parse::<usize>().ok()).is_none() {
            return Err(CatalogError::new(
                StatusCode::BAD_GATEWAY,
                "chapter",
                "parse_failed",
                "章节分页地址无效",
            ));
        }
        next = Some(href.to_string());
    }
    Ok((lines, next))
}

fn now() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

fn relay_error(status: reqwest::StatusCode, body: String) -> (StatusCode, String) {
    let code = StatusCode::from_u16(status.as_u16()).unwrap_or(StatusCode::BAD_GATEWAY);
    (
        code,
        format!("relay: {}", body.chars().take(500).collect::<String>()),
    )
}

async fn relay_json(
    st: &AppState,
    method: reqwest::Method,
    path: &str,
    body: Option<Value>,
) -> Result<(reqwest::StatusCode, Value), (StatusCode, String)> {
    let url = format!("{}{}", st.relay_url, path);
    let mut req = st
        .relay_client
        .request(method, url)
        .header("X-Relay-Token", &st.relay_token);
    if let Some(body) = body {
        req = req.json(&body);
    }
    let response = req
        .send()
        .await
        .map_err(|e| (StatusCode::BAD_GATEWAY, format!("relay 请求失败: {e}")))?;
    let status = response.status();
    let raw = response
        .text()
        .await
        .map_err(|e| (StatusCode::BAD_GATEWAY, format!("relay 响应失败: {e}")))?;
    let value = serde_json::from_str(&raw).unwrap_or_else(|_| json!({"error": raw}));
    if !status.is_success() {
        return Err(relay_error(status, value.to_string()));
    }
    Ok((status, value))
}

fn task_result_ready(task: &Value) -> bool {
    task.get("state").and_then(Value::as_str) == Some("completed")
        && task.pointer("/result/filename").is_some()
        && task
            .pointer("/result/expires_at")
            .and_then(Value::as_i64)
            .unwrap_or(0)
            > now()
}

async fn create_relay_task(
    st: &AppState,
    kind: &str,
    payload: Value,
    dedupe: String,
    priority: i64,
) -> Result<Value, (StatusCode, String)> {
    let (_, value) = relay_json(
        st,
        reqwest::Method::POST,
        "/v1/tasks",
        Some(json!({
            "kind": kind, "payload": payload, "dedupe_key": dedupe,
            "priority": priority, "max_attempts": 3
        })),
    )
    .await?;
    Ok(value)
}

async fn create_content_task(
    st: &AppState,
    kind: &str,
    payload: Value,
    key: String,
) -> Result<Value, (StatusCode, String)> {
    let task = create_relay_task(st, kind, payload.clone(), key.clone(), 0).await?;
    let state = task["state"].as_str();
    if matches!(state, Some("failed" | "cancelled"))
        || (state == Some("completed")
            && (!task_result_ready(&task) || task["result"]["relay_state"] == "failed"))
    {
        // Coalesce simultaneous retries; do not spend a new worker budget on every tap.
        return create_relay_task(st, kind, payload, format!("{key}:retry:{}", now() / 60), 0)
            .await;
    }
    Ok(task)
}

async fn serve_content(st: &AppState, fetch: &Value) -> Result<Value, (StatusCode, String)> {
    let book_id = fetch["payload"]["bookId"]
        .as_i64()
        .ok_or((StatusCode::BAD_GATEWAY, "正文任务缺少书籍编号".into()))?;
    let fetch_id = fetch["id"]
        .as_str()
        .ok_or((StatusCode::BAD_GATEWAY, "正文任务缺少编号".into()))?;
    create_content_task(
        st,
        "content.serve",
        json!({"bookId": book_id, "fetchTaskId": fetch_id}),
        format!("content.serve:txt-v2:{fetch_id}"),
    )
    .await
}

fn content_state(task: &Value) -> Json<Value> {
    let stage = match task["kind"].as_str() {
        Some("content.serve") => "content.serve",
        Some("content.update") => "content.update",
        _ => "content.fetch",
    };
    // Relay errors are for operators and may contain private paths or URLs.
    let error = match task["state"].as_str() {
        Some("failed") if stage == "content.fetch" => Some("服务器未能从书源下载正文，请稍后重试"),
        Some("failed")
            if stage == "content.update"
                && task["error"]
                    .as_str()
                    .is_some_and(|v| v.starts_with("invalid_base:")) =>
        {
            Some("前置版本与服务器快照不一致，请重新检查更新")
        }
        Some("failed")
            if stage == "content.update"
                && task["error"]
                    .as_str()
                    .is_some_and(|v| v.starts_with("fetch:")) =>
        {
            Some("服务器未能从书源下载正文，请稍后重试")
        }
        Some("failed") if stage == "content.update" => Some("服务器未能生成正文更新包，请稍后重试"),
        Some("failed") => Some("服务器未能打包正文，请稍后重试"),
        Some("cancelled") => Some("正文导入任务已取消，请重新导入"),
        _ => None,
    };
    let mut out = json!({"taskId":task["id"], "state":task["state"], "kind":stage, "error":error});
    if stage == "content.update" {
        out["phase"] = task["progress"]["phase"].clone();
        out["relayState"] = task["result"]["relay_state"].clone();
        out["relayAttempts"] = task["result"]["relay_attempts"].clone();
        out["attempts"] = task["attempts"].clone();
        if task["result"]["relay_state"] == "failed" {
            out["state"] = json!("failed");
            out["error"] = json!("正文中转失败，请稍后重试");
            return Json(out);
        }
    }
    if matches!(
        task["kind"].as_str(),
        Some("content.serve" | "content.update")
    ) && task_result_ready(task)
    {
        out["downloadUrl"] = json!(format!(
            "/v1/catalog/content/{}/download",
            task["id"].as_str().unwrap_or("")
        ));
        out["sha256"] = task["result"]["sha256"].clone();
        out["bytes"] = task["result"]["bytes"].clone();
        out["expiresAt"] = task["result"]["expires_at"].clone();
        if stage == "content.update" {
            out["plan"] = task["progress"]["plan"].clone();
        }
    } else if task["state"] == "completed" && !task_result_ready(task) {
        out["state"] = json!("expired");
        out["error"] = json!("正文任务已过期，请重新导入");
    }
    Json(out)
}

async fn update_plan(
    State(st): State<AppState>,
    Path(id): Path<i64>,
    Query(selection): Query<SourceSelect>,
    Json(request): Json<UpdatePlanRequest>,
) -> Result<Response, Response> {
    let book = {
        let conn = st.db.lock().await;
        db::get_book(&conn, id)
            .map_err(|e| db_err(e).into_response())?
            .ok_or_else(|| {
                UpdateError(StatusCode::NOT_FOUND, "book_not_found", "没有这本书").into_response()
            })?
    };
    let source_key = selection.source.filter(|value| !value.is_empty());
    let expected_revision = {
        let conn = st.db.lock().await;
        match source_key.as_deref() {
            Some(source_key) => db::source_manifest_revision(&conn, id, source_key),
            None => db::manifest_revision(&conn, id),
        }
        .map_err(|e| db_err(e).into_response())?
    };
    validate_update_request(&book, &request, &expected_revision)
        .map_err(IntoResponse::into_response)?;
    if st.relay_token.trim().is_empty() {
        return Err(UpdateError(
            StatusCode::SERVICE_UNAVAILABLE,
            "unavailable",
            "正文服务尚未配置",
        )
        .into_response());
    }
    let from = request.from_revision;
    let target = request.target_revision;
    let mut plan_key = DefaultHasher::new();
    target.hash(&mut plan_key);
    request.chapters.hash(&mut plan_key);
    let payload = json!({
        "bookId": id,
        "sourceKey": source_key,
        "latestChapterId": book.latest_chapter_id,
        "fromRevision": from,
        "targetRevision": target,
        "chapters": request.chapters
    });
    let task = create_content_task(
        &st,
        "content.update",
        payload,
        format!(
            "content.update:txt-v3:{id}:{}:{from}:{:016x}",
            source_key.as_deref().unwrap_or("legacy"),
            plan_key.finish()
        ),
    )
    .await
    .map_err(|(status, _)| {
        UpdateError(
            status,
            "task_creation",
            "无法创建正文更新任务，请重新检查后重试",
        )
        .into_response()
    })?;
    let out = content_state(&task).0;
    let code = if out["downloadUrl"].is_string() {
        StatusCode::OK
    } else {
        StatusCode::ACCEPTED
    };
    Ok((code, Json(out)).into_response())
}

async fn content(
    State(st): State<AppState>,
    Path(id): Path<i64>,
    Query(selection): Query<SourceSelect>,
) -> Result<Response, (StatusCode, String)> {
    let book = {
        let conn = st.db.lock().await;
        db::get_book(&conn, id)
            .map_err(db_err)?
            .ok_or((StatusCode::NOT_FOUND, "没有这本书".into()))?
    };
    if st.relay_token.trim().is_empty() {
        return Err((StatusCode::SERVICE_UNAVAILABLE, "正文服务尚未配置".into()));
    }
    let source_key = selection.source.filter(|value| !value.is_empty());
    let latest = book.latest_chapter_id;
    let catalog_revision = if let Some(source_key) = source_key.as_deref() {
        let conn = st.db.lock().await;
        db::find_book_source(&conn, id, source_key)
            .map_err(db_err)?
            .ok_or((StatusCode::NOT_FOUND, "该来源未绑定到这本书".into()))?;
        db::source_manifest_revision(&conn, id, source_key).map_err(db_err)?
    } else {
        remote_revision(&book)
    };
    // The preheat key uses a different priority. Relay rejects collisions even
    // for the same book/version; workers still share the on-disk book cache.
    let mut task = create_content_task(&st, "content.fetch",
        json!({"bookId": id, "sourceKey": source_key, "latestChapterId": latest, "title": book.title, "author": book.author, "catalogRevision": catalog_revision}),
        format!(
            "content.fetch:txt-v4:{}:{catalog_revision}",
            source_key.as_deref().unwrap_or("legacy")
        )).await?;
    // A cached fetch is only the small manifest; the App needs the serve task's ZIP.
    if task_result_ready(&task) {
        task = serve_content(&st, &task).await?;
    }
    let out = content_state(&task);
    let code = if out.0["downloadUrl"].is_string() {
        StatusCode::OK
    } else {
        StatusCode::ACCEPTED
    };
    Ok((code, out).into_response())
}

async fn status(State(st): State<AppState>, Path(task_id): Path<String>) -> ApiResult {
    let (_, mut task) = relay_json(
        &st,
        reqwest::Method::GET,
        &format!("/v1/tasks/{task_id}"),
        None,
    )
    .await?;
    match task["kind"].as_str() {
        Some("content.fetch") if task_result_ready(&task) => {
            task = serve_content(&st, &task).await?
        }
        Some("content.fetch" | "content.serve" | "content.update") => {}
        _ => return Err((StatusCode::NOT_FOUND, "没有这个正文任务".into())),
    }
    Ok(content_state(&task))
}

async fn download(
    State(st): State<AppState>,
    Path(task_id): Path<String>,
    request_headers: HeaderMap,
) -> Result<Response, (StatusCode, String)> {
    let (_, task) = relay_json(
        &st,
        reqwest::Method::GET,
        &format!("/v1/tasks/{task_id}"),
        None,
    )
    .await?;
    if !matches!(
        task["kind"].as_str(),
        Some("content.serve" | "content.update")
    ) {
        return Err((StatusCode::NOT_FOUND, "没有这个正文包".into()));
    }
    if task["state"] == "completed" && !task_result_ready(&task) {
        return Err((StatusCode::GONE, "正文包已过期，请重新检查".into()));
    }
    if !task_result_ready(&task) {
        return Err((StatusCode::CONFLICT, "正文尚未生成".into()));
    }
    let relay_state = task
        .pointer("/result/relay_state")
        .and_then(Value::as_str)
        .unwrap_or("none");
    if relay_state == "failed" {
        return Err((StatusCode::BAD_GATEWAY, "正文中转失败，请稍后重试".into()));
    }
    if relay_state != "ready" {
        // local_available only means a LAN URL exists, not that relay has a copy.
        if relay_state == "none" {
            relay_json(
                &st,
                reqwest::Method::POST,
                &format!("/v1/tasks/{task_id}/result/request"),
                Some(json!({})),
            )
            .await?;
        }
        return Err((StatusCode::ACCEPTED, "已请求中转，稍后重试".into()));
    }
    let mut request = st
        .relay_client
        .get(format!("{}/v1/tasks/{task_id}/result", st.relay_url))
        .header("X-Relay-Token", &st.relay_token);
    for name in ["range", "if-range"] {
        if let Some(value) = request_headers.get(name) {
            request = request.header(name, value);
        }
    }
    let response = request
        .send()
        .await
        .map_err(|_| (StatusCode::BAD_GATEWAY, "导出下载暂时不可用".into()))?;
    if !response.status().is_success() {
        return Err(relay_error(
            response.status(),
            response.text().await.unwrap_or_default(),
        ));
    }
    let status = response.status();
    let headers = response.headers().clone();
    let stream = response
        .bytes_stream()
        .map(|item| item.map_err(std::io::Error::other));
    let mut out = Body::from_stream(stream).into_response();
    *out.status_mut() = status;
    for name in ["content-range", "accept-ranges", "etag"] {
        if let Some(value) = headers.get(name) {
            out.headers_mut().insert(name, value.clone());
        }
    }
    if let Some(v) = headers.get("content-type") {
        out.headers_mut().insert("content-type", v.clone());
    }
    if let Some(v) = headers.get("content-length") {
        out.headers_mut().insert("content-length", v.clone());
    }
    Ok(out)
}

pub fn router(state: AppState) -> Router {
    Router::new()
        .route("/healthz", get(healthz))
        .route("/v1/catalog/stats", get(stats))
        .route("/v1/catalog/search", get(search))
        .route("/v1/catalog/books", get(list))
        .route("/v1/catalog/legacy/books/{external_id}", get(legacy_book))
        .route("/v1/catalog/books/{id}", get(book))
        .route("/v1/catalog/books/{id}/sources", get(book_sources))
        .route(
            "/v1/catalog/sources/{source_key}/metrics",
            get(source_metrics),
        )
        .route(
            "/v1/catalog/books/{id}/sources/preferred",
            post(preferred_source),
        )
        .route(
            "/v1/catalog/books/{id}/sources/confirm",
            post(confirm_source),
        )
        .route("/v1/catalog/sources/reject", post(reject_source))
        .route("/v1/catalog/sources/split", post(split_source))
        .route("/v1/catalog/books/resolve", post(resolve_book))
        .route("/v1/catalog/books/{id}/manifest", get(manifest))
        .route(
            "/v1/catalog/books/{id}/chapters/{source_id}",
            get(chapter_body),
        )
        .route("/v1/catalog/books/{id}/prefetch", post(prefetch))
        .route("/v1/catalog/prefetch/{task_id}", get(prefetch_status))
        .route("/v1/catalog/prefetch/{task_id}/retry", post(retry_prefetch))
        .route("/v1/catalog/books/{id}/version", get(version))
        .route("/v1/catalog/source/txt", get(source_txt))
        .route("/v1/catalog/source/txt/", get(source_txt))
        .route("/v1/catalog/books/{id}/update-plan", post(update_plan))
        .route("/v1/catalog/books/{id}/content", post(content))
        .route("/v1/catalog/books/{id}/bundle", post(content))
        .route("/v1/catalog/content/{id}", get(status))
        .route("/v1/catalog/content/{id}/download", get(download))
        .with_state(state)
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::body::to_bytes;
    use std::sync::atomic::{AtomicUsize, Ordering};

    async fn start_mock(app: Router) -> AppState {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
        AppState {
            db: Arc::new(Mutex::new(rusqlite::Connection::open_in_memory().unwrap())),
            relay_url: format!("http://{addr}"),
            relay_token: "test-token".into(),
            relay_client: reqwest::Client::new(),
            source_url: "https://www.suduguu.com".into(),
            chapter_cache: Arc::new(Mutex::new(ChapterCache::default())),
            chapter_fetches: Arc::new(Mutex::new(HashMap::new())),
        }
    }

    #[tokio::test]
    async fn lan_only_metadata_requests_relay_before_returning_zip() {
        let requests = Arc::new(AtomicUsize::new(0));
        let app = Router::new()
            .route("/v1/tasks/{id}", get(|State(n): State<Arc<AtomicUsize>>| async move {
                Json(json!({
                    "id": "book-pack", "kind": "content.serve", "state": "completed",
                    "result": {"filename": "book.zip", "expires_at": now()+60, "local_available": true,
                               "direct": {"url": "http://192.0.2.10/ignored", "token": "not-for-app"},
                               "relay_state": if n.load(Ordering::SeqCst) == 0 {"none"} else {"ready"}}
                }))
            }))
            .route("/v1/tasks/{id}/result/request", post(|State(n): State<Arc<AtomicUsize>>| async move {
                n.fetch_add(1, Ordering::SeqCst);
                Json(json!({}))
            }))
            .route("/v1/tasks/{id}/result", get(|| async {
                ([(axum::http::header::CONTENT_TYPE, "application/zip")], b"zip bytes".as_slice())
            }))
            .with_state(requests.clone());
        let st = start_mock(app).await;
        let view = status(State(st.clone()), Path("book-pack".to_owned()))
            .await
            .unwrap()
            .0;
        assert!(view.get("direct").is_none());
        assert!(view.get("result").is_none());
        assert_eq!(
            view["downloadUrl"],
            "/v1/catalog/content/book-pack/download"
        );
        assert_eq!(
            download(
                State(st.clone()),
                Path("book-pack".to_owned()),
                HeaderMap::new()
            )
            .await
            .unwrap_err()
            .0,
            StatusCode::ACCEPTED,
        );
        let result = download(State(st), Path("book-pack".to_owned()), HeaderMap::new())
            .await
            .unwrap();
        assert_eq!(result.headers()["content-type"], "application/zip");
        assert_eq!(
            &to_bytes(result.into_body(), 32).await.unwrap()[..],
            b"zip bytes"
        );
        assert_eq!(requests.load(Ordering::SeqCst), 1);
    }

    #[tokio::test]
    async fn failed_or_expired_task_is_not_returned_as_the_new_import() {
        let app = Router::new().route("/v1/tasks", post(|Json(v): Json<Value>| async move {
            let key = v["dedupe_key"].as_str().unwrap();
            if key.contains(":retry:") {
                Json(json!({"state": "queued", "id": "replacement"}))
            } else if key == "expired" {
                Json(json!({"state": "completed", "id": "old", "result": {"filename": "book.zip", "expires_at": 1}}))
            } else {
                Json(json!({"state": "failed", "id": "old", "error": "reset"}))
            }
        }));
        let st = start_mock(app).await;
        for key in ["failed", "expired"] {
            let task = create_content_task(&st, "content.fetch", json!({"bookId": 1}), key.into())
                .await
                .unwrap();
            assert_eq!(task["id"], "replacement");
        }
    }

    #[tokio::test]
    async fn source_scoped_catalog_endpoints_do_not_guess_legacy_sources() {
        let st = start_mock(Router::new()).await;
        {
            let conn = st.db.lock().await;
            db::init(&conn).unwrap();
            conn.execute(
                "INSERT INTO books(id,title,author,meta_level)
                 VALUES(1,'测试书','作者',2)",
                [],
            )
            .unwrap();
            let source =
                db::ensure_book_source(&conn, 1, "suduguu", "203", "测试书", "作者", "confirmed")
                    .unwrap();
            conn.execute(
                "INSERT INTO source_chapters
                    (book_source_id,external_chapter_id,chapter_id,ordinal,source_title,
                     source_revision,last_seen_at)
                 VALUES(?1,'407',900,0,'第一章','fixture',1)",
                rusqlite::params![source],
            )
            .unwrap();
        }
        let api = start_mock(router(st.clone())).await;
        let sources = api
            .relay_client
            .get(format!("{}/v1/catalog/books/1/sources", api.relay_url))
            .send()
            .await
            .unwrap();
        assert_eq!(sources.status(), StatusCode::OK);
        assert_eq!(
            sources.json::<Value>().await.unwrap()["items"][0]["sourceKey"],
            "suduguu"
        );

        let manifest = api
            .relay_client
            .get(format!(
                "{}/v1/catalog/books/1/manifest?source=suduguu",
                api.relay_url
            ))
            .send()
            .await
            .unwrap();
        assert_eq!(manifest.status(), StatusCode::OK);
        assert_eq!(
            manifest.json::<Value>().await.unwrap()["items"][0]["externalChapterId"],
            "407"
        );
        let prefetch = api
            .relay_client
            .post(format!("{}/v1/catalog/books/1/prefetch", api.relay_url))
            .json(&json!({
                "sourceKey": "suduguu",
                "chapterIds": [900],
                "priority": 80
            }))
            .send()
            .await
            .unwrap();
        assert_eq!(prefetch.status(), StatusCode::OK);
        let prefetch_json = prefetch.json::<Value>().await.unwrap();
        assert_eq!(prefetch_json["sourceKey"], "suduguu");
        let prefetch_status = api
            .relay_client
            .get(format!(
                "{}/v1/catalog/prefetch/{}",
                api.relay_url,
                prefetch_json["taskId"].as_str().unwrap()
            ))
            .send()
            .await
            .unwrap();
        assert_eq!(
            prefetch_status.json::<Value>().await.unwrap()["items"][0]["externalChapterId"],
            "407"
        );

        let legacy = api
            .relay_client
            .get(format!("{}/v1/catalog/legacy/books/203", api.relay_url))
            .send()
            .await
            .unwrap();
        assert_eq!(legacy.status(), StatusCode::BAD_REQUEST);
        assert_eq!(
            legacy.json::<Value>().await.unwrap()["code"],
            "source_required"
        );
        let legacy_path = api
            .relay_client
            .get(format!("{}/v1/catalog/books/203", api.relay_url))
            .send()
            .await
            .unwrap();
        assert_eq!(legacy_path.status(), StatusCode::BAD_REQUEST);
        assert_eq!(
            legacy_path.json::<Value>().await.unwrap()["code"],
            "source_required"
        );
        let selected = api
            .relay_client
            .get(format!(
                "{}/v1/catalog/books/203?source=suduguu",
                api.relay_url
            ))
            .send()
            .await
            .unwrap();
        assert_eq!(selected.status(), StatusCode::OK);
        assert_eq!(
            selected.json::<Value>().await.unwrap()["externalBookId"],
            "203"
        );
    }

    #[tokio::test]
    async fn source_metrics_are_exposed_per_registered_source() {
        let st = start_mock(Router::new()).await;
        {
            let conn = st.db.lock().await;
            db::init(&conn).unwrap();
            db::record_source_error(
                &conn,
                "sudugu520",
                Some("407"),
                "source_redirect",
                "redirect to google",
            )
            .unwrap();
            db::start_source_sync_run(&conn, "incremental", "sudugu520").unwrap();
        }
        let api = start_mock(router(st)).await;
        let response = api
            .relay_client
            .get(format!(
                "{}/v1/catalog/sources/sudugu520/metrics",
                api.relay_url
            ))
            .send()
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let value = response.json::<Value>().await.unwrap();
        assert_eq!(value["sourceKey"], "sudugu520");
        assert_eq!(value["redirectFailures"], 1);
        assert_eq!(value["syncRuns"], 1);
    }

    #[tokio::test]
    async fn update_plan_http_fixture_checks_versions_and_rebuilds_expired_tasks() {
        let calls = Arc::new(AtomicUsize::new(0));
        let relay = Router::new()
            .route("/v1/tasks", post(|State(calls): State<Arc<AtomicUsize>>, Json(v): Json<Value>| async move {
                calls.fetch_add(1, Ordering::SeqCst);
                let key = v["dedupe_key"].as_str().unwrap();
                assert_eq!(v["kind"], "content.update");
                assert_eq!(v["payload"]["fromRevision"], "a".repeat(64));
                if key.contains(":retry:") {
                    Json(json!({"id":"new-job", "kind":"content.update", "state":"queued"}))
                } else {
                    Json(json!({"id":"expired-job", "kind":"content.update", "state":"completed",
                               "result":{"filename":"update.zip","expires_at":1}}))
                }
            }))
            .with_state(calls.clone());
        let st = start_mock(relay).await;
        {
            let conn = st.db.lock().await;
            db::init(&conn).unwrap();
            conn.execute(
                "INSERT INTO books (id,chapter_no,latest_chapter_id) VALUES (7,2,42)",
                [],
            )
            .unwrap();
        }
        let api = start_mock(router(st)).await;
        let url = format!("{}/v1/catalog/books/7", api.relay_url);
        let version: Value = api
            .relay_client
            .get(format!("{url}/version"))
            .send()
            .await
            .unwrap()
            .json()
            .await
            .unwrap();
        let mut body = json!({"fromRevision":"invalid","targetRevision":"old","chapters":[
            {"chapterId":"online:catalog:7:chapter:0","index":0,"title":"第一章","bodySha256":"b".repeat(64)}
        ]});
        let post = |body: &Value| {
            api.relay_client
                .post(format!("{url}/update-plan"))
                .json(body)
        };
        let invalid = post(&body).send().await.unwrap();
        assert_eq!(invalid.status(), StatusCode::BAD_REQUEST);
        assert_eq!(
            invalid.json::<Value>().await.unwrap()["code"],
            "invalid_base"
        );
        body["fromRevision"] = json!("a".repeat(64));
        assert_eq!(
            post(&body).send().await.unwrap().status(),
            StatusCode::CONFLICT
        );
        assert_eq!(calls.load(Ordering::SeqCst), 0);
        body["targetRevision"] = version["remoteRevision"].clone();
        for _ in 0..2 {
            let valid = post(&body).send().await.unwrap();
            assert_eq!(valid.status(), StatusCode::ACCEPTED);
            assert_eq!(valid.json::<Value>().await.unwrap()["taskId"], "new-job");
        }
        assert_eq!(calls.load(Ordering::SeqCst), 4);
    }

    #[tokio::test]
    async fn manifest_paginates_and_reports_missing_manifest_structurally() {
        let mut st = start_mock(Router::new()).await;
        st.source_url = st.relay_url.clone();
        {
            let conn = st.db.lock().await;
            db::init(&conn).unwrap();
            conn.execute(
                "INSERT INTO books (id,title,chapter_no,latest_chapter_id,last_update_at) VALUES (8,'测试书',2,2,'today')",
                [],
            ).unwrap();
            conn.execute("INSERT INTO book_chapters(book_id,source_id,chapter_index,title) VALUES (8,101,0,'第一章')", []).unwrap();
            conn.execute("INSERT INTO book_chapters(book_id,source_id,chapter_index,title) VALUES (8,102,1,'第二章')", []).unwrap();
            conn.execute(
                "INSERT INTO books (id,title,chapter_no) VALUES (9,'目录缺失',2)",
                [],
            )
            .unwrap();
            conn.execute(
                "INSERT INTO books (id,title,chapter_no) VALUES (10,'大目录',1200)",
                [],
            )
            .unwrap();
            for index in 0..1200 {
                conn.execute(
                    "INSERT INTO book_chapters(book_id,source_id,chapter_index,title) VALUES (10,?1,?2,?3)",
                    rusqlite::params![10000 + index, index, format!("第{}章", index + 1)],
                ).unwrap();
            }
        }
        let api = start_mock(router(st.clone())).await;
        let url = format!("{}/v1/catalog/books/8/manifest?limit=1", api.relay_url);
        let first = api.relay_client.get(&url).send().await.unwrap();
        assert_eq!(first.status(), StatusCode::OK);
        let etag = first
            .headers()
            .get("etag")
            .unwrap()
            .to_str()
            .unwrap()
            .to_owned();
        let first_json = first.json::<Value>().await.unwrap();
        assert_eq!(first_json["chapterCount"], 2);
        assert_eq!(first_json["items"].as_array().unwrap().len(), 1);
        let conditional = api
            .relay_client
            .get(&url)
            .header("If-None-Match", etag)
            .send()
            .await
            .unwrap();
        assert_eq!(conditional.status(), StatusCode::NOT_MODIFIED);
        let missing = api
            .relay_client
            .get(format!("{}/v1/catalog/books/9/manifest", api.relay_url))
            .send()
            .await
            .unwrap();
        assert_eq!(missing.status(), StatusCode::SERVICE_UNAVAILABLE);
        let error = missing.json::<Value>().await.unwrap();
        assert_eq!(error["code"], "manifest_unavailable");
        let mut cursor = None;
        let mut total = 0usize;
        loop {
            let mut page_url = format!("{}/v1/catalog/books/10/manifest?limit=500", api.relay_url);
            if let Some(value) = cursor {
                page_url.push_str(&format!("&cursor={value}"));
            }
            let page: Value = api
                .relay_client
                .get(page_url)
                .send()
                .await
                .unwrap()
                .json()
                .await
                .unwrap();
            total += page["items"].as_array().unwrap().len();
            cursor = page["nextCursor"].as_i64();
            if cursor.is_none() {
                break;
            }
        }
        assert_eq!(total, 1200);
        let created = api
            .relay_client
            .post(format!("{}/v1/catalog/books/8/prefetch", api.relay_url))
            .json(&json!({"chapterIds":[101,102],"priority":80}))
            .send()
            .await
            .unwrap();
        assert_eq!(created.status(), StatusCode::OK);
        let created_json = created.json::<Value>().await.unwrap();
        let task_id = created_json["taskId"].as_str().unwrap();
        let status = api
            .relay_client
            .get(format!("{}/v1/catalog/prefetch/{task_id}", api.relay_url))
            .send()
            .await
            .unwrap();
        assert_eq!(status.status(), StatusCode::OK);
        assert_eq!(status.json::<Value>().await.unwrap()["bookId"], 8);
    }

    #[tokio::test]
    async fn chapter_pages_are_complete_single_flight_persistent_and_conditional() {
        let count = Arc::new(AtomicUsize::new(0));
        let source=Router::new().route("/77/{page}",get(|State(count):State<Arc<AtomicUsize>>,Path(page):Path<String>|async move {
            let request=count.fetch_add(1,Ordering::SeqCst);
            tokio::time::sleep(std::time::Duration::from_millis(40)).await;
            if page=="1.html" && request>=2 {"<div class='con'><p>正文已修订。</p></div><div class='prenext'><a href='/77/1-2.html'>下一页</a></div>"}
            else if page=="1.html" {"<div class='con'><p>第一段。</p><p>记住我们网</p></div><div class='prenext'><a href='/77/1-2.html'>下一页</a></div>"}
            else {"<div class='con'><p>第二段。</p></div><div class='prenext'><a href='/77/2.html'>下一章</a></div>"}
        })).with_state(count.clone());
        let mut st = start_mock(source).await;
        st.source_url = st.relay_url.clone();
        {
            let conn = st.db.lock().await;
            db::init(&conn).unwrap();
            conn.execute("INSERT INTO books(id,title) VALUES(77,'测试')", [])
                .unwrap();
            conn.execute("INSERT INTO book_chapters(book_id,source_id,chapter_index,title) VALUES(77,1,0,'第1章')",[]).unwrap();
        }
        let book = db::get_book(&*st.db.lock().await, 77).unwrap().unwrap();
        let revision = db::manifest_revision(&*st.db.lock().await, 77).unwrap();
        let (a, b) = tokio::join!(
            get_cached_chapter(&st, 77, 1, &book, revision.clone()),
            get_cached_chapter(&st, 77, 1, &book, revision.clone())
        );
        let a = a.unwrap();
        assert_eq!(a.body, "第一段。\n\n第二段。");
        assert_eq!(a.sha256, b.unwrap().sha256);
        assert_eq!(count.load(Ordering::SeqCst), 2);
        st.chapter_cache.lock().await.entries.clear();
        let cached = get_cached_chapter(&st, 77, 1, &book, revision.clone())
            .await
            .unwrap();
        assert_eq!(count.load(Ordering::SeqCst), 2);
        assert_eq!(cached.body, a.body);
        assert_eq!(
            db::manifest_revision(&*st.db.lock().await, 77).unwrap(),
            revision
        );
        let mut headers = HeaderMap::new();
        headers.insert("If-None-Match", HeaderValue::from_str(&a.etag).unwrap());
        let response = chapter_body(
            State(st.clone()),
            Path((77, 1)),
            Query(ChapterParams {
                revision: Some(revision.clone()),
                source: None,
            }),
            headers,
        )
        .await
        .unwrap();
        assert_eq!(response.status(), StatusCode::NOT_MODIFIED);
        st.chapter_cache.lock().await.entries.clear();
        st.db
            .lock()
            .await
            .execute("DELETE FROM chapter_bodies", [])
            .unwrap();
        let changed = get_cached_chapter(&st, 77, 1, &book, revision.clone())
            .await
            .unwrap();
        assert_eq!(changed.body, "正文已修订。\n\n第二段。");
        assert_ne!(changed.sha256, a.sha256);
        assert_ne!(changed.revision, revision);
        assert_eq!(
            changed.revision,
            db::manifest_revision(&*st.db.lock().await, 77).unwrap()
        );
        let error = chapter_body(
            State(st),
            Path((77, 1)),
            Query(ChapterParams {
                revision: Some("outdated".into()),
                source: None,
            }),
            HeaderMap::new(),
        )
        .await
        .err()
        .unwrap();
        assert_eq!(error.status, StatusCode::CONFLICT);
    }

    #[tokio::test]
    async fn persistent_prefetch_resumes_after_worker_interruption_and_retries_failed_only() {
        let count = Arc::new(AtomicUsize::new(0));
        let source = Router::new()
            .route(
                "/77/{page}",
                get(|State(count): State<Arc<AtomicUsize>>| async move {
                    count.fetch_add(1, Ordering::SeqCst);
                    "<div class='con'><p>完整章节。</p></div>"
                }),
            )
            .with_state(count.clone());
        let mut st = start_mock(source).await;
        st.source_url = st.relay_url.clone();
        {
            let conn = st.db.lock().await;
            db::init(&conn).unwrap();
            conn.execute("INSERT INTO books(id,title) VALUES(77,'测试')", [])
                .unwrap();
            for i in 1..=2 {
                conn.execute("INSERT INTO book_chapters(book_id,source_id,chapter_index,title) VALUES(77,?1,?2,'标题')",rusqlite::params![i,i-1]).unwrap();
            }
            db::create_prefetch_job(&conn, "restart", 77, 20, &[1, 2]).unwrap();
            db::update_prefetch_item(&conn, "restart", 1, "running", None).unwrap();
        }
        let worker = tokio::spawn(run_prefetch(st.clone()));
        for _ in 0..100 {
            if db::prefetch_job(&*st.db.lock().await, "restart")
                .unwrap()
                .unwrap()["state"]
                == "completed"
            {
                break;
            }
            tokio::time::sleep(std::time::Duration::from_millis(20)).await;
        }
        worker.abort();
        let conn = st.db.lock().await;
        assert_eq!(
            db::prefetch_job(&conn, "restart").unwrap().unwrap()["completed"],
            2
        );
        assert_eq!(count.load(Ordering::SeqCst), 2);
        db::update_prefetch_item(&conn, "restart", 2, "failed", Some("fixture")).unwrap();
        drop(conn);
        let _ = retry_prefetch(State(st.clone()), Path("restart".into()))
            .await
            .unwrap();
        let conn = st.db.lock().await;
        let job = db::prefetch_job(&conn, "restart").unwrap().unwrap();
        assert_eq!(job["items"][0]["state"], "completed");
        assert_eq!(job["items"][1]["state"], "queued");
    }

    #[test]
    fn chapter_pagination_rejects_foreign_links_and_missing_body() {
        assert!(parse_chapter_page("<div class='con'><p>正文</p></div><div class='prenext'><a href='/88/1-2.html'>下一页</a></div>",77,1).is_err());
        assert!(parse_chapter_page("<div>改版</div>", 77, 1).is_err());
    }

    #[test]
    fn failed_jobs_report_the_content_stage_without_exposing_worker_errors() {
        for (kind, expected) in [
            ("content.fetch", "服务器未能从书源下载正文，请稍后重试"),
            ("content.serve", "服务器未能打包正文，请稍后重试"),
        ] {
            let view = content_state(&json!({
                "id": "book-job", "state": "failed", "kind": kind,
                "error": "<urlopen error [Errno 104] Connection reset by peer>",
            }))
            .0;
            assert_eq!(view["kind"], kind);
            assert_eq!(view["error"], expected);
            assert!(view.get("downloadUrl").is_none());
        }
    }
}

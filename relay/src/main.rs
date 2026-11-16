use axum::{
    body::Body,
    extract::{DefaultBodyLimit, Path, Query, State},
    http::{header, HeaderMap, HeaderValue, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use futures_util::StreamExt;
use rusqlite::{params, Connection, OptionalExtension};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    env,
    path::PathBuf,
    sync::{Arc, Mutex},
    time::{SystemTime, UNIX_EPOCH},
};
use tokio::{
    fs,
    io::AsyncWriteExt,
    time::{sleep, Duration},
};
use tokio_util::io::ReaderStream;
use uuid::Uuid;
mod delivery;
use delivery::{
    claim_transfer, complete_task, request_transfer, transfer_fail, transfer_heartbeat,
    DirectResult,
};

#[derive(Clone)]
struct AppState {
    db: Arc<Mutex<Connection>>,
    token: String,
    temp_dir: PathBuf,
    max_result_bytes: u64,
    result_ttl_secs: i64,
}

#[derive(Debug)]
struct ApiError(StatusCode, String);
impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        (self.0, Json(json!({"error": {"message": self.1}}))).into_response()
    }
}
type ApiResult<T> = Result<Json<T>, ApiError>;
fn db_error(e: rusqlite::Error) -> ApiError {
    eprintln!("relay database: {e}");
    ApiError(
        StatusCode::INTERNAL_SERVER_ERROR,
        "任务数据库操作失败".into(),
    )
}
fn io_error(e: std::io::Error) -> ApiError {
    eprintln!("relay storage: {e}");
    ApiError(StatusCode::INTERNAL_SERVER_ERROR, "结果文件操作失败".into())
}

#[derive(Debug, Deserialize)]
struct NewTask {
    kind: String,
    payload: Value,
    metadata: Option<Value>,
    dedupe_key: Option<String>,
    priority: Option<i64>,
    max_attempts: Option<i64>,
}
#[derive(Debug, Deserialize)]
struct ClaimRequest {
    worker_id: String,
    kinds: Option<Vec<String>>,
    lease_seconds: Option<i64>,
    /// 无任务时最多挂起多少秒（0–25），到点返回空任务。
    wait_seconds: Option<i64>,
}
#[derive(Debug, Deserialize)]
struct HeartbeatRequest {
    worker_id: String,
    lease_token: String,
    lease_seconds: Option<i64>,
    progress: Option<Value>,
}
#[derive(Debug, Deserialize)]
struct FailRequest {
    worker_id: String,
    lease_token: String,
    error: String,
    retryable: Option<bool>,
}
#[derive(Debug, Serialize)]
struct TaskResponse {
    id: String,
    kind: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    payload: Option<Value>,
    metadata: Value,
    created_at: i64,
    updated_at: i64,
    state: String,
    attempts: i64,
    max_attempts: i64,
    worker_id: Option<String>,
    lease_until: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    lease_token: Option<String>,
    progress: Value,
    result: Option<ResultInfo>,
    error: Option<String>,
}
#[derive(Debug, Serialize)]
struct ResultInfo {
    filename: String,
    content_type: String,
    sha256: String,
    bytes: i64,
    expires_at: i64,
    download_path: String,
    local_available: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    direct: Option<DirectResult>,
    relay_state: String,
    relay_error: Option<String>,
}
#[derive(Debug, Serialize)]
struct ClaimResponse {
    task: Option<TaskResponse>,
}

fn now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("clock before unix epoch")
        .as_secs() as i64
}
fn env_u64(name: &str, default: u64) -> u64 {
    env::var(name)
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(default)
}
fn auth(headers: &HeaderMap, state: &AppState) -> Result<(), ApiError> {
    if headers.get("x-relay-token").and_then(|v| v.to_str().ok()) == Some(state.token.as_str()) {
        Ok(())
    } else {
        Err(ApiError(
            StatusCode::UNAUTHORIZED,
            "需要有效的 relay token".into(),
        ))
    }
}
fn sanitize_filename(raw: Option<&str>) -> Result<String, ApiError> {
    let name = raw.unwrap_or("result.bin").trim();
    if name.is_empty()
        || name == "."
        || name == ".."
        || name.contains(['/', '\\'])
        || name.chars().any(|c| c.is_control())
        || name.len() > 180
    {
        return Err(ApiError(
            StatusCode::BAD_REQUEST,
            "结果文件名无效或超过 180 字节".into(),
        ));
    }
    Ok(name.to_owned())
}
fn lease_seconds(value: Option<i64>) -> i64 {
    value.unwrap_or(300).clamp(30, 3600)
}

fn init_db(conn: &Connection) -> rusqlite::Result<()> {
    conn.pragma_update(None, "journal_mode", "WAL")?;
    conn.busy_timeout(std::time::Duration::from_secs(10))?;
    conn.execute_batch(
        "CREATE TABLE IF NOT EXISTS tasks (
            id TEXT PRIMARY KEY, kind TEXT NOT NULL, payload_json TEXT NOT NULL,
            dedupe_key TEXT UNIQUE, priority INTEGER NOT NULL DEFAULT 0,
            max_attempts INTEGER NOT NULL DEFAULT 3, attempts INTEGER NOT NULL DEFAULT 0,
            state TEXT NOT NULL, worker_id TEXT, lease_until INTEGER, lease_token TEXT,
            progress_json TEXT NOT NULL DEFAULT '{}', result_filename TEXT, result_path TEXT,
            result_sha256 TEXT, result_bytes INTEGER, result_content_type TEXT,
            result_expires_at INTEGER, error TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS tasks_queue ON tasks(state, priority DESC, created_at);
        CREATE INDEX IF NOT EXISTS tasks_lease ON tasks(state, lease_until);"
    )?;
    let has_metadata = conn
        .prepare("PRAGMA table_info(tasks)")?
        .query_map([], |r| r.get::<_, String>(1))?
        .collect::<rusqlite::Result<Vec<_>>>()?
        .iter()
        .any(|c| c == "metadata_json");
    if !has_metadata {
        conn.execute(
            "ALTER TABLE tasks ADD COLUMN metadata_json TEXT NOT NULL DEFAULT '{}'",
            [],
        )?;
    }
    delivery::migrate(conn)?;
    Ok(())
}
fn decode_task(r: &rusqlite::Row<'_>, include_payload: bool) -> rusqlite::Result<TaskResponse> {
    let id: String = r.get("id")?;
    let filename: Option<String> = r.get("result_filename")?;
    let expires: Option<i64> = r.get("result_expires_at")?;
    let direct: Option<DirectResult> = r
        .get::<_, Option<String>>("result_direct_json")?
        .and_then(|v| serde_json::from_str(&v).ok());
    let result = if let (Some(filename), Some(expires_at)) = (filename, expires) {
        Some(ResultInfo {
            filename,
            content_type: r.get("result_content_type")?,
            sha256: r.get("result_sha256")?,
            bytes: r.get("result_bytes")?,
            expires_at,
            download_path: format!("/v1/tasks/{id}/result"),
            local_available: direct.is_some(),
            direct: if include_payload { direct } else { None },
            relay_state: if r.get::<_, Option<String>>("result_path")?.is_some() {
                "ready".into()
            } else {
                r.get("transfer_state")?
            },
            relay_error: r.get("transfer_error")?,
        })
    } else {
        None
    };
    Ok(TaskResponse {
        id: r.get("id")?,
        kind: r.get("kind")?,
        payload: if include_payload {
            Some(serde_json::from_str(&r.get::<_, String>("payload_json")?).unwrap_or(Value::Null))
        } else {
            None
        },
        metadata: serde_json::from_str(&r.get::<_, String>("metadata_json")?)
            .unwrap_or(Value::Null),
        created_at: r.get("created_at")?,
        updated_at: r.get("updated_at")?,
        state: r.get("state")?,
        attempts: r.get("attempts")?,
        max_attempts: r.get("max_attempts")?,
        worker_id: r.get("worker_id")?,
        lease_until: r.get("lease_until")?,
        lease_token: None,
        progress: serde_json::from_str(&r.get::<_, String>("progress_json")?)
            .unwrap_or(Value::Null),
        result,
        error: r.get("error")?,
    })
}
fn row_response(conn: &Connection, id: &str) -> Result<TaskResponse, ApiError> {
    conn.query_row("SELECT * FROM tasks WHERE id=?", [id], |r| {
        decode_task(r, true)
    })
    .optional()
    .map_err(db_error)?
    .ok_or_else(|| ApiError(StatusCode::NOT_FOUND, "任务不存在".into()))
}
fn summary(mut task: TaskResponse) -> TaskResponse {
    task.payload = None;
    if let Some(info) = task.result.as_mut() {
        info.direct = None;
    }
    task
}

#[derive(Debug, Deserialize)]
struct ListTasks {
    kind: Option<String>,
    state: Option<String>,
    before: Option<i64>,
    limit: Option<usize>,
}
async fn list_tasks(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<ListTasks>,
) -> ApiResult<Value> {
    auth(&headers, &state)?;
    let limit = query.limit.unwrap_or(30).clamp(1, 100);
    let db = state.db.lock().unwrap();
    // Deliberately omit payload_json: chapter text and other large inputs belong in task detail only.
    let mut stmt = db.prepare("SELECT rowid,id,kind,metadata_json,created_at,updated_at,state,attempts,max_attempts,
        worker_id,lease_until,progress_json,result_filename,result_content_type,result_sha256,result_bytes,
        result_expires_at,result_path,result_direct_json,transfer_state,transfer_error,error FROM tasks WHERE (? IS NULL OR kind=?) AND (? IS NULL OR state=?)
        AND (? IS NULL OR rowid<?) ORDER BY rowid DESC LIMIT ?").map_err(db_error)?;
    let rows = stmt
        .query_map(
            params![
                query.kind,
                query.kind,
                query.state,
                query.state,
                query.before,
                query.before,
                (limit + 1) as i64
            ],
            |r| Ok((r.get::<_, i64>("rowid")?, decode_task(r, false)?)),
        )
        .map_err(db_error)?;
    let mut rows = rows
        .collect::<rusqlite::Result<Vec<_>>>()
        .map_err(db_error)?;
    let has_more = rows.len() > limit;
    rows.truncate(limit);
    let next_cursor = if has_more {
        rows.last().map(|r| r.0)
    } else {
        None
    };
    Ok(Json(
        json!({"items":rows.into_iter().map(|r|r.1).collect::<Vec<_>>(),"next_cursor":next_cursor}),
    ))
}

async fn health(State(state): State<AppState>, headers: HeaderMap) -> ApiResult<Value> {
    auth(&headers, &state)?;
    let db = state.db.lock().unwrap();
    let tasks: i64 = db
        .query_row("SELECT count(*) FROM tasks", [], |r| r.get(0))
        .map_err(db_error)?;
    Ok(Json(json!({"service":"shanye-task-relay", "tasks":tasks})))
}
async fn create_task(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(body): Json<NewTask>,
) -> ApiResult<TaskResponse> {
    auth(&headers, &state)?;
    if body.kind.trim().is_empty()
        || body.kind.len() > 80
        || body.kind.chars().any(char::is_control)
    {
        return Err(ApiError(StatusCode::BAD_REQUEST, "任务 kind 无效".into()));
    }
    if body
        .dedupe_key
        .as_ref()
        .is_some_and(|v| v.is_empty() || v.len() > 200)
    {
        return Err(ApiError(
            StatusCode::BAD_REQUEST,
            "dedupe_key 需要 1–200 字节".into(),
        ));
    }
    let max_attempts = body.max_attempts.unwrap_or(3).clamp(1, 20);
    let priority = body.priority.unwrap_or(0);
    let id = Uuid::new_v4().to_string();
    let payload = body.payload.to_string();
    let metadata_value = body.metadata.unwrap_or_else(|| json!({}));
    let metadata = metadata_value.to_string();
    if metadata.len() > 8192 {
        return Err(ApiError(
            StatusCode::BAD_REQUEST,
            "metadata 超过 8KiB".into(),
        ));
    }
    let db = state.db.lock().unwrap();
    let inserted = db.execute(
        "INSERT INTO tasks(id,kind,payload_json,dedupe_key,priority,max_attempts,state,created_at,updated_at,metadata_json)
         VALUES (?,?,?,?,?,?, 'queued',?,?,?) ON CONFLICT(dedupe_key) DO NOTHING",
        params![id, body.kind.trim(), payload, body.dedupe_key, priority, max_attempts, now(), now(), metadata],
    ).map_err(db_error)?;
    if inserted == 0 {
        let (old_id, old_payload, old_priority): (String, String, i64) = db
            .query_row(
                "SELECT id,payload_json,priority FROM tasks WHERE dedupe_key=?",
                [body.dedupe_key.as_deref().unwrap_or("")],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
            )
            .map_err(db_error)?;
        let previous = row_response(&db, &old_id)?;
        if previous.kind != body.kind.trim()
            || old_payload != payload
            || old_priority != priority
            || previous.max_attempts != max_attempts
            || previous.metadata != metadata_value
        {
            return Err(ApiError(
                StatusCode::CONFLICT,
                "幂等键已用于不同任务".into(),
            ));
        }
        return Ok(Json(previous));
    }
    Ok(Json(row_response(&db, &id)?))
}
async fn claim_task(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(body): Json<ClaimRequest>,
) -> ApiResult<ClaimResponse> {
    auth(&headers, &state)?;
    if body.worker_id.trim().is_empty() || body.worker_id.len() > 120 {
        return Err(ApiError(StatusCode::BAD_REQUEST, "worker_id 无效".into()));
    }
    let kinds = body.kinds.clone().unwrap_or_default();
    if kinds.len() > 100 || kinds.iter().any(|k| k.is_empty() || k.len() > 80) {
        return Err(ApiError(
            StatusCode::BAD_REQUEST,
            "kinds 需要不超过 100 个有效类型".into(),
        ));
    }
    let wait = body.wait_seconds.unwrap_or(0).clamp(0, 25);
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(wait as u64);
    loop {
        if let Some((id, lease_token)) = try_claim(&state, &body, &kinds)? {
            let db = state.db.lock().unwrap();
            let mut task = row_response(&db, &id)?;
            task.lease_token = Some(lease_token);
            return Ok(Json(ClaimResponse { task: Some(task) }));
        }
        if wait == 0 || std::time::Instant::now() >= deadline {
            return Ok(Json(ClaimResponse { task: None }));
        }
        // 无任务时短暂挂起，避免 worker 高频空转。
        tokio::time::sleep(std::time::Duration::from_millis(200)).await;
    }
}

/// 单次尝试领取：回收过期租约 + 选一条 queued 任务并加锁。无任务返回 None。
fn try_claim(
    state: &AppState,
    body: &ClaimRequest,
    kinds: &[String],
) -> Result<Option<(String, String)>, ApiError> {
    let db = state.db.lock().unwrap();
    let tx = db.unchecked_transaction().map_err(db_error)?;
    let current = now();
    tx.execute(
        "UPDATE tasks SET state=CASE WHEN attempts>=max_attempts THEN 'failed' ELSE 'queued' END,
         worker_id=NULL,lease_until=NULL,lease_token=NULL,error='worker lease expired',updated_at=?
         WHERE state='running' AND lease_until<=?",
        params![current, current],
    )
    .map_err(db_error)?;
    let mut sql = "SELECT id FROM tasks WHERE state='queued' AND attempts<max_attempts".to_owned();
    if !kinds.is_empty() {
        sql.push_str(&format!(
            " AND kind IN ({})",
            vec!["?"; kinds.len()].join(",")
        ));
    }
    sql.push_str(" ORDER BY priority DESC,created_at ASC,rowid ASC LIMIT 1");
    let selected: Option<String> = tx
        .query_row(&sql, rusqlite::params_from_iter(kinds), |r| r.get(0))
        .optional()
        .map_err(db_error)?;
    let Some(id) = selected else {
        tx.commit().map_err(db_error)?;
        return Ok(None);
    };
    let lease_token = Uuid::new_v4().to_string();
    tx.execute(
        "UPDATE tasks SET state='running',worker_id=?,lease_until=?,lease_token=?,attempts=attempts+1,
         progress_json='{}',error=NULL,updated_at=? WHERE id=?",
        params![body.worker_id.trim(), current+lease_seconds(body.lease_seconds), lease_token, current, id],
    ).map_err(db_error)?;
    tx.commit().map_err(db_error)?;
    Ok(Some((id, lease_token)))
}
async fn heartbeat(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
    Json(body): Json<HeartbeatRequest>,
) -> ApiResult<TaskResponse> {
    auth(&headers, &state)?;
    let db = state.db.lock().unwrap();
    let current = now();
    let changed = db
        .execute(
            "UPDATE tasks SET lease_until=?,progress_json=coalesce(?,progress_json),updated_at=?
         WHERE id=? AND state='running' AND worker_id=? AND lease_token=? AND lease_until>?",
            params![
                current + lease_seconds(body.lease_seconds),
                body.progress.map(|v| v.to_string()),
                current,
                id,
                body.worker_id,
                body.lease_token,
                current
            ],
        )
        .map_err(db_error)?;
    if changed == 0 {
        return Err(ApiError(StatusCode::CONFLICT, "任务租约已失效".into()));
    }
    Ok(Json(summary(row_response(&db, &id)?)))
}
async fn fail_task(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
    Json(body): Json<FailRequest>,
) -> ApiResult<TaskResponse> {
    auth(&headers, &state)?;
    let db = state.db.lock().unwrap();
    let changed = db.execute(
        "UPDATE tasks SET state=CASE WHEN ? AND attempts<max_attempts THEN 'queued' ELSE 'failed' END,
         worker_id=NULL,lease_until=NULL,lease_token=NULL,error=?,updated_at=?
         WHERE id=? AND state='running' AND worker_id=? AND lease_token=? AND lease_until>?",
        params![body.retryable.unwrap_or(true), body.error.chars().take(4000).collect::<String>(), now(),
                id, body.worker_id, body.lease_token, now()],
    ).map_err(db_error)?;
    if changed == 0 {
        return Err(ApiError(StatusCode::CONFLICT, "任务租约已失效".into()));
    }
    Ok(Json(summary(row_response(&db, &id)?)))
}
fn require_owner(
    db: &Connection,
    id: &str,
    worker_id: &str,
    lease_token: &str,
) -> Result<(), ApiError> {
    let active: bool = db.query_row(
        "SELECT EXISTS(SELECT 1 FROM tasks WHERE id=? AND state='running' AND worker_id=? AND lease_token=? AND lease_until>?)",
        params![id, worker_id, lease_token, now()], |r| r.get(0),
    ).map_err(db_error)?;
    if !active {
        return Err(ApiError(StatusCode::CONFLICT, "任务租约已失效".into()));
    }
    Ok(())
}
async fn upload_result(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
    body: Body,
) -> ApiResult<TaskResponse> {
    auth(&headers, &state)?;
    let worker_id = headers
        .get("x-worker-id")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    let lease_token = headers
        .get("x-lease-token")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    let filename = sanitize_filename(headers.get("x-filename").and_then(|v| v.to_str().ok()))?;
    let expected_sha = headers
        .get("x-sha256")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    let expected_bytes = headers
        .get(header::CONTENT_LENGTH)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.parse::<u64>().ok());
    if expected_sha.len() != 64
        || !expected_sha.bytes().all(|c| c.is_ascii_hexdigit())
        || expected_bytes.is_none()
    {
        return Err(ApiError(
            StatusCode::BAD_REQUEST,
            "需要 X-SHA256 与 Content-Length".into(),
        ));
    }
    if expected_bytes.unwrap() > state.max_result_bytes {
        return Err(ApiError(
            StatusCode::PAYLOAD_TOO_LARGE,
            "结果文件超过大小限制".into(),
        ));
    }
    let content_type = headers
        .get(header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("application/octet-stream");
    {
        delivery::require_upload_owner(
            &state.db.lock().unwrap(),
            &id,
            worker_id,
            lease_token,
            &filename,
            expected_sha,
            expected_bytes.unwrap() as i64,
        )?;
    }
    let task_dir = state.temp_dir.join(&id);
    fs::create_dir_all(&task_dir).await.map_err(io_error)?;
    // Tempfile removes incomplete uploads on error, disconnect or cancellation.
    let pending = tempfile::Builder::new()
        .suffix(".part")
        .tempfile_in(&task_dir)
        .map_err(io_error)?;
    let mut file = fs::File::from_std(pending.reopen().map_err(io_error)?);
    let mut stream = body.into_data_stream();
    let mut hasher = Sha256::new();
    let mut bytes = 0u64;
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|_| ApiError(StatusCode::BAD_REQUEST, "上传中断".into()))?;
        bytes = bytes.saturating_add(chunk.len() as u64);
        if bytes > state.max_result_bytes {
            return Err(ApiError(
                StatusCode::PAYLOAD_TOO_LARGE,
                "结果文件超过大小限制".into(),
            ));
        }
        hasher.update(&chunk);
        file.write_all(&chunk).await.map_err(io_error)?;
    }
    file.sync_all().await.map_err(io_error)?;
    drop(file);
    let sha = hex::encode(hasher.finalize());
    if expected_bytes != Some(bytes) || !expected_sha.eq_ignore_ascii_case(&sha) {
        return Err(ApiError(
            StatusCode::BAD_REQUEST,
            "结果文件长度或 SHA-256 不匹配".into(),
        ));
    }
    let target = task_dir.join(format!("{lease_token}.result"));
    let db = state.db.lock().unwrap();
    delivery::require_upload_owner(
        &db,
        &id,
        worker_id,
        lease_token,
        &filename,
        &sha,
        bytes as i64,
    )?;
    std::fs::rename(pending.path(), &target).map_err(io_error)?;
    let updated = db.execute(
        "UPDATE tasks SET state='completed',worker_id=NULL,lease_until=NULL,lease_token=NULL,
         result_filename=?,result_path=?,result_sha256=?,result_bytes=?,result_content_type=?,result_expires_at=?,updated_at=?,
         transfer_state='ready',transfer_lease_token=NULL,transfer_lease_until=NULL,transfer_error=NULL
         WHERE id=?",
        params![filename, target.to_string_lossy(), sha, bytes as i64, content_type, now()+state.result_ttl_secs,
                now(), id],
    );
    if let Err(e) = updated {
        let _ = std::fs::remove_file(&target);
        return Err(db_error(e));
    }
    Ok(Json(summary(row_response(&db, &id)?)))
}
async fn get_task(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> ApiResult<TaskResponse> {
    auth(&headers, &state)?;
    Ok(Json(row_response(&state.db.lock().unwrap(), &id)?))
}
async fn get_task_status(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> ApiResult<TaskResponse> {
    auth(&headers, &state)?;
    Ok(Json(summary(row_response(&state.db.lock().unwrap(), &id)?)))
}
async fn download_result(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> Result<Response, ApiError> {
    auth(&headers, &state)?;
    let (path, info) = {
        let db = state.db.lock().unwrap();
        let task = row_response(&db, &id)?;
        let info = task
            .result
            .ok_or_else(|| ApiError(StatusCode::GONE, "结果尚未生成或已经清理".into()))?;
        if info.expires_at <= now() {
            return Err(ApiError(StatusCode::GONE, "结果已过期".into()));
        }
        let path: Option<String> = db
            .query_row("SELECT result_path FROM tasks WHERE id=?", [id], |r| {
                r.get(0)
            })
            .map_err(db_error)?;
        (
            path.ok_or_else(|| {
                ApiError(
                    StatusCode::CONFLICT,
                    "结果在合成节点，请先使用直连或申请中转".into(),
                )
            })?,
            info,
        )
    };
    let file = fs::File::open(path)
        .await
        .map_err(|_| ApiError(StatusCode::GONE, "结果文件不可用".into()))?;
    let mut response = Body::from_stream(ReaderStream::new(file)).into_response();
    let out = response.headers_mut();
    out.insert(
        header::CONTENT_TYPE,
        HeaderValue::from_str(&info.content_type)
            .unwrap_or(HeaderValue::from_static("application/octet-stream")),
    );
    out.insert(
        header::CONTENT_LENGTH,
        HeaderValue::from_str(&info.bytes.to_string()).unwrap(),
    );
    out.insert("x-sha256", HeaderValue::from_str(&info.sha256).unwrap());
    // Percent-encode all bytes for a safe UTF-8 RFC 5987 filename.
    let encoded: String = info.filename.bytes().map(|c| format!("%{c:02X}")).collect();
    out.insert(
        header::CONTENT_DISPOSITION,
        HeaderValue::from_str(&format!(
            "attachment; filename=\"result.bin\"; filename*=UTF-8''{encoded}"
        ))
        .unwrap(),
    );
    Ok(response)
}
async fn cleanup(state: &AppState) {
    let rows: Vec<(String, String)> = {
        let db = state.db.lock().unwrap();
        let mut stmt = match db.prepare("SELECT id,result_path FROM tasks WHERE state='completed' AND result_expires_at<=? AND result_path IS NOT NULL") {
            Ok(s) => s, Err(e) => { eprintln!("cleanup query: {e}"); return; }
        };
        let rows = match stmt.query_map([now()], |r| Ok((r.get(0)?, r.get(1)?))) {
            Ok(rows) => rows,
            Err(e) => {
                eprintln!("cleanup query: {e}");
                return;
            }
        };
        rows.filter_map(Result::ok).collect()
    };
    for (id, path) in rows {
        match fs::remove_file(&path).await {
            Ok(()) => (),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => (),
            Err(e) => {
                eprintln!("cleanup file: {e}");
                continue;
            }
        }
        let _ = fs::remove_dir(state.temp_dir.join(&id)).await;
        let db = state.db.lock().unwrap();
        let _ = db.execute("UPDATE tasks SET result_path=NULL,result_filename=NULL,result_sha256=NULL,result_bytes=NULL,result_content_type=NULL WHERE id=? AND result_path=?", params![id, path]);
    }
    // A process crash can bypass Tempfile::drop or interrupt rename + DB commit.
    // Reap old, unreferenced files only in task folders without an active lease.
    let Ok(mut folders) = fs::read_dir(&state.temp_dir).await else {
        return;
    };
    while let Ok(Some(folder)) = folders.next_entry().await {
        let id = folder.file_name().to_string_lossy().to_string();
        if Uuid::parse_str(&id).is_err() {
            continue;
        }
        let (active, retained): (bool, Option<String>) =
            {
                let db = state.db.lock().unwrap();
                match db.query_row(
                "SELECT state='running' AND lease_until>?,result_path FROM tasks WHERE id=?",
                params![now(), id],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .optional() {
                Ok(Some(row)) => row,
                Ok(None) => (false, None),
                Err(e) => { eprintln!("cleanup owner query: {e}"); continue; }
            }
            };
        if active {
            continue;
        }
        let Ok(mut files) = fs::read_dir(folder.path()).await else {
            continue;
        };
        while let Ok(Some(file)) = files.next_entry().await {
            let path = file.path();
            if retained.as_deref() == path.to_str() {
                continue;
            }
            if !matches!(
                path.extension().and_then(|s| s.to_str()),
                Some("part" | "result")
            ) {
                continue;
            }
            let old = file
                .metadata()
                .await
                .ok()
                .filter(|m| m.is_file())
                .and_then(|m| m.modified().ok())
                .and_then(|t| t.elapsed().ok())
                .is_some_and(|age| age.as_secs() > 3600);
            if old {
                let _ = fs::remove_file(path).await;
            }
        }
        let _ = fs::remove_dir(folder.path()).await;
    }
}

async fn shutdown_signal() {
    #[cfg(unix)]
    {
        let mut terminate =
            tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
                .expect("SIGTERM handler");
        tokio::select! { _=tokio::signal::ctrl_c()=>{}, _=terminate.recv()=>{} }
    }
    #[cfg(not(unix))]
    {
        let _ = tokio::signal::ctrl_c().await;
    }
}
fn router(state: AppState) -> Router {
    Router::new()
        .route("/healthz", get(health))
        .route("/v1/tasks", post(create_task).get(list_tasks))
        .route("/v1/tasks/claim", post(claim_task))
        .route("/v1/tasks/{id}", get(get_task))
        .route("/v1/tasks/{id}/status", get(get_task_status))
        .route("/v1/tasks/{id}/heartbeat", post(heartbeat))
        .route("/v1/tasks/{id}/fail", post(fail_task))
        .route("/v1/tasks/{id}/complete", post(complete_task))
        .route("/v1/tasks/{id}/result/request", post(request_transfer))
        .route("/v1/transfers/claim", post(claim_transfer))
        .route(
            "/v1/tasks/{id}/transfer/heartbeat",
            post(transfer_heartbeat),
        )
        .route("/v1/tasks/{id}/transfer/fail", post(transfer_fail))
        .route(
            "/v1/tasks/{id}/result",
            post(upload_result).get(download_result),
        )
        .layer(DefaultBodyLimit::max(16 * 1024 * 1024))
        .with_state(state)
}
#[tokio::main]
async fn main() {
    let bind = env::var("SHANYE_RELAY_BIND").unwrap_or_else(|_| "127.0.0.1:17870".into());
    let token = env::var("SHANYE_RELAY_TOKEN").expect("SHANYE_RELAY_TOKEN is required");
    assert!(
        !token.trim().is_empty(),
        "SHANYE_RELAY_TOKEN must not be empty"
    );
    let database = PathBuf::from(
        env::var("SHANYE_RELAY_DATABASE").unwrap_or_else(|_| "./relay.sqlite".into()),
    );
    let temp_dir =
        PathBuf::from(env::var("SHANYE_RELAY_TEMP_DIR").unwrap_or_else(|_| "./relay-temp".into()));
    if let Some(parent) = database.parent().filter(|p| !p.as_os_str().is_empty()) {
        std::fs::create_dir_all(parent).expect("create database directory");
    }
    std::fs::create_dir_all(&temp_dir).expect("create result directory");
    let conn = Connection::open(database).expect("open relay database");
    init_db(&conn).expect("initialize relay database");
    let state = AppState {
        db: Arc::new(Mutex::new(conn)),
        token,
        temp_dir,
        max_result_bytes: env_u64("SHANYE_RELAY_MAX_RESULT_BYTES", 8 * 1024 * 1024 * 1024),
        result_ttl_secs: env_u64("SHANYE_RELAY_RESULT_TTL_SECS", 86400).clamp(60, 365 * 86400)
            as i64,
    };
    let cleanup_state = state.clone();
    tokio::spawn(async move {
        let interval = env_u64("SHANYE_RELAY_CLEANUP_SECS", 300).max(1);
        loop {
            cleanup(&cleanup_state).await;
            sleep(Duration::from_secs(interval)).await;
        }
    });
    let listener = tokio::net::TcpListener::bind(&bind)
        .await
        .expect("bind relay listener");
    println!("shanye-task-relay listening on {bind}");
    axum::serve(listener, router(state))
        .with_graceful_shutdown(shutdown_signal())
        .await
        .expect("relay server failed");
}

#[cfg(test)]
mod tests;

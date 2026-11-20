//! Result discovery and on-demand transfer are independent of task execution.
use super::*;

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq)]
pub(crate) struct DirectResult {
    pub url: String,
    pub token: String,
    pub expires_at: i64,
}
#[derive(Deserialize)]
pub(crate) struct Completion {
    worker_id: String,
    lease_token: String,
    filename: String,
    content_type: String,
    sha256: String,
    bytes: i64,
    direct: DirectResult,
    progress: Option<Value>,
}

pub(crate) fn migrate(db: &Connection) -> rusqlite::Result<()> {
    let names = db
        .prepare("PRAGMA table_info(tasks)")?
        .query_map([], |r| r.get::<_, String>(1))?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    for (name, definition) in [
        ("result_direct_json", "TEXT"),
        ("result_worker_id", "TEXT"),
        ("transfer_state", "TEXT NOT NULL DEFAULT 'none'"),
        ("transfer_attempts", "INTEGER NOT NULL DEFAULT 0"),
        ("transfer_lease_until", "INTEGER"),
        ("transfer_lease_token", "TEXT"),
        ("transfer_error", "TEXT"),
    ] {
        if !names.iter().any(|n| n == name) {
            db.execute_batch(&format!("ALTER TABLE tasks ADD COLUMN {name} {definition}"))?;
        }
    }
    Ok(())
}

pub(crate) async fn complete_task(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
    Json(body): Json<Completion>,
) -> ApiResult<TaskResponse> {
    auth(&headers, &state)?;
    let filename = sanitize_filename(Some(&body.filename))?;
    let uri = body.direct.url.parse::<axum::http::Uri>().ok();
    let progress = body.progress.unwrap_or_else(|| json!({})).to_string();
    if body.bytes < 1
        || body.bytes as u64 > state.max_result_bytes
        || body.sha256.len() != 64
        || !body.sha256.bytes().all(|b| b.is_ascii_hexdigit())
        || body.direct.expires_at <= now()
        || body.direct.url.len() > 2048
        || !uri.as_ref().is_some_and(|u| {
            matches!(u.scheme_str(), Some("http" | "https"))
                && u.host().is_some()
                && !u.authority().unwrap().as_str().contains('@')
                && u.query().is_none()
        })
        || body.direct.token.len() < 24
        || body.direct.token.len() > 256
        || !body.direct.token.bytes().all(|b| (33..=126).contains(&b))
        || body.content_type.is_empty()
        || body.content_type.len() > 120
        || HeaderValue::from_str(&body.content_type).is_err()
        || progress.len() > 1024 * 1024
    {
        return Err(ApiError(
            StatusCode::BAD_REQUEST,
            "结果大小、校验信息或直连配置无效".into(),
        ));
    }
    let db = state.db.lock().unwrap();
    require_owner(&db, &id, &body.worker_id, &body.lease_token)?;
    db.execute(
        "UPDATE tasks SET state='completed',worker_id=NULL,lease_until=NULL,lease_token=NULL,
        result_filename=?,result_sha256=?,result_bytes=?,result_content_type=?,result_expires_at=?,
        result_direct_json=?,result_worker_id=?,transfer_state='none',error=NULL,
        progress_json=?,updated_at=? WHERE id=?",
        params![
            filename,
            body.sha256.to_lowercase(),
            body.bytes,
            body.content_type,
            body.direct.expires_at,
            serde_json::to_string(&body.direct).unwrap(),
            body.worker_id,
            progress,
            now(),
            id
        ],
    )
    .map_err(db_error)?;
    Ok(Json(summary(row_response(&db, &id)?)))
}

pub(crate) async fn request_transfer(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> ApiResult<TaskResponse> {
    auth(&headers, &state)?;
    let db = state.db.lock().unwrap();
    let task = row_response(&db, &id)?;
    let info = task
        .result
        .as_ref()
        .ok_or_else(|| ApiError(StatusCode::CONFLICT, "任务尚未完成".into()))?;
    if task.state != "completed" {
        return Err(ApiError(StatusCode::CONFLICT, "任务尚未完成".into()));
    }
    if info.expires_at <= now() {
        return Err(ApiError(StatusCode::GONE, "结果已过期，请重新合成".into()));
    }
    if info.relay_state == "ready"
        || info.relay_state == "queued"
        || info.relay_state == "uploading"
    {
        return Ok(Json(summary(task)));
    }
    if info.direct.is_none() {
        return Err(ApiError(StatusCode::GONE, "没有可中转的本地结果".into()));
    }
    // Concurrent callers share one transfer; retries never re-run the original task.
    db.execute(
        "UPDATE tasks SET transfer_state='queued',transfer_attempts=0,transfer_error=NULL,
        transfer_lease_until=NULL,transfer_lease_token=NULL,updated_at=? WHERE id=?",
        params![now(), id],
    )
    .map_err(db_error)?;
    Ok(Json(summary(row_response(&db, &id)?)))
}

pub(crate) async fn claim_transfer(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(body): Json<ClaimRequest>,
) -> ApiResult<Value> {
    auth(&headers, &state)?;
    if body.worker_id.is_empty() || body.worker_id.len() > 120 {
        return Err(ApiError(StatusCode::BAD_REQUEST, "worker_id 无效".into()));
    }
    let db = state.db.lock().unwrap();
    let tx = db.unchecked_transaction().map_err(db_error)?;
    tx.execute("UPDATE tasks SET transfer_state=CASE WHEN transfer_attempts>=3 THEN 'failed' ELSE 'queued' END,
        transfer_lease_until=NULL,transfer_lease_token=NULL,transfer_error='transfer lease expired',updated_at=?
        WHERE transfer_state='uploading' AND transfer_lease_until<=?", params![now(),now()]).map_err(db_error)?;
    let id: Option<String> = tx.query_row("SELECT id FROM tasks WHERE state='completed' AND
        transfer_state='queued' AND result_worker_id=? AND result_expires_at>? ORDER BY updated_at,rowid LIMIT 1",
        params![body.worker_id, now()], |r| r.get(0)).optional().map_err(db_error)?;
    let Some(id) = id else {
        tx.commit().map_err(db_error)?;
        return Ok(Json(json!({"task":null})));
    };
    let token = Uuid::new_v4().to_string();
    tx.execute(
        "UPDATE tasks SET transfer_state='uploading',transfer_attempts=transfer_attempts+1,
        transfer_lease_token=?,transfer_lease_until=?,transfer_error=NULL,updated_at=? WHERE id=?",
        params![token, now() + lease_seconds(body.lease_seconds), now(), id],
    )
    .map_err(db_error)?;
    tx.commit().map_err(db_error)?;
    // Only the original result holder can claim; no text or privileged direct token is needed here.
    Ok(Json(json!({"task":{"id":id,"lease_token":token}})))
}

fn require_transfer_owner(
    db: &Connection,
    id: &str,
    worker: &str,
    token: &str,
) -> Result<(), ApiError> {
    let active: bool = db
        .query_row(
            "SELECT EXISTS(SELECT 1 FROM tasks WHERE id=? AND state='completed'
        AND result_worker_id=? AND transfer_state='uploading' AND transfer_lease_token=?
        AND transfer_lease_until>? AND result_expires_at>?)",
            params![id, worker, token, now(), now()],
            |r| r.get(0),
        )
        .map_err(db_error)?;
    if !active {
        return Err(ApiError(StatusCode::CONFLICT, "中转上传租约已失效".into()));
    }
    Ok(())
}
pub(crate) fn require_upload_owner(
    db: &Connection,
    id: &str,
    worker: &str,
    token: &str,
    filename: &str,
    sha: &str,
    bytes: i64,
) -> Result<(), ApiError> {
    let task = row_response(db, id)?;
    if task.state == "running" {
        return require_owner(db, id, worker, token);
    }
    require_transfer_owner(db, id, worker, token)?;
    let info = task.result.unwrap();
    if info.filename != filename || !info.sha256.eq_ignore_ascii_case(sha) || info.bytes != bytes {
        return Err(ApiError(
            StatusCode::CONFLICT,
            "上传内容与完成通知不一致".into(),
        ));
    }
    Ok(())
}

pub(crate) async fn transfer_heartbeat(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
    Json(body): Json<HeartbeatRequest>,
) -> ApiResult<Value> {
    auth(&headers, &state)?;
    let db = state.db.lock().unwrap();
    require_transfer_owner(&db, &id, &body.worker_id, &body.lease_token)?;
    db.execute(
        "UPDATE tasks SET transfer_lease_until=?,updated_at=? WHERE id=?",
        params![now() + lease_seconds(body.lease_seconds), now(), id],
    )
    .map_err(db_error)?;
    Ok(Json(json!({"ok":true})))
}
pub(crate) async fn transfer_fail(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
    Json(body): Json<FailRequest>,
) -> ApiResult<Value> {
    auth(&headers, &state)?;
    let db = state.db.lock().unwrap();
    require_transfer_owner(&db, &id, &body.worker_id, &body.lease_token)?;
    db.execute("UPDATE tasks SET transfer_state=CASE WHEN ? AND transfer_attempts<3 THEN 'queued' ELSE 'failed' END,
        transfer_lease_token=NULL,transfer_lease_until=NULL,transfer_error=?,updated_at=? WHERE id=?",
        params![body.retryable.unwrap_or(true),body.error.chars().take(1000).collect::<String>(),now(),id]).map_err(db_error)?;
    Ok(Json(json!({"ok":true})))
}

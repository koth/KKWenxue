//! 目录与正文任务 API。
//!
//! 元信息直接读本机 SQLite；正文只通过 relay 建立 content.fetch/content.serve
//! 任务。worker 将正文保存在家庭服务器，完成通知带局域网直连地址，只有 App
//! 无法直连时才调用 download 路由申请 relay 中转。

use axum::{
    body::Body,
    extract::{Path, Query, State},
    http::StatusCode,
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use futures_util::StreamExt;
use serde::Deserialize;
use serde_json::{json, Value};
use std::sync::Arc;
use tokio::sync::Mutex;

use crate::db;

#[derive(Clone)]
pub struct AppState {
    pub db: Arc<Mutex<rusqlite::Connection>>,
    pub relay_url: String,
    pub relay_token: String,
    pub relay_client: reqwest::Client,
}

type ApiResult = Result<Json<Value>, (StatusCode, String)>;

fn db_err(e: rusqlite::Error) -> (StatusCode, String) {
    eprintln!("catalog db: {e}");
    (StatusCode::INTERNAL_SERVER_ERROR, "目录数据库错误".into())
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
    Ok(Json(json!({"items": rows, "nextCursor": next})))
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
    Ok(Json(json!({"items": rows, "nextCursor": next})))
}

async fn book(State(st): State<AppState>, Path(id): Path<i64>) -> ApiResult {
    let conn = st.db.lock().await;
    match db::get_book(&conn, id).map_err(db_err)? {
        Some(b) => Ok(Json(serde_json::to_value(b).unwrap_or(Value::Null))),
        None => Err((StatusCode::NOT_FOUND, "没有这本书".into())),
    }
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

async fn content(
    State(st): State<AppState>,
    Path(id): Path<i64>,
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
    let latest = book.latest_chapter_id;
    let base = format!("content.fetch:{id}:{latest}");
    let mut task = create_relay_task(&st, "content.fetch", json!({"bookId": id, "latestChapterId": latest, "title": book.title, "author": book.author}), base.clone(), 0).await?;
    // relay 的任务记录会保留，但结果过期后幂等键不能复用；换版本键重新排队。
    if task.get("state").and_then(Value::as_str) == Some("completed") && !task_result_ready(&task) {
        task = create_relay_task(&st, "content.fetch", json!({"bookId": id, "latestChapterId": latest, "title": book.title, "author": book.author}),
            format!("{base}:{}", now()), 0).await?;
    }
    // A cached fetch is only the small manifest; the App needs the serve task's ZIP.
    if task_result_ready(&task) {
        let version = task
            .pointer("/result/sha256")
            .and_then(Value::as_str)
            .unwrap_or("unknown");
        task = create_relay_task(
            &st,
            "content.serve",
            json!({"bookId": id, "version": version}),
            format!("content.serve:{id}:{version}"),
            0,
        )
        .await?;
    }
    let state = task
        .get("state")
        .and_then(Value::as_str)
        .unwrap_or("queued");
    let code = if task_result_ready(&task) {
        StatusCode::OK
    } else {
        StatusCode::ACCEPTED
    };
    let mut out = json!({"taskId": task["id"], "state": state});
    if task_result_ready(&task) {
        out["downloadUrl"] = json!(format!(
            "/v1/catalog/content/{}/download",
            task["id"].as_str().unwrap_or("")
        ));
    }
    Ok((code, Json(out)).into_response())
}

async fn status(State(st): State<AppState>, Path(task_id): Path<String>) -> ApiResult {
    let (_, mut task) = relay_json(
        &st,
        reqwest::Method::GET,
        &format!("/v1/tasks/{task_id}"),
        None,
    )
    .await?;
    // fetch 完成后再排一个 serve，serve 只打包家里已经落盘的正文。
    if task.get("kind").and_then(Value::as_str) == Some("content.fetch") && task_result_ready(&task)
    {
        let book_id = task
            .pointer("/payload/bookId")
            .and_then(Value::as_i64)
            .unwrap_or(0);
        let version = task
            .pointer("/result/sha256")
            .and_then(Value::as_str)
            .unwrap_or("unknown");
        task = create_relay_task(
            &st,
            "content.serve",
            json!({"bookId":book_id,"version":version}),
            format!("content.serve:{book_id}:{version}"),
            0,
        )
        .await?;
    }
    let mut out = task.clone();
    out["taskId"] = task.get("id").cloned().unwrap_or(Value::Null);
    if task_result_ready(&task) {
        if let Some(direct) = task.pointer("/result/direct").cloned() {
            out["direct"] = direct;
        }
        out["downloadUrl"] = json!(format!(
            "/v1/catalog/content/{}/download",
            task["id"].as_str().unwrap_or("")
        ));
    }
    Ok(Json(out))
}

async fn download(
    State(st): State<AppState>,
    Path(task_id): Path<String>,
) -> Result<Response, (StatusCode, String)> {
    let (_, task) = relay_json(
        &st,
        reqwest::Method::GET,
        &format!("/v1/tasks/{task_id}"),
        None,
    )
    .await?;
    if !task_result_ready(&task) {
        return Err((StatusCode::CONFLICT, "正文尚未生成".into()));
    }
    let relay_state = task
        .pointer("/result/relay_state")
        .and_then(Value::as_str)
        .unwrap_or("none");
    if relay_state != "ready"
        && task
            .pointer("/result/local_available")
            .and_then(Value::as_bool)
            != Some(true)
    {
        let _ = relay_json(
            &st,
            reqwest::Method::POST,
            &format!("/v1/tasks/{task_id}/result/request"),
            Some(json!({})),
        )
        .await?;
        return Err((StatusCode::ACCEPTED, "已请求中转，稍后重试".into()));
    }
    let response = st
        .relay_client
        .get(format!("{}/v1/tasks/{task_id}/result", st.relay_url))
        .header("X-Relay-Token", &st.relay_token)
        .send()
        .await
        .map_err(|e| (StatusCode::BAD_GATEWAY, format!("下载失败: {e}")))?;
    if !response.status().is_success() {
        return Err(relay_error(
            response.status(),
            response.text().await.unwrap_or_default(),
        ));
    }
    let headers = response.headers().clone();
    let stream = response
        .bytes_stream()
        .map(|item| item.map_err(std::io::Error::other));
    let mut out = Body::from_stream(stream).into_response();
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
        .route("/v1/catalog/books/{id}", get(book))
        .route("/v1/catalog/books/{id}/content", post(content))
        .route("/v1/catalog/content/{id}", get(status))
        .route("/v1/catalog/content/{id}/download", get(download))
        .with_state(state)
}

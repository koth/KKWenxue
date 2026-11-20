//! 目录与正文任务 API。
//!
//! 元信息直接读本机 SQLite；正文只通过 relay 建立 content.fetch/content.serve
//! 任务。worker 在服务器下载 TXT 并打包；App 只从目录代理下载 relay 上的正文包。

use axum::{
    body::Body,
    extract::{Path, Query, State},
    http::StatusCode,
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use futures_util::StreamExt;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::{hash_map::DefaultHasher, HashSet};
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
        (self.0, Json(json!({"stage": "plan", "code": self.1, "error": self.2}))).into_response()
    }
}

fn digest(value: &str) -> bool {
    value.len() == 64 && value.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

fn validate_update_request(book: &db::BookRow, request: &UpdatePlanRequest) -> Result<(), UpdateError> {
    if !digest(&request.from_revision) {
        return Err(UpdateError(StatusCode::BAD_REQUEST, "invalid_base", "前置正文版本无效"));
    }
    if request.target_revision != remote_revision(book) {
        return Err(UpdateError(StatusCode::CONFLICT, "stale_target", "目录版本已变化，请重新检查更新"));
    }
    if request.chapters.len() > 2000 {
        return Err(UpdateError(StatusCode::PAYLOAD_TOO_LARGE, "chapter_limit", "本地章节数量超过更新限制"));
    }
    let prefix = format!("online:catalog:{}:chapter:", book.id);
    let mut ids = HashSet::new();
    if request.chapters.is_empty() || request.chapters.iter().enumerate().any(|(i, chapter)| {
        let id = chapter.chapter_id.strip_prefix(&prefix).unwrap_or("");
        let stable = id.strip_prefix("sha256:").and_then(|v| v.split_once(':'))
            .is_some_and(|(hash, occurrence)| digest(hash) && occurrence.parse::<u16>().is_ok_and(|v| v > 0 && v <= 2000));
        chapter.index != i || chapter.title.is_empty() || chapter.title.len() > 440 ||
            !digest(&chapter.body_sha256) || !(stable || id == i.to_string()) || !ids.insert(&chapter.chapter_id)
    }) {
        return Err(UpdateError(StatusCode::BAD_REQUEST, "invalid_manifest", "本地章节清单无效"));
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

fn remote_revision(book: &db::BookRow) -> String {
    format!(
        "catalog-v1:{}:{}:{}:{}",
        book.id, book.latest_chapter_id, book.chapter_no, book.last_update_at
    )
}

async fn version(State(st): State<AppState>, Path(id): Path<i64>) -> ApiResult {
    let conn = st.db.lock().await;
    let book = db::get_book(&conn, id)
        .map_err(db_err)?
        .ok_or((StatusCode::NOT_FOUND, "没有这本书".into()))?;
    Ok(Json(json!({
        "bookId": book.id,
        "latestChapterId": book.latest_chapter_id,
        "chapterCount": book.chapter_no,
        "remoteRevision": remote_revision(&book),
        "lastUpdateAt": book.last_update_at,
        "latestChapter": book.latest_chapter
    })))
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
        Some("failed") if stage == "content.update" && task["error"].as_str().is_some_and(|v| v.starts_with("invalid_base:")) =>
            Some("前置版本与服务器快照不一致，请重新检查更新"),
        Some("failed") if stage == "content.update" && task["error"].as_str().is_some_and(|v| v.starts_with("fetch:")) =>
            Some("服务器未能从书源下载正文，请稍后重试"),
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
    if matches!(task["kind"].as_str(), Some("content.serve" | "content.update")) && task_result_ready(task) {
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
    Json(request): Json<UpdatePlanRequest>,
) -> Result<Response, Response> {
    let book = {
        let conn = st.db.lock().await;
        db::get_book(&conn, id)
            .map_err(|e| db_err(e).into_response())?
            .ok_or_else(|| UpdateError(StatusCode::NOT_FOUND, "book_not_found", "没有这本书").into_response())?
    };
    validate_update_request(&book, &request).map_err(IntoResponse::into_response)?;
    if st.relay_token.trim().is_empty() {
        return Err(UpdateError(StatusCode::SERVICE_UNAVAILABLE, "unavailable", "正文服务尚未配置").into_response());
    }
    let from = request.from_revision;
    let target = request.target_revision;
    let mut plan_key = DefaultHasher::new();
    target.hash(&mut plan_key);
    request.chapters.hash(&mut plan_key);
    let payload = json!({
        "bookId": id,
        "latestChapterId": book.latest_chapter_id,
        "fromRevision": from,
        "targetRevision": target,
        "chapters": request.chapters
    });
    let task = create_content_task(
        &st,
        "content.update",
        payload,
        format!("content.update:txt-v2:{id}:{from}:{:016x}", plan_key.finish()),
    )
    .await.map_err(|(status, _)| UpdateError(status, "task_creation", "无法创建正文更新任务，请重新检查后重试").into_response())?;
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
    // The preheat key uses a different priority. Relay rejects collisions even
    // for the same book/version; workers still share the on-disk book cache.
    let catalog_revision = remote_revision(&book);
    let mut task = create_content_task(&st, "content.fetch",
        json!({"bookId": id, "latestChapterId": latest, "title": book.title, "author": book.author, "catalogRevision": catalog_revision}),
        format!("content.fetch:txt-v3:{catalog_revision}")).await?;
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
) -> Result<Response, (StatusCode, String)> {
    let (_, task) = relay_json(
        &st,
        reqwest::Method::GET,
        &format!("/v1/tasks/{task_id}"),
        None,
    )
    .await?;
    if !matches!(task["kind"].as_str(), Some("content.serve" | "content.update")) {
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
        .route("/v1/catalog/books/{id}/version", get(version))
        .route("/v1/catalog/books/{id}/update-plan", post(update_plan))
        .route("/v1/catalog/books/{id}/content", post(content))
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
            download(State(st.clone()), Path("book-pack".to_owned()))
                .await
                .unwrap_err()
                .0,
            StatusCode::ACCEPTED,
        );
        let result = download(State(st), Path("book-pack".to_owned()))
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
            conn.execute("INSERT INTO books (id,chapter_no,latest_chapter_id) VALUES (7,2,42)", []).unwrap();
        }
        let api = start_mock(router(st)).await;
        let url = format!("{}/v1/catalog/books/7", api.relay_url);
        let version: Value = api.relay_client.get(format!("{url}/version")).send().await.unwrap().json().await.unwrap();
        let mut body = json!({"fromRevision":"invalid","targetRevision":"old","chapters":[
            {"chapterId":"online:catalog:7:chapter:0","index":0,"title":"第一章","bodySha256":"b".repeat(64)}
        ]});
        let post = |body: &Value| api.relay_client.post(format!("{url}/update-plan")).json(body);
        let invalid = post(&body).send().await.unwrap();
        assert_eq!(invalid.status(), StatusCode::BAD_REQUEST);
        assert_eq!(invalid.json::<Value>().await.unwrap()["code"], "invalid_base");
        body["fromRevision"] = json!("a".repeat(64));
        assert_eq!(post(&body).send().await.unwrap().status(), StatusCode::CONFLICT);
        assert_eq!(calls.load(Ordering::SeqCst), 0);
        body["targetRevision"] = version["remoteRevision"].clone();
        for _ in 0..2 {
            let valid = post(&body).send().await.unwrap();
            assert_eq!(valid.status(), StatusCode::ACCEPTED);
            assert_eq!(valid.json::<Value>().await.unwrap()["taskId"], "new-job");
        }
        assert_eq!(calls.load(Ordering::SeqCst), 4);
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
            })).0;
            assert_eq!(view["kind"], kind);
            assert_eq!(view["error"], expected);
            assert!(view.get("downloadUrl").is_none());
        }
    }
}

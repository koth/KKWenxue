use super::*;
use axum::http::Request;
use http_body_util::BodyExt;
use tower::ServiceExt;

struct Fixture {
    state: AppState,
    _dir: tempfile::TempDir,
}
impl Fixture {
    async fn publish_local(&self, task: &Value, data: &[u8]) -> (StatusCode, Value) {
        self.request(&format!("/v1/tasks/{}/complete", task["id"].as_str().unwrap()), Some(json!({
            "worker_id":"worker-1", "lease_token":task["lease_token"], "filename":"package.zip",
            "content_type":"application/zip", "sha256":hex::encode(Sha256::digest(data)), "bytes":data.len(),
            "direct":{"url":format!("http://192.168.3.32:17871/v1/results/{}",task["id"].as_str().unwrap()),
                "token":"scoped-result-capability-token", "expires_at":now()+60}
        }))).await
    }
    fn new() -> Self {
        let dir = tempfile::tempdir().unwrap();
        let db = Connection::open_in_memory().unwrap();
        init_db(&db).unwrap();
        Self {
            state: AppState {
                db: Arc::new(Mutex::new(db)),
                token: "test-token".into(),
                temp_dir: dir.path().to_owned(),
                max_result_bytes: 1024,
                result_ttl_secs: 60,
            },
            _dir: dir,
        }
    }
    async fn request(&self, path: &str, body: Option<Value>) -> (StatusCode, Value) {
        let req = Request::builder()
            .method(if body.is_some() { "POST" } else { "GET" })
            .uri(path)
            .header("x-relay-token", "test-token")
            .header("content-type", "application/json")
            .body(Body::from(body.map(|v| v.to_string()).unwrap_or_default()))
            .unwrap();
        let response = router(self.state.clone()).oneshot(req).await.unwrap();
        let status = response.status();
        let data = response.into_body().collect().await.unwrap().to_bytes();
        (status, serde_json::from_slice(&data).unwrap())
    }
    async fn create(&self, kind: &str, priority: i64) -> String {
        let (status, task) = self
            .request(
                "/v1/tasks",
                Some(json!({"kind":kind,"payload":{"text":"你好。"},"priority":priority})),
            )
            .await;
        assert_eq!(status, StatusCode::OK);
        task["id"].as_str().unwrap().to_owned()
    }
    async fn claim(&self, kind: &str) -> Value {
        self.request(
            "/v1/tasks/claim",
            Some(json!({"worker_id":"worker-1","kinds":[kind],"lease_seconds":90})),
        )
        .await
        .1["task"]
            .clone()
    }
    fn expire(&self, id: &str) {
        self.state
            .db
            .lock()
            .unwrap()
            .execute(
                "UPDATE tasks SET lease_until=? WHERE id=?",
                params![now() - 1, id],
            )
            .unwrap();
    }
    async fn upload(&self, task: &Value, data: &[u8], sha: Option<&str>) -> (StatusCode, Value) {
        let id = task["id"].as_str().unwrap();
        let digest = hex::encode(Sha256::digest(data));
        let req = Request::builder()
            .method("POST")
            .uri(format!("/v1/tasks/{id}/result"))
            .header("x-relay-token", "test-token")
            .header("x-worker-id", "worker-1")
            .header("x-lease-token", task["lease_token"].as_str().unwrap())
            .header("x-filename", "package.zip")
            .header("content-type", "application/zip")
            .header("content-length", data.len())
            .header("x-sha256", sha.unwrap_or(&digest))
            .body(Body::from(data.to_owned()))
            .unwrap();
        let response = router(self.state.clone()).oneshot(req).await.unwrap();
        let status = response.status();
        (
            status,
            serde_json::from_slice(&response.into_body().collect().await.unwrap().to_bytes())
                .unwrap(),
        )
    }
}

#[tokio::test]
async fn local_completion_publishes_metadata_without_uploading_any_file() {
    let f = Fixture::new();
    let id = f.create("any-generic-kind", 0).await;
    let task = f.claim("any-generic-kind").await;
    let (status, result) = f.publish_local(&task, b"result").await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(result["state"], "completed");
    assert_eq!(result["result"]["relay_state"], "none");
    assert!(
        f.request(&format!("/v1/tasks/{id}"), None).await.1["result"]["direct"]["token"]
            .is_string()
    );
    let status = f.request(&format!("/v1/tasks/{id}/status"), None).await.1;
    assert!(status.get("payload").is_none());
    assert!(status["result"].get("direct").is_none());
    assert_eq!(std::fs::read_dir(&f.state.temp_dir).unwrap().count(), 0);
    assert_eq!(
        f.request(&format!("/v1/tasks/{id}/result"), None).await.0,
        StatusCode::CONFLICT
    );
    assert_eq!(
        f.upload(&task, b"result", None).await.0,
        StatusCode::CONFLICT
    );
    let listed = f.request("/v1/tasks", None).await.1;
    assert!(listed["items"][0]["result"].get("direct").is_none());
    // A completed job cannot be reclaimed; synthesis capacity remains available.
    let next = f.create("any-generic-kind", 0).await;
    assert_eq!(f.claim("any-generic-kind").await["id"], next);
}

#[tokio::test]
async fn transfer_is_on_demand_idempotent_and_pinned_to_the_result_holder() {
    let f = Fixture::new();
    let id = f.create("tts", 0).await;
    let task = f.claim("tts").await;
    f.publish_local(&task, b"result").await;
    let claim = json!({"worker_id":"worker-1","lease_seconds":90});
    assert!(f
        .request("/v1/transfers/claim", Some(claim.clone()))
        .await
        .1["task"]
        .is_null());
    let path = format!("/v1/tasks/{id}/result/request");
    for _ in 0..3 {
        let result = f.request(&path, Some(json!({}))).await;
        assert_eq!(result.0, StatusCode::OK);
        assert_eq!(result.1["result"]["relay_state"], "queued");
    }
    assert!(f
        .request(
            "/v1/transfers/claim",
            Some(json!({"worker_id":"other-worker"}))
        )
        .await
        .1["task"]
        .is_null());
    let transfer = f
        .request("/v1/transfers/claim", Some(claim.clone()))
        .await
        .1["task"]
        .clone();
    assert_eq!(transfer["id"], id);
    assert_ne!(transfer["lease_token"], task["lease_token"]);
    assert!(f.request("/v1/transfers/claim", Some(claim)).await.1["task"].is_null());
    assert_eq!(
        f.request(&path, Some(json!({}))).await.1["result"]["relay_state"],
        "uploading"
    );
    assert_eq!(
        f.upload(&transfer, b"changed", None).await.0,
        StatusCode::CONFLICT
    );
    assert_eq!(f.upload(&transfer, b"result", None).await.0, StatusCode::OK);
    let ready = f.request(&path, Some(json!({}))).await.1;
    assert_eq!(ready["result"]["relay_state"], "ready");
    assert_eq!(ready["attempts"], 1);
    let req = Request::builder()
        .uri(format!("/v1/tasks/{id}/result"))
        .header("x-relay-token", "test-token")
        .body(Body::empty())
        .unwrap();
    let response = router(f.state.clone()).oneshot(req).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(
        response.into_body().collect().await.unwrap().to_bytes(),
        b"result"[..]
    );
    let etag = format!("\"{}\"", ready["result"]["sha256"].as_str().unwrap());
    for (range, if_range, status, body) in [
        (
            "bytes=2-",
            etag.as_str(),
            StatusCode::PARTIAL_CONTENT,
            b"sult".as_slice(),
        ),
        (
            "bytes=1-3",
            etag.as_str(),
            StatusCode::PARTIAL_CONTENT,
            b"esu".as_slice(),
        ),
        (
            "bytes=2-",
            "\"different\"",
            StatusCode::OK,
            b"result".as_slice(),
        ),
        (
            "bytes=99-",
            etag.as_str(),
            StatusCode::RANGE_NOT_SATISFIABLE,
            b"".as_slice(),
        ),
        (
            "bytes=4-2",
            etag.as_str(),
            StatusCode::RANGE_NOT_SATISFIABLE,
            b"".as_slice(),
        ),
        (
            "bytes=1-2,4-5",
            etag.as_str(),
            StatusCode::RANGE_NOT_SATISFIABLE,
            b"".as_slice(),
        ),
    ] {
        let req = Request::builder()
            .uri(format!("/v1/tasks/{id}/result"))
            .header("x-relay-token", "test-token")
            .header("Range", range)
            .header("If-Range", if_range)
            .body(Body::empty())
            .unwrap();
        let response = router(f.state.clone()).oneshot(req).await.unwrap();
        assert_eq!(response.status(), status);
        if status == StatusCode::PARTIAL_CONTENT {
            assert_eq!(response.headers()["etag"], etag);
            assert!(response.headers().contains_key("content-range"));
        }
        assert_eq!(
            response.into_body().collect().await.unwrap().to_bytes(),
            body
        );
    }
}

#[tokio::test]
async fn transfer_reclaim_fences_stale_upload_and_failure_never_resynthesizes() {
    let f = Fixture::new();
    let id = f.create("tts", 0).await;
    let task = f.claim("tts").await;
    f.publish_local(&task, b"result").await;
    f.request(&format!("/v1/tasks/{id}/result/request"), Some(json!({})))
        .await;
    let claim = json!({"worker_id":"worker-1"});
    let old = f
        .request("/v1/transfers/claim", Some(claim.clone()))
        .await
        .1["task"]
        .clone();
    f.state
        .db
        .lock()
        .unwrap()
        .execute(
            "UPDATE tasks SET transfer_lease_until=? WHERE id=?",
            params![now() - 1, id],
        )
        .unwrap();
    let new = f.request("/v1/transfers/claim", Some(claim)).await.1["task"].clone();
    assert_ne!(old["lease_token"], new["lease_token"]);
    assert_eq!(
        f.upload(&old, b"result", None).await.0,
        StatusCode::CONFLICT
    );
    assert_eq!(
        f.request(
            &format!("/v1/tasks/{id}/transfer/heartbeat"),
            Some(json!({
        "worker_id":"worker-1","lease_token":old["lease_token"]}))
        )
        .await
        .0,
        StatusCode::CONFLICT
    );
    assert_eq!(f.request(&format!("/v1/tasks/{id}/transfer/fail"),Some(json!({
        "worker_id":"worker-1","lease_token":new["lease_token"],"error":"local file unavailable","retryable":false}))).await.0,StatusCode::OK);
    let result = f.request(&format!("/v1/tasks/{id}"), None).await.1;
    assert_eq!(result["state"], "completed");
    assert_eq!(result["result"]["relay_state"], "failed");
    assert!(f.claim("tts").await.is_null());
    // Explicit retry queues transfer alone; expired packages never request another upload.
    assert_eq!(
        f.request(&format!("/v1/tasks/{id}/result/request"), Some(json!({})))
            .await
            .1["result"]["relay_state"],
        "queued"
    );
    f.state
        .db
        .lock()
        .unwrap()
        .execute(
            "UPDATE tasks SET result_expires_at=? WHERE id=?",
            params![now() - 1, id],
        )
        .unwrap();
    assert_eq!(
        f.request(&format!("/v1/tasks/{id}/result/request"), Some(json!({})))
            .await
            .0,
        StatusCode::GONE
    );
}

#[tokio::test]
async fn only_the_current_synthesis_owner_can_publish_a_local_result() {
    let f = Fixture::new();
    let id = f.create("tts", 0).await;
    let old = f.claim("tts").await;
    f.expire(&id);
    let current = f.claim("tts").await;
    assert_eq!(
        f.publish_local(&old, b"result").await.0,
        StatusCode::CONFLICT
    );
    assert_eq!(f.publish_local(&current, b"result").await.0, StatusCode::OK);
}

#[tokio::test]
async fn auth_is_required() {
    let f = Fixture::new();
    let response = router(f.state)
        .oneshot(
            Request::builder()
                .uri("/healthz")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
}
#[tokio::test]
async fn arbitrary_kinds_payloads_priority_and_deduplication() {
    let f = Fixture::new();
    let request = json!({"kind":"thumbnail","payload":[1,{"anything":true}],"dedupe_key":"same"});
    let first = f.request("/v1/tasks", Some(request.clone())).await.1;
    assert_eq!(
        first["id"],
        f.request("/v1/tasks", Some(request.clone())).await.1["id"]
    );
    let mut conflict = request;
    conflict["payload"] = json!({"changed":true});
    assert_eq!(
        f.request("/v1/tasks", Some(conflict)).await.0,
        StatusCode::CONFLICT
    );
    let low = f.create("tts", 0).await;
    let high = f.create("tts", 10).await;
    assert_eq!(f.claim("tts").await["id"], high);
    assert_eq!(f.claim("tts").await["id"], low);
    assert!(f.claim("tts").await.is_null());
    assert_eq!(
        f.claim("thumbnail").await["payload"],
        json!([1,{"anything":true}])
    );
}
#[tokio::test]
async fn reclaims_are_fenced_even_with_the_same_worker_id() {
    let f = Fixture::new();
    let id = f.create("tts", 0).await;
    let first = f.claim("tts").await;
    f.expire(&id);
    let second = f.claim("tts").await;
    assert_ne!(first["lease_token"], second["lease_token"]);
    assert_eq!(second["attempts"], 2);
    let stale = json!({"worker_id":"worker-1","lease_token":first["lease_token"],"progress":{}});
    assert_eq!(
        f.request(&format!("/v1/tasks/{id}/heartbeat"), Some(stale.clone()))
            .await
            .0,
        StatusCode::CONFLICT
    );
    let mut fail = stale;
    fail["error"] = json!("stale");
    assert_eq!(
        f.request(&format!("/v1/tasks/{id}/fail"), Some(fail))
            .await
            .0,
        StatusCode::CONFLICT
    );
    assert_eq!(f.upload(&first, b"old", None).await.0, StatusCode::CONFLICT);
    assert_eq!(f.upload(&second, b"new", None).await.0, StatusCode::OK);
}
#[tokio::test]
async fn lease_expiry_obeys_max_attempts() {
    let f = Fixture::new();
    let id = f.create("tts", 0).await;
    for attempt in 1..=3 {
        assert_eq!(f.claim("tts").await["attempts"], attempt);
        f.expire(&id);
    }
    assert!(f.claim("tts").await.is_null());
    assert_eq!(
        f.request(&format!("/v1/tasks/{id}"), None).await.1["state"],
        "failed"
    );
}
#[tokio::test]
async fn heartbeats_extend_the_requested_lease_and_keep_progress() {
    let f = Fixture::new();
    let id = f.create("tts", 0).await;
    let task = f.claim("tts").await;
    let (status, result) = f.request(&format!("/v1/tasks/{id}/heartbeat"), Some(json!({
        "worker_id":"worker-1","lease_token":task["lease_token"],"lease_seconds":900,"progress":{"done":12}
    }))).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(result["progress"]["done"], 12);
    assert!(result["lease_until"].as_i64().unwrap() >= now() + 899);
    assert!(result.get("lease_token").is_none());
}
#[tokio::test]
async fn failures_are_bounded_and_nonretryable_failures_stop() {
    let f = Fixture::new();
    let id = f.create("tts", 0).await;
    for attempt in 1..=3 {
        let task = f.claim("tts").await;
        let (_, result) = f.request(&format!("/v1/tasks/{id}/fail"), Some(json!({
            "worker_id":"worker-1","lease_token":task["lease_token"],"error":"busy","retryable":true
        }))).await;
        assert_eq!(
            result["state"],
            if attempt < 3 { "queued" } else { "failed" }
        );
    }
    let id = f.create("tts", 0).await;
    let task = f.claim("tts").await;
    let (_, result) = f.request(&format!("/v1/tasks/{id}/fail"), Some(json!({
        "worker_id":"worker-1","lease_token":task["lease_token"],"error":"bad input","retryable":false
    }))).await;
    assert_eq!(result["state"], "failed");
    assert!(f.claim("tts").await.is_null());
}
#[tokio::test]
async fn streamed_results_validate_hash_download_and_expire() {
    let f = Fixture::new();
    let id = f.create("other", 0).await;
    let task = f.claim("other").await;
    assert_eq!(
        f.upload(&task, b"result", Some(&"0".repeat(64))).await.0,
        StatusCode::BAD_REQUEST
    );
    assert_eq!(
        std::fs::read_dir(f.state.temp_dir.join(&id))
            .unwrap()
            .count(),
        0
    );
    assert_eq!(
        f.request(&format!("/v1/tasks/{id}"), None).await.1["state"],
        "running"
    );
    let (status, result) = f.upload(&task, b"result", None).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(result["state"], "completed");
    assert_eq!(result["result"]["bytes"], 6);
    assert!(result["result"].get("path").is_none());
    let req = Request::builder()
        .uri(format!("/v1/tasks/{id}/result"))
        .header("x-relay-token", "test-token")
        .body(Body::empty())
        .unwrap();
    let response = router(f.state.clone()).oneshot(req).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(response.headers()["content-type"], "application/zip");
    assert_eq!(
        response.into_body().collect().await.unwrap().to_bytes(),
        b"result"[..]
    );
    f.state
        .db
        .lock()
        .unwrap()
        .execute(
            "UPDATE tasks SET result_expires_at=? WHERE id=?",
            params![now() - 1, id],
        )
        .unwrap();
    assert_eq!(
        f.request(&format!("/v1/tasks/{id}/result"), None).await.0,
        StatusCode::GONE
    );
    cleanup(&f.state).await;
    assert!(f.request(&format!("/v1/tasks/{id}"), None).await.1["result"].is_null());
    assert!(!f.state.temp_dir.join(id).exists());
}
#[tokio::test]
async fn oversized_results_are_rejected_before_writing() {
    let f = Fixture::new();
    let id = f.create("tts", 0).await;
    let task = f.claim("tts").await;
    assert_eq!(
        f.upload(&task, &vec![0; 1025], None).await.0,
        StatusCode::PAYLOAD_TOO_LARGE
    );
    assert!(!f.state.temp_dir.join(id).exists());
}
#[test]
fn filenames_reject_paths_and_long_unicode_without_panicking() {
    assert_eq!(sanitize_filename(Some("result.zip")).unwrap(), "result.zip");
    assert!(sanitize_filename(Some("../result.zip")).is_err());
    assert!(sanitize_filename(Some("a\\b.zip")).is_err());
    assert!(sanitize_filename(Some(&"书".repeat(100))).is_err());
}

#[tokio::test]
async fn cleanup_reaps_crash_leftovers_but_preserves_active_uploads() {
    let f = Fixture::new();
    let id = f.create("tts", 0).await;
    let _task = f.claim("tts").await;
    let folder = f.state.temp_dir.join(&id);
    std::fs::create_dir(&folder).unwrap();
    let old = folder.join("aborted.part");
    std::fs::write(&old, b"unfinished").unwrap();
    let times = std::fs::FileTimes::new()
        .set_modified(SystemTime::now() - std::time::Duration::from_secs(7200));
    std::fs::File::options()
        .write(true)
        .open(&old)
        .unwrap()
        .set_times(times)
        .unwrap();
    cleanup(&f.state).await;
    assert!(old.exists());
    f.expire(&id);
    cleanup(&f.state).await;
    assert!(!old.exists());
    assert!(!folder.exists());
}

#[tokio::test]
async fn task_listing_is_paginated_filterable_and_omits_payloads() {
    let f = Fixture::new();
    let first = f.create("tts", 0).await;
    f.create("thumbnail", 0).await;
    let (_,newest)=f.request("/v1/tasks",Some(json!({"kind":"tts","payload":{"large":"novel text"},"metadata":{"title":"book chapter"}}))).await;
    let (status, page) = f.request("/v1/tasks?kind=tts&limit=1", None).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(page["items"][0]["id"], newest["id"]);
    assert_eq!(page["items"][0]["metadata"]["title"], "book chapter");
    assert!(page["items"][0].get("payload").is_none());
    assert!(page["items"][0].get("lease_token").is_none());
    let before = page["next_cursor"].as_i64().unwrap();
    let (_, second) = f
        .request(&format!("/v1/tasks?kind=tts&limit=1&before={before}"), None)
        .await;
    assert_eq!(second["items"][0]["id"], first);
    assert!(second["next_cursor"].is_null());
    assert_eq!(
        f.request("/v1/tasks?kind=tts&state=running", None).await.1["items"],
        json!([])
    );
    assert_eq!(
        f.request(
            &format!("/v1/tasks/{}", newest["id"].as_str().unwrap()),
            None
        )
        .await
        .1["payload"]["large"],
        "novel text"
    );
}

#[test]
fn metadata_migration_is_idempotent_for_existing_databases() {
    let db = Connection::open_in_memory().unwrap();
    init_db(&db).unwrap();
    db.execute("ALTER TABLE tasks DROP COLUMN metadata_json", [])
        .unwrap();
    init_db(&db).unwrap();
    init_db(&db).unwrap();
    let count: i64 = db
        .query_row(
            "SELECT count(*) FROM pragma_table_info('tasks') WHERE name='metadata_json'",
            [],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(count, 1);
}

#[tokio::test]
async fn claim_with_wait_returns_pending_task_without_delay() {
    let f = Fixture::new();
    let id = f.create("content.fetch", 0).await;
    let started = std::time::Instant::now();
    let (status, body) = f
        .request(
            "/v1/tasks/claim",
            Some(json!({"worker_id":"worker-1","kinds":["content.fetch"],"wait_seconds":20})),
        )
        .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["task"]["id"], id);
    assert!(
        started.elapsed() < std::time::Duration::from_millis(800),
        "有任务时应立即返回"
    );
}

#[tokio::test]
async fn claim_with_wait_times_out_on_empty_queue() {
    let f = Fixture::new();
    let started = std::time::Instant::now();
    let (status, body) = f
        .request(
            "/v1/tasks/claim",
            Some(json!({"worker_id":"worker-1","kinds":["content.fetch"],"wait_seconds":1})),
        )
        .await;
    assert_eq!(status, StatusCode::OK);
    assert!(body["task"].is_null());
    assert!(
        started.elapsed() >= std::time::Duration::from_millis(900),
        "无任务时应挂起接近 wait_seconds"
    );
    assert!(
        started.elapsed() < std::time::Duration::from_secs(3),
        "不应超过请求的等待时间太多"
    );
}

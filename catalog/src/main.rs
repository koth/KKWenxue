//! shanye-catalog：速读谷书籍元信息采集 + 只读目录 API。
//!
//! 用法：
//!   shanye-catalog bootstrap  [--pages N] [--no-details]   全量首爬
//!   shanye-catalog incremental [--pages N] [--quiet K]     增量同步
//!   shanye-catalog backfill-manifests [--limit N]          旧库章节目录回填
//!   shanye-catalog serve                                   只读 API
//!   shanye-catalog stats                                   打印库统计

mod api;
mod db;
mod parsers;
mod sources;

use std::sync::Arc;
use std::time::Duration;

use reqwest::redirect::Policy;
use reqwest::Client;
use tokio::sync::Mutex;
use tokio::time::Instant;

const DEFAULT_BASE: &str = "https://www.suduguu.com";

fn source_base() -> String {
    std::env::var("SHANYE_CATALOG_SOURCE")
        .unwrap_or_else(|_| DEFAULT_BASE.into())
        .trim_end_matches('/')
        .to_owned()
}

fn source_adapter() -> Box<dyn sources::SourceAdapter> {
    let key = std::env::var("SHANYE_CATALOG_SOURCE_KEY").unwrap_or_else(|_| "suduguu".into());
    sources::built_in_adapters()
        .into_iter()
        .find(|adapter| adapter.source_key() == key)
        .unwrap_or_else(|| {
            eprintln!("未知来源 {key}，回退到 suduguu");
            Box::new(sources::SuduguuAdapter)
        })
}

struct Fetcher {
    client: Client,
    next: Mutex<Instant>,
    gap: Duration,
}

impl Fetcher {
    fn new(rate_per_sec: f64) -> Self {
        let client = Client::builder()
            .http1_only()
            .user_agent("shanye-catalog/0.1 (+ops@shanye.local)")
            .redirect(Policy::none())
            .timeout(Duration::from_secs(30))
            .build()
            .expect("build http client");
        let gap = Duration::from_secs_f64(1.0 / rate_per_sec.max(0.2));
        Self {
            client,
            next: Mutex::new(Instant::now()),
            gap,
        }
    }

    /// 全局限速：所有请求共用一个时间槽，外加随机抖动。
    async fn acquire(&self) {
        let mut next = self.next.lock().await;
        let now = Instant::now();
        let at = (*next).max(now);
        *next = at + self.gap + Duration::from_millis((now.elapsed().subsec_millis() % 320) as u64);
        drop(next);
        if at > now {
            tokio::time::sleep_until(at).await;
        }
    }

    async fn get(&self, url: &str) -> Result<(u16, String, String), String> {
        let mut last_error = String::new();
        for attempt in 0..3 {
            self.acquire().await;
            match self.client.get(url).send().await {
                Ok(resp) => {
                    let code = resp.status().as_u16();
                    let final_url = resp.url().to_string();
                    match resp.text().await {
                        Ok(body) => {
                            if attempt < 2 && (code == 429 || (500..600).contains(&code)) {
                                tokio::time::sleep(Duration::from_millis(250 * (1 << attempt)))
                                    .await;
                                continue;
                            }
                            return Ok((code, final_url, body));
                        }
                        Err(error) => last_error = error.to_string(),
                    }
                }
                Err(error) => last_error = error.to_string(),
            }
            if attempt < 2 {
                tokio::time::sleep(Duration::from_millis(250 * (1 << attempt))).await;
            }
        }
        Err(last_error)
    }
}

fn now_secs() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

fn arg_value(args: &[String], name: &str) -> Option<String> {
    args.iter()
        .position(|a| a == name)
        .and_then(|i| args.get(i + 1))
        .cloned()
}

fn flag(args: &[String], name: &str) -> bool {
    args.iter().any(|a| a == name)
}

fn source_error_code(kind: sources::SourceErrorKind) -> &'static str {
    match kind {
        sources::SourceErrorKind::Redirect => "redirect",
        sources::SourceErrorKind::NotFound => "not_found",
        sources::SourceErrorKind::EmptyResponse => "empty",
        sources::SourceErrorKind::InvalidBook => "invalid_book",
        sources::SourceErrorKind::InvalidDirectory => "invalid_directory",
        sources::SourceErrorKind::DuplicateChapter => "duplicate_chapter",
        sources::SourceErrorKind::Parse => "parse",
        sources::SourceErrorKind::Transport => "transport",
    }
}

async fn cmd_preheat(args: &[String]) -> Result<(), String> {
    let limit: usize = arg_value(args, "--limit")
        .and_then(|v| v.parse().ok())
        .unwrap_or(200)
        .clamp(1, 200);
    let pages: usize = arg_value(args, "--pages")
        .and_then(|v| v.parse().ok())
        .unwrap_or(limit.div_ceil(10));
    let db_path = std::env::var("SHANYE_CATALOG_DB").unwrap_or_else(|_| "./catalog.sqlite".into());
    let relay_url = std::env::var("SHANYE_RELAY_URL")
        .map_err(|_| "SHANYE_RELAY_URL 未配置".to_string())?
        .trim_end_matches('/')
        .to_owned();
    let token =
        std::env::var("SHANYE_RELAY_TOKEN").map_err(|_| "SHANYE_RELAY_TOKEN 未配置".to_string())?;
    if token.trim().is_empty() {
        return Err("SHANYE_RELAY_TOKEN 为空".into());
    }
    let client = Client::builder()
        .http1_only()
        .timeout(Duration::from_secs(30))
        .build()
        .map_err(|e| e.to_string())?;
    let base = source_base();
    let selector = scraper::Selector::parse("div.item h3 a[href]").map_err(|e| e.to_string())?;
    let mut ids = Vec::new();
    for page in 1..=pages {
        let url = if page == 1 {
            format!("{base}/paihang/")
        } else {
            format!("{base}/paihang/{page}.html")
        };
        let response = client.get(url).send().await.map_err(|e| e.to_string())?;
        if !response.status().is_success() {
            return Err(format!("榜单 HTTP {}", response.status()));
        }
        let html = response.text().await.map_err(|e| e.to_string())?;
        for element in scraper::Html::parse_document(&html).select(&selector) {
            let Some(href) = element.value().attr("href") else {
                continue;
            };
            let raw = href.trim_matches('/');
            if let Ok(id) = raw.parse::<i64>() {
                if !ids.contains(&id) {
                    ids.push(id);
                }
                if ids.len() >= limit {
                    break;
                }
            }
        }
        if ids.len() >= limit {
            break;
        }
        tokio::time::sleep(Duration::from_millis(500)).await;
    }
    let conn = db::open(&db_path).map_err(|e| e.to_string())?;
    eprintln!(
        "榜单解析到 {} 本，开始按 2 req/s 创建 content.fetch",
        ids.len()
    );
    for (index, id) in ids.iter().enumerate() {
        let Some(book) = db::get_book(&conn, *id).map_err(|e| e.to_string())? else {
            continue;
        };
        let payload = serde_json::json!({"bookId": id, "latestChapterId": book.latest_chapter_id, "title": book.title, "author": book.author});
        let body = serde_json::json!({"kind":"content.fetch", "payload":payload, "dedupe_key":format!("content.fetch:{}:{}", id, book.latest_chapter_id), "priority":10, "max_attempts":3});
        let response = client
            .post(format!("{relay_url}/v1/tasks"))
            .header("X-Relay-Token", &token)
            .json(&body)
            .send()
            .await
            .map_err(|e| e.to_string())?;
        if !response.status().is_success() {
            eprintln!("  {id} 创建失败 HTTP {}", response.status());
        } else if index % 10 == 0 {
            eprintln!("  已创建 {}/{}", index + 1, ids.len());
        }
        tokio::time::sleep(Duration::from_millis(500)).await;
    }
    eprintln!("预热任务创建完成：{} 本", ids.len());
    Ok(())
}

/// 扫 /zuixin/ 列表页，返回 (bookId, 最新章id) 与扫描页数。越界（302）或空页即停。
async fn scan_updates(
    conn: &rusqlite::Connection,
    fetcher: &Fetcher,
    adapter: &dyn sources::SourceAdapter,
    max_pages: usize,
    quiet_pages: Option<usize>,
) -> Result<(usize, usize), String> {
    let base = std::env::var("SHANYE_CATALOG_SOURCE")
        .unwrap_or_else(|_| adapter.base_url().into())
        .trim_end_matches('/')
        .to_owned();
    db::ensure_source(
        conn,
        adapter.source_key(),
        adapter.display_name(),
        &base,
        if adapter.source_key() == "sudugu520" {
            20
        } else {
            10
        },
    )
    .map_err(|e| format!("ensure source failed: {e}"))?;
    let known = db::known_latest_chapters_for_source(conn, adapter.source_key())
        .map_err(|e| format!("load known source chapters failed: {e}"))?;
    let mut quiet = 0usize;
    let mut pages = 0usize;
    let mut seen = 0usize;

    for page in 1..=max_pages {
        let url = format!("{base}{}", adapter.list_path(page));
        let (code, final_url, body) = fetcher
            .get(&url)
            .await
            .map_err(|e| format!("fetch list page {page} failed: {e}"))?;
        if let Err(error) = adapter.validate_response(code, &final_url, &body) {
            let _ = db::record_source_error(
                conn,
                adapter.source_key(),
                None,
                &format!("source_{}", source_error_code(error.kind)),
                &error.message,
            );
            if matches!(error.kind, sources::SourceErrorKind::NotFound)
                || (300..400).contains(&code)
            {
                eprintln!(
                    "  来源 {} 第 {page} 页停止：{}",
                    adapter.source_key(),
                    error.message
                );
                break;
            }
            let _ = db::record_source_health(
                conn,
                adapter.source_key(),
                false,
                Some(source_error_code(error.kind)),
                Some(&error.message),
            );
            return Err(error.message);
        }
        let items = match adapter.parse_list(&body) {
            Ok(items) => items,
            Err(error) => {
                let _ = db::record_source_error(
                    conn,
                    adapter.source_key(),
                    None,
                    &format!("source_{}", source_error_code(error.kind)),
                    &error.message,
                );
                return Err(error.message);
            }
        };
        db::set_source_cursor(conn, adapter.source_key(), Some(&page.to_string()), None)
            .map_err(|e| format!("save list cursor page {page} failed: {e}"))?;
        pages = page;
        let mut changed = 0usize;
        for item in &items {
            seen += 1;
            let item_changed = known.get(&item.external_book_id).copied()
                != item
                    .latest_external_chapter_id
                    .as_deref()
                    .and_then(|value| value.parse::<i64>().ok());
            if item_changed {
                changed += 1;
            }
            let book_id = match db::upsert_source_list(conn, adapter.source_key(), item) {
                Ok(book_id) => book_id,
                Err(error) => {
                    let message = format!(
                        "upsert source list {} ({}) failed: {error}",
                        item.external_book_id, item.title
                    );
                    let _ = db::record_source_error(
                        conn,
                        adapter.source_key(),
                        Some(&item.external_book_id),
                        "source_list_persist",
                        &message,
                    );
                    eprintln!("  {message}，跳过此条继续");
                    continue;
                }
            };
            if item_changed {
                db::mark_recent_update(conn, book_id)
                    .map_err(|e| format!("mark book update {book_id} failed: {e}"))?;
                if let Some(binding) =
                    db::find_external_book(conn, adapter.source_key(), &item.external_book_id)
                        .map_err(|e| {
                            format!("load source binding {} failed: {e}", item.external_book_id)
                        })?
                {
                    db::mark_source_recent_update(conn, binding.book_source_id).map_err(|e| {
                        format!("mark source update {} failed: {e}", binding.book_source_id)
                    })?;
                }
            }
        }
        if let Some(quiet_limit) = quiet_pages {
            if changed == 0 {
                quiet += 1;
                if quiet >= quiet_limit {
                    eprintln!("  连续 {quiet} 页无变化，已追上");
                    break;
                }
            } else {
                quiet = 0;
            }
        }
        if page % 50 == 0 {
            eprintln!("  列表已扫 {page} 页 / {seen} 本");
        }
    }
    db::record_source_health(conn, adapter.source_key(), true, None, None)
        .map_err(|e| format!("record source health failed: {e}"))?;
    Ok((pages, seen))
}

/// 补抓详情：多 worker 并发，全局限速由 Fetcher 统一控制；每个 worker 用自己的连接（WAL 多写）。
async fn fetch_details(
    db_path: &str,
    fetcher: Arc<Fetcher>,
    adapter: Arc<dyn sources::SourceAdapter>,
    ids: Vec<String>,
    workers: usize,
) -> Result<(usize, usize), String> {
    let base = std::env::var("SHANYE_CATALOG_SOURCE")
        .unwrap_or_else(|_| adapter.base_url().into())
        .trim_end_matches('/')
        .to_owned();
    let source_key = adapter.source_key().to_string();
    let ids = Arc::new(ids);
    let next = Arc::new(std::sync::atomic::AtomicUsize::new(0));
    let mut handles = Vec::new();

    for _ in 0..workers.max(1) {
        let db_path = db_path.to_string();
        let fetcher = fetcher.clone();
        let ids = ids.clone();
        let next = next.clone();
        let base = base.clone();
        let adapter = adapter.clone();
        let source_key = source_key.clone();
        handles.push(tokio::spawn(async move {
            let conn = db::open_existing(&db_path)
                .map_err(|e| format!("worker database open failed: {e}"))?;
            let (mut ok, mut failed) = (0usize, 0usize);
            loop {
                let i = next.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                let Some(id) = ids.get(i).cloned() else { break };
                let url = format!("{base}{}", adapter.detail_path(&id).map_err(|e| e.message)?);
                match fetcher.get(&url).await {
                    Ok((code, final_url, body))
                        if adapter.validate_response(code, &final_url, &body).is_ok() =>
                    {
                        match adapter.parse_detail(&body, &id) {
                            Ok(mut d) => {
                                let mut next_page = adapter.next_directory_page(&body);
                                let mut seen = d
                                    .chapters
                                    .iter()
                                    .map(|chapter| chapter.external_chapter_id.clone())
                                    .collect::<std::collections::HashSet<_>>();
                                let mut pages = 0usize;
                                let mut complete = true;
                                let mut visited = std::collections::HashSet::new();
                                while let Some(page) = next_page {
                                    if page <= 1 || page > 100 || !visited.insert(page) {
                                        complete = false;
                                        break;
                                    }
                                    let page_url = format!(
                                        "{base}{}",
                                        adapter
                                            .directory_page_path(&id, page)
                                            .map_err(|e| e.message)?
                                    );
                                    match fetcher.get(&page_url).await {
                                        Ok((code, final_url, page_body))
                                            if adapter
                                                .validate_response(code, &final_url, &page_body)
                                                .is_ok() =>
                                        {
                                            if let Ok(page_detail) =
                                                adapter.parse_detail(&page_body, &id)
                                            {
                                                for chapter in page_detail.chapters {
                                                    if seen
                                                        .insert(chapter.external_chapter_id.clone())
                                                    {
                                                        let mut chapter = chapter;
                                                        // `parse_detail` numbers each directory
                                                        // page from zero. Rebase appended pages
                                                        // to one global ordinal before persisting,
                                                        // otherwise page 2 is matched against the
                                                        // beginning of the canonical directory.
                                                        chapter.ordinal = d.chapters.len();
                                                        d.chapters.push(chapter);
                                                    }
                                                }
                                                next_page = adapter.next_directory_page(&page_body);
                                                pages += 1;
                                            } else {
                                                complete = false;
                                                break;
                                            }
                                        }
                                        Ok((code, _, _)) => {
                                            let _ = db::record_source_error(
                                                &conn,
                                                &source_key,
                                                Some(&id),
                                                "source_directory_error",
                                                &format!("目录 {id}/p-{page} HTTP {code}"),
                                            );
                                            eprintln!("  目录 {id}/p-{page} HTTP {code}");
                                            complete = false;
                                            break;
                                        }
                                        Err(error) => {
                                            let _ = db::record_source_error(
                                                &conn,
                                                &source_key,
                                                Some(&id),
                                                "source_directory_transport",
                                                &error,
                                            );
                                            eprintln!("  目录 {id}/p-{page} 失败：{error}");
                                            complete = false;
                                            break;
                                        }
                                    }
                                    if pages >= 100 && next_page.is_some() {
                                        complete = false;
                                        break;
                                    }
                                }
                                if complete
                                    && db::upsert_source_detail(&conn, &source_key, &d).is_ok()
                                {
                                    let _ =
                                        db::set_source_cursor(&conn, &source_key, None, Some(&id));
                                    let _ = db::record_source_health(
                                        &conn,
                                        &source_key,
                                        true,
                                        None,
                                        None,
                                    );
                                    ok += 1;
                                } else {
                                    failed += 1;
                                    let _ = db::record_source_health(
                                        &conn,
                                        &source_key,
                                        false,
                                        Some("persist"),
                                        Some("目录写入失败"),
                                    );
                                }
                            }
                            Err(error) => {
                                failed += 1;
                                let _ = db::record_source_error(
                                    &conn,
                                    &source_key,
                                    Some(&id),
                                    &format!("source_{}", source_error_code(error.kind)),
                                    &error.message,
                                );
                                let _ = db::record_source_health(
                                    &conn,
                                    &source_key,
                                    false,
                                    Some("parse"),
                                    Some(&error.message),
                                );
                            }
                        }
                    }
                    Ok((code, final_url, body)) => {
                        failed += 1;
                        let event = if (300..400).contains(&code) {
                            "source_redirect"
                        } else if code == 404 {
                            "source_not_found"
                        } else {
                            "source_http_error"
                        };
                        let _ = db::record_source_error(
                            &conn,
                            &source_key,
                            Some(&id),
                            event,
                            &format!(
                                "{}: {}",
                                final_url,
                                body.chars().take(120).collect::<String>()
                            ),
                        );
                        let _ = db::record_source_health(
                            &conn,
                            &source_key,
                            false,
                            Some(&code.to_string()),
                            Some(&format!(
                                "{}: {}",
                                final_url,
                                body.chars().take(120).collect::<String>()
                            )),
                        );
                        eprintln!("  详情 {id} HTTP {code}");
                    }
                    Err(e) => {
                        failed += 1;
                        let _ = db::record_source_error(
                            &conn,
                            &source_key,
                            Some(&id),
                            "source_transport",
                            &e,
                        );
                        let _ = db::record_source_health(
                            &conn,
                            &source_key,
                            false,
                            Some("transport"),
                            Some(&e),
                        );
                        eprintln!("  详情 {id} 失败：{e}");
                    }
                }
                let done = ok + failed;
                if done % 100 == 0 {
                    eprintln!("  详情 {done}/{}（失败 {failed}）", ids.len());
                }
            }
            Ok::<(usize, usize), String>((ok, failed))
        }));
    }

    let (mut ok, mut failed) = (0usize, 0usize);
    for h in handles {
        let (o, f) = h.await.map_err(|e| e.to_string())??;
        ok += o;
        failed += f;
    }
    Ok((ok, failed))
}

fn workers_env() -> usize {
    std::env::var("SHANYE_CATALOG_WORKERS")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(4)
}

async fn cmd_bootstrap(args: &[String]) -> Result<(), String> {
    let db_path = std::env::var("SHANYE_CATALOG_DB").unwrap_or_else(|_| "./catalog.sqlite".into());
    let rate: f64 = std::env::var("SHANYE_CATALOG_RATE")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(2.0);
    let max_pages: usize = arg_value(args, "--pages")
        .and_then(|v| v.parse().ok())
        .unwrap_or(600);
    let conn = db::open(&db_path).map_err(|e| e.to_string())?;
    let adapter: Arc<dyn sources::SourceAdapter> = source_adapter().into();
    let source_rate =
        db::source_rate_limit_ms(&conn, adapter.source_key()).map_err(|e| e.to_string())?;
    let fetcher = Arc::new(Fetcher::new(rate.min(1000.0 / source_rate.max(1) as f64)));
    let run = db::start_source_sync_run(&conn, "bootstrap", adapter.source_key())
        .map_err(|e| e.to_string())?;

    eprintln!("bootstrap: 扫描 /zuixin/ 最多 {max_pages} 页（{rate} req/s）");
    let (pages, seen) =
        scan_updates(&conn, fetcher.as_ref(), adapter.as_ref(), max_pages, None).await?;
    eprintln!("列表完成：{pages} 页 / {seen} 条");

    let (ok, failed) = if flag(args, "--no-details") {
        (0, 0)
    } else {
        let ids = db::pending_source_detail_ids(&conn, adapter.source_key(), 100_000)
            .map_err(|e| e.to_string())?;
        eprintln!("待抓详情 {} 本（{} 并发）", ids.len(), workers_env());
        drop(conn);
        let r = fetch_details(
            &db_path,
            fetcher.clone(),
            adapter.clone(),
            ids,
            workers_env(),
        )
        .await?;
        let conn = db::open(&db_path).map_err(|e| e.to_string())?;
        let total: i64 = conn
            .query_row("SELECT COUNT(*) FROM books", [], |r| r.get(0))
            .map_err(|e| e.to_string())?;
        db::finish_sync_run(
            &conn,
            run,
            pages as i64,
            seen as i64,
            0,
            r.0 as i64,
            r.1 as i64,
            None,
        )
        .map_err(|e| e.to_string())?;
        eprintln!(
            "bootstrap 完成：库内 {total} 本，详情成功 {}，失败 {}",
            r.0, r.1
        );
        return Ok(());
    };

    let total: i64 = conn
        .query_row("SELECT COUNT(*) FROM books", [], |r| r.get(0))
        .map_err(|e| e.to_string())?;
    db::finish_sync_run(
        &conn,
        run,
        pages as i64,
        seen as i64,
        0,
        ok as i64,
        failed as i64,
        None,
    )
    .map_err(|e| e.to_string())?;
    eprintln!("bootstrap 完成：库内 {total} 本，详情成功 {ok}，失败 {failed}");
    Ok(())
}

async fn cmd_incremental(args: &[String]) -> Result<(), String> {
    let db_path = std::env::var("SHANYE_CATALOG_DB").unwrap_or_else(|_| "./catalog.sqlite".into());
    let rate: f64 = std::env::var("SHANYE_CATALOG_RATE")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(2.0);
    let max_pages: usize = arg_value(args, "--pages")
        .and_then(|v| v.parse().ok())
        .unwrap_or(250);
    let quiet: usize = arg_value(args, "--quiet")
        .and_then(|v| v.parse().ok())
        .unwrap_or(3);
    let conn = db::open(&db_path).map_err(|e| format!("initial database open failed: {e}"))?;
    let adapter: Arc<dyn sources::SourceAdapter> = source_adapter().into();
    let source_rate = db::source_rate_limit_ms(&conn, adapter.source_key())
        .map_err(|e| format!("source rate lookup failed: {e}"))?;
    let fetcher = Arc::new(Fetcher::new(rate.min(1000.0 / source_rate.max(1) as f64)));
    let run = db::start_source_sync_run(&conn, "incremental", adapter.source_key())
        .map_err(|e| format!("start sync run failed: {e}"))?;

    let before = db::known_latest_chapters_for_source(&conn, adapter.source_key())
        .map_err(|e| format!("load source cursor failed: {e}"))?;
    let (pages, seen) = scan_updates(
        &conn,
        fetcher.as_ref(),
        adapter.as_ref(),
        max_pages,
        Some(quiet),
    )
    .await
    .map_err(|e| format!("scan source updates failed: {e}"))?;
    let requested = db::pending_source_refreshes(&conn, adapter.source_key(), 1000)
        .map_err(|e| format!("load pending source refreshes failed: {e}"))?;
    let after = db::known_latest_chapters_for_source(&conn, adapter.source_key())
        .map_err(|e| format!("load updated source cursor failed: {e}"))?;
    let mut changed_external: Vec<String> = after
        .iter()
        .filter(|(id, cid)| before.get(*id).map(|old| old != *cid).unwrap_or(true))
        .map(|(id, _)| id.clone())
        .collect();
    let requested_book_ids = requested
        .iter()
        .map(|(_, book_id)| *book_id)
        .collect::<Vec<_>>();
    let requested_external =
        db::source_external_ids_for_books(&conn, adapter.source_key(), &requested_book_ids)
            .map_err(|e| format!("load requested source IDs failed: {e}"))?;
    for id in requested_external {
        if !changed_external.contains(&id) {
            changed_external.push(id);
        }
    }
    eprintln!(
        "增量：{pages} 页 / {seen} 条，变化 {} 本",
        changed_external.len()
    );

    drop(conn);
    let (ok, failed) = fetch_details(
        &db_path,
        fetcher.clone(),
        adapter.clone(),
        changed_external,
        workers_env(),
    )
    .await
    .map_err(|e| format!("fetch source details failed: {e}"))?;
    let conn =
        db::open(&db_path).map_err(|e| format!("reopen database after details failed: {e}"))?;
    db::clear_source_refreshes(
        &conn,
        adapter.source_key(),
        &requested
            .iter()
            .map(|(source_id, _)| *source_id)
            .collect::<Vec<_>>(),
    )
    .map_err(|e| format!("clear source refreshes failed: {e}"))?;
    db::finish_sync_run(
        &conn,
        run,
        pages as i64,
        seen as i64,
        ok as i64,
        ok as i64,
        failed as i64,
        None,
    )
    .map_err(|e| format!("finish sync run failed: {e}"))?;
    eprintln!("增量完成：更新 {ok} 本，失败 {failed}");
    Ok(())
}

/// Fetch one source book by external ID, then explicitly bind it to an
/// existing canonical catalog book. This avoids silently fuzzy-merging a
/// source when the book is not present in the source's recent-updates pages.
async fn cmd_bind_source(args: &[String]) -> Result<(), String> {
    let book_id = arg_value(args, "--book-id")
        .and_then(|value| value.parse::<i64>().ok())
        .ok_or_else(|| "bind-source 需要 --book-id".to_string())?;
    let external_book_id = arg_value(args, "--external-book-id")
        .filter(|value| !value.trim().is_empty())
        .ok_or_else(|| "bind-source 需要 --external-book-id".to_string())?;
    let db_path = std::env::var("SHANYE_CATALOG_DB").unwrap_or_else(|_| "./catalog.sqlite".into());
    let rate: f64 = std::env::var("SHANYE_CATALOG_RATE")
        .ok()
        .and_then(|value| value.parse().ok())
        .unwrap_or(1.0);
    let conn = db::open(&db_path).map_err(|e| e.to_string())?;
    let adapter: Arc<dyn sources::SourceAdapter> = source_adapter().into();
    let base = source_base();
    db::ensure_source(
        &conn,
        adapter.source_key(),
        adapter.display_name(),
        &base,
        if adapter.source_key() == "sudugu520" {
            20
        } else {
            10
        },
    )
    .map_err(|e| format!("ensure source failed: {e}"))?;
    let source_rate = db::source_rate_limit_ms(&conn, adapter.source_key())
        .map_err(|e| format!("source rate lookup failed: {e}"))?;
    let fetcher = Arc::new(Fetcher::new(rate.min(1000.0 / source_rate.max(1) as f64)));
    db::reset_source_binding(&conn, adapter.source_key(), &external_book_id)
        .map_err(|e| format!("reset source binding failed: {e}"))?;
    drop(conn);

    let (ok, failed) = fetch_details(
        &db_path,
        fetcher,
        adapter.clone(),
        vec![external_book_id.clone()],
        1,
    )
    .await?;
    if ok != 1 || failed != 0 {
        return Err(format!("抓取来源书籍失败：成功 {ok}，失败 {failed}"));
    }

    let conn = db::open(&db_path).map_err(|e| e.to_string())?;
    if !db::resolve_confirmed_source(
        &conn,
        book_id,
        adapter.source_key(),
        &external_book_id,
        "operator",
    )
    .map_err(|e| format!("确认来源绑定失败: {e}"))?
    {
        return Err(format!(
            "来源 {} /{} 未能绑定到内部书籍 {}",
            adapter.source_key(),
            external_book_id,
            book_id
        ));
    }
    eprintln!(
        "来源绑定完成：{} /{} -> 内部书籍 {}",
        adapter.source_key(),
        external_book_id,
        book_id
    );
    Ok(())
}

async fn cmd_backfill_manifests(args: &[String]) -> Result<(), String> {
    let db_path = std::env::var("SHANYE_CATALOG_DB").unwrap_or_else(|_| "./catalog.sqlite".into());
    let rate: f64 = std::env::var("SHANYE_CATALOG_RATE")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(2.0);
    let limit: usize = arg_value(args, "--limit")
        .and_then(|v| v.parse().ok())
        .unwrap_or(100_000)
        .clamp(1, 100_000);
    let conn = db::open(&db_path).map_err(|e| e.to_string())?;
    let adapter: Arc<dyn sources::SourceAdapter> = source_adapter().into();
    let ids = db::pending_source_manifest_ids(&conn, adapter.source_key(), limit)
        .map_err(|e| e.to_string())?;
    if ids.is_empty() {
        eprintln!("章节目录已全部回填");
        return Ok(());
    }
    eprintln!("待回填章节目录 {} 本（{} 并发）", ids.len(), workers_env());
    drop(conn);
    let conn = db::open(&db_path).map_err(|e| e.to_string())?;
    let source_rate =
        db::source_rate_limit_ms(&conn, adapter.source_key()).map_err(|e| e.to_string())?;
    drop(conn);
    let fetcher = Arc::new(Fetcher::new(rate.min(1000.0 / source_rate.max(1) as f64)));
    let (ok, failed) = fetch_details(&db_path, fetcher, adapter, ids, workers_env()).await?;
    eprintln!("章节目录回填完成：成功 {ok} 本，失败 {failed} 本");
    Ok(())
}

async fn cmd_serve() -> Result<(), String> {
    let db_path = std::env::var("SHANYE_CATALOG_DB").unwrap_or_else(|_| "./catalog.sqlite".into());
    let bind = std::env::var("SHANYE_CATALOG_BIND").unwrap_or_else(|_| "127.0.0.1:17872".into());
    let conn = db::open(&db_path).map_err(|e| e.to_string())?;
    let relay_url =
        std::env::var("SHANYE_RELAY_URL").unwrap_or_else(|_| "http://127.0.0.1:17870".into());
    let relay_token = std::env::var("SHANYE_RELAY_TOKEN").unwrap_or_default();
    let relay_client = reqwest::Client::builder()
        .http1_only()
        .timeout(Duration::from_secs(30))
        .build()
        .map_err(|e| e.to_string())?;
    let state = api::AppState {
        db: Arc::new(Mutex::new(conn)),
        relay_url: relay_url.trim_end_matches('/').to_owned(),
        relay_token,
        relay_client,
        source_url: std::env::var("SHANYE_CATALOG_SOURCE")
            .unwrap_or_else(|_| "https://www.suduguu.com".into()),
        chapter_cache: Arc::new(Mutex::new(api::ChapterCache::default())),
        chapter_fetches: Arc::new(Mutex::new(std::collections::HashMap::new())),
    };
    tokio::spawn(api::run_prefetch(state.clone()));
    let app = api::router(state);
    let listener = tokio::net::TcpListener::bind(&bind)
        .await
        .map_err(|e| e.to_string())?;
    eprintln!("catalog api 监听 {bind}");
    axum::serve(listener, app).await.map_err(|e| e.to_string())
}

fn cmd_stats() -> Result<(), String> {
    let db_path = std::env::var("SHANYE_CATALOG_DB").unwrap_or_else(|_| "./catalog.sqlite".into());
    let conn = db::open(&db_path).map_err(|e| e.to_string())?;
    let s = db::stats(&conn).map_err(|e| e.to_string())?;
    println!("{}", serde_json::to_string_pretty(&s).unwrap_or_default());
    Ok(())
}

#[tokio::main]
async fn main() {
    let args: Vec<String> = std::env::args().collect();
    let cmd = args.get(1).map(|s| s.as_str()).unwrap_or("help");
    let result = match cmd {
        "bootstrap" => cmd_bootstrap(&args).await,
        "incremental" => cmd_incremental(&args).await,
        "bind-source" => cmd_bind_source(&args).await,
        "backfill-manifests" => cmd_backfill_manifests(&args).await,
        "preheat" => cmd_preheat(&args).await,
        "serve" => cmd_serve().await,
        "stats" => cmd_stats(),
        _ => {
            eprintln!("用法: shanye-catalog <bootstrap|incremental|bind-source|backfill-manifests|preheat|serve|stats> [选项]");
            eprintln!("环境变量: SHANYE_CATALOG_DB, SHANYE_CATALOG_BIND, SHANYE_CATALOG_RATE, SHANYE_RELAY_URL, SHANYE_RELAY_TOKEN");
            std::process::exit(2);
        }
    };
    if let Err(e) = result {
        eprintln!("错误: {e}");
        std::process::exit(1);
    }
    let _ = now_secs();
}

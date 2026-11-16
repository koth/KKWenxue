//! shanye-catalog：速读谷书籍元信息采集 + 只读目录 API。
//!
//! 用法：
//!   shanye-catalog bootstrap  [--pages N] [--no-details]   全量首爬
//!   shanye-catalog incremental [--pages N] [--quiet K]     增量同步
//!   shanye-catalog serve                                   只读 API
//!   shanye-catalog stats                                   打印库统计

mod api;
mod db;
mod parsers;

use std::sync::Arc;
use std::time::Duration;

use reqwest::redirect::Policy;
use reqwest::Client;
use tokio::sync::Mutex;
use tokio::time::Instant;

const BASE: &str = "https://www.suduguu.com";

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

    async fn get(&self, url: &str) -> Result<(u16, String), String> {
        self.acquire().await;
        let resp = self
            .client
            .get(url)
            .send()
            .await
            .map_err(|e| e.to_string())?;
        let code = resp.status().as_u16();
        let body = resp.text().await.map_err(|e| e.to_string())?;
        Ok((code, body))
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
    let selector = scraper::Selector::parse("div.item h3 a[href]").map_err(|e| e.to_string())?;
    let mut ids = Vec::new();
    for page in 1..=pages {
        let url = if page == 1 {
            format!("{BASE}/paihang/")
        } else {
            format!("{BASE}/paihang/{page}.html")
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
    max_pages: usize,
    quiet_pages: Option<usize>,
) -> Result<(usize, usize), String> {
    let known = db::known_latest_chapters(conn).map_err(|e| e.to_string())?;
    let mut quiet = 0usize;
    let mut pages = 0usize;
    let mut seen = 0usize;

    for page in 1..=max_pages {
        let url = if page == 1 {
            format!("{BASE}/zuixin/")
        } else {
            format!("{BASE}/zuixin/{page}.html")
        };
        let (code, body) = fetcher.get(&url).await?;
        if (300..400).contains(&code) {
            eprintln!("  第 {page} 页返回 {code}（越界或限流），停止");
            break;
        }
        if code != 200 {
            return Err(format!("列表页第 {page} 页 HTTP {code}"));
        }
        let items = parsers::parse_list(&body);
        if items.is_empty() {
            eprintln!("  第 {page} 页解析为空，停止（可能是改版）");
            break;
        }
        pages = page;
        let mut changed = 0usize;
        for item in &items {
            seen += 1;
            if known.get(&item.id) != Some(&item.latest_chapter_id) {
                changed += 1;
            }
            db::upsert_list(conn, item).map_err(|e| e.to_string())?;
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
    Ok((pages, seen))
}

/// 补抓详情：多 worker 并发，全局限速由 Fetcher 统一控制；每个 worker 用自己的连接（WAL 多写）。
async fn fetch_details(
    db_path: &str,
    fetcher: Arc<Fetcher>,
    ids: Vec<i64>,
    workers: usize,
) -> Result<(usize, usize), String> {
    let ids = Arc::new(ids);
    let next = Arc::new(std::sync::atomic::AtomicUsize::new(0));
    let mut handles = Vec::new();

    for _ in 0..workers.max(1) {
        let db_path = db_path.to_string();
        let fetcher = fetcher.clone();
        let ids = ids.clone();
        let next = next.clone();
        handles.push(tokio::spawn(async move {
            let conn = db::open(&db_path).map_err(|e| e.to_string())?;
            let (mut ok, mut failed) = (0usize, 0usize);
            loop {
                let i = next.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                let Some(id) = ids.get(i).copied() else { break };
                let url = format!("{BASE}/{id}/");
                match fetcher.get(&url).await {
                    Ok((200, body)) => match parsers::parse_detail(&body, id) {
                        Some(d) if db::upsert_detail(&conn, &d).is_ok() => ok += 1,
                        _ => {
                            failed += 1;
                            let _ = db::record_detail_failure(&conn, id);
                        }
                    },
                    Ok((code, _)) => {
                        failed += 1;
                        let _ = db::record_detail_failure(&conn, id);
                        eprintln!("  详情 {id} HTTP {code}");
                    }
                    Err(e) => {
                        failed += 1;
                        let _ = db::record_detail_failure(&conn, id);
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
    let fetcher = Arc::new(Fetcher::new(rate));
    let run = db::start_sync_run(&conn, "bootstrap").map_err(|e| e.to_string())?;

    eprintln!("bootstrap: 扫描 /zuixin/ 最多 {max_pages} 页（{rate} req/s）");
    let (pages, seen) = scan_updates(&conn, fetcher.as_ref(), max_pages, None).await?;
    eprintln!("列表完成：{pages} 页 / {seen} 条");

    let (ok, failed) = if flag(args, "--no-details") {
        (0, 0)
    } else {
        let ids = db::pending_detail_ids(&conn, 100_000).map_err(|e| e.to_string())?;
        eprintln!("待抓详情 {} 本（{} 并发）", ids.len(), workers_env());
        drop(conn);
        let r = fetch_details(&db_path, fetcher.clone(), ids, workers_env()).await?;
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
    let conn = db::open(&db_path).map_err(|e| e.to_string())?;
    let fetcher = Arc::new(Fetcher::new(rate));
    let run = db::start_sync_run(&conn, "incremental").map_err(|e| e.to_string())?;

    let before = db::known_latest_chapters(&conn).map_err(|e| e.to_string())?;
    let (pages, seen) = scan_updates(&conn, fetcher.as_ref(), max_pages, Some(quiet)).await?;
    let after = db::known_latest_chapters(&conn).map_err(|e| e.to_string())?;
    let changed: Vec<i64> = after
        .iter()
        .filter(|(id, cid)| before.get(id).map(|old| old != *cid).unwrap_or(true))
        .map(|(id, _)| *id)
        .collect();
    eprintln!("增量：{pages} 页 / {seen} 条，变化 {} 本", changed.len());

    drop(conn);
    let (ok, failed) =
        fetch_details(&db_path, fetcher.clone(), changed.clone(), workers_env()).await?;
    let conn = db::open(&db_path).map_err(|e| e.to_string())?;
    db::finish_sync_run(
        &conn,
        run,
        pages as i64,
        seen as i64,
        changed.len() as i64,
        ok as i64,
        failed as i64,
        None,
    )
    .map_err(|e| e.to_string())?;
    eprintln!("增量完成：更新 {ok} 本，失败 {failed}");
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
    };
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
        "preheat" => cmd_preheat(&args).await,
        "serve" => cmd_serve().await,
        "stats" => cmd_stats(),
        _ => {
            eprintln!("用法: shanye-catalog <bootstrap|incremental|preheat|serve|stats> [选项]");
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

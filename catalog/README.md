# shanye-catalog

速读谷（www.suduguu.com）书籍元信息采集 + 只读目录 API。Rust（axum + rusqlite + reqwest + scraper），单二进制，两种运行方式：

```sh
# 全量首爬（约 543 页列表 + 5,426 个详情页，2–3 req/s 下 30–50 分钟）
SHANYE_CATALOG_DB=/tmp/catalog.sqlite shanye-catalog bootstrap --pages 600

# 增量同步（扫 /zuixin/ 到静默；日常 10 分钟一跑，深扫一天一次）
shanye-catalog incremental --pages 250 --quiet 3

# 只读 API（默认 127.0.0.1:17872）
shanye-catalog serve

# 库统计
shanye-catalog stats
```

环境变量：

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `SHANYE_CATALOG_DB` | `./catalog.sqlite` | SQLite 路径（WAL） |
| `SHANYE_CATALOG_BIND` | `127.0.0.1:17872` | API 监听地址 |
| `SHANYE_CATALOG_RATE` | `2.0` | 全局请求速率（req/s） |
| `SHANYE_CATALOG_WORKERS` | `4` | 详情页并发 worker 数 |

## 数据

- 枚举源：`/zuixin/`（按更新时间倒序，10 本/页，543 页恰好覆盖全站 5,426 本）。
- 列表页给：bookId、书名、状态、分类、作者、最新章 id/标题。
- 详情页给：字数、状态、分类、作者、**绝对更新时间**、最新章节、目录（目录截断 999 条，不用来数章节）。
- 增量水位：每本书自己的 `latest_chapter_id`，连续 3 页无变化即认为追上。

## API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/healthz` | 存活 |
| GET | `/v1/catalog/stats` | 总数、分类分布、最近同步 |
| GET | `/v1/catalog/search?q=&limit=&cursor=` | 书名/作者 LIKE 搜索 |
| GET | `/v1/catalog/books?category=&status=&sort=updated\|words&limit=&cursor=` | 列表 |
| GET | `/v1/catalog/books/{id}` | 单本详情 |

## 测试

```sh
cargo test          # 解析器用 fixtures/ 下真实页面做断言
cargo clippy --all-targets -- -D warnings
```

## 部署

见 [deploy/README.md](deploy/README.md)：在 `emotts` 构建 release、`rsync` 到 `relay`，systemd 三个单元（serve / 10 分钟增量 / 每日深扫），nginx 加 `/v1/catalog/` 反代。

## 正文任务与预热

目录服务在启动时可配置 `SHANYE_RELAY_URL`、`SHANYE_RELAY_TOKEN`。正文接口为：

- `POST /v1/catalog/books/{id}/content`：创建或复用 `content.fetch`。
- `GET /v1/catalog/content/{taskId}`：查询抓取/打包状态；抓取完成后自动创建 `content.serve`。
- `GET /v1/catalog/content/{taskId}/download`：局域网不可达时申请 relay 中转并下载。

家里 worker 位于 `/h/work/shanye-content`，先下载并切片到 `books/`，再把 ZIP 放到 `packs/`；完成通知带 `192.168.3.32:17873` 的短期直连能力。App 会优先直连，只有失败才走 relay 上传。

榜单预热（在 relay 上执行，按 2 req/s 创建任务）：

```sh
SHANYE_CATALOG_DB=/var/lib/shanye-catalog/catalog.sqlite \
SHANYE_RELAY_URL=http://127.0.0.1:17870 SHANYE_RELAY_TOKEN=… \
/usr/local/bin/shanye-catalog preheat --limit 200
```

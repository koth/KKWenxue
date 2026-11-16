# 通用任务中转服务

外网侧使用独立的 Rust 程序 `shanye-task-relay`（Axum + SQLite）。它只处理任务类型、JSON payload、优先级、租约、进度和临时结果文件，不依赖 TTS、小说库、Python 或模型。不同 worker 通过 `kind` 分工，例如 `tts`、`transcode`、`thumbnail`。

```text
任务提交方 → Rust relay（外网）← 轮询 / 心跳 ← GPU / 其他 worker
                     ↑                        │
                     └── 完成通知（地址/哈希）──┘
                     ↓
              提交方查询 → 优先直连 worker 下载
                       → 不可达才申请中转 → worker 按需上传 → 下载
```

所有接口要求 `X-Relay-Token`。这是同一信任域内的服务间凭据；此版本不包含多租户用户权限或账单。建议只监听 loopback，通过 HTTPS 反向代理暴露，代理须允许大文件上传和足够长的上传超时。

## 接口

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| POST | `/v1/tasks` | 提交任意类型任务 |
| GET | `/v1/tasks?kind=tts&limit=30&before=…` | 按类型/状态筛选并分页查询轻量任务列表 |
| POST | `/v1/tasks/claim` | worker 原子领取任务 |
| GET | `/v1/tasks/{id}` | 查询状态、进度和结果元数据 |
| GET | `/v1/tasks/{id}/status` | 轻量状态，不含 payload、直连凭据 |
| POST | `/v1/tasks/{id}/heartbeat` | 续租和更新任意 JSON 进度 |
| POST | `/v1/tasks/{id}/fail` | 报告失败，按预算重新排队 |
| POST | `/v1/tasks/{id}/complete` | 只发布完成通知和直连结果元数据，不上传文件 |
| POST | `/v1/tasks/{id}/result/request` | 按需申请中转上传，幂等复用已有上传/结果 |
| POST | `/v1/transfers/claim` | 原结果持有 worker 领取独立的中转上传租约 |
| POST | `/v1/tasks/{id}/transfer/heartbeat` | 续租中转上传 |
| POST | `/v1/tasks/{id}/transfer/fail` | 上传失败，单独重试，不重新执行任务 |
| POST | `/v1/tasks/{id}/result` | 上传一个二进制结果文件并完成任务 |
| GET | `/v1/tasks/{id}/result` | 流式下载结果文件 |
| GET | `/healthz` | 检查服务与数据库 |

创建任务，payload 可以是任意 JSON；请求体上限 16MiB：

```json
{
  "kind": "transcode",
  "payload": {"source": "input-id", "format": "opus"},
  "metadata": {"title": "输入文件转码"},
  "dedupe_key": "input-id-opus-v1",
  "priority": 10,
  "max_attempts": 3
}
```

`priority` 大的先领取，同优先级按提交顺序。`dedupe_key` 可省略；相同键和内容返回原任务，内容、metadata 或调度参数不同返回 409。`max_attempts` 为 1–20，默认 3。

`metadata` 为可选的通用展示信息，最大 8KiB；中转服务只保存和透传，不解释业务字段。任务列表省略 payload 和租约凭据，返回 metadata、状态、进度、结果元数据和创建/更新时间。支持 `kind`、`state`、`limit`（1–100，默认 30）、`before` 游标，响应为 `{"items":[…],"next_cursor":123}`；没有更多任务时游标为 null。GET 单任务或领取时仍包含完整 payload。旧数据库启动时自动补上 metadata 字段。

领取：

```json
{"worker_id":"gpu-1", "kinds":["tts"], "lease_seconds":300}
```

返回 `{"task":null}` 或 `{"task":{"id":"…","kind":"tts","payload":{},"lease_token":"…","lease_until":123,…}}`。省略 `kinds` 或空数组表示接受全部类型。只有领取响应返回本次 `lease_token`；同一 worker 再次领取也会得到新凭据。

心跳：

```json
{
  "worker_id":"gpu-1",
  "lease_token":"领取时返回的凭据",
  "lease_seconds":300,
  "progress":{"phase":"synthesis", "completed":12, "total":68}
}
```

租约范围 30–3600 秒，建议每 15 秒续租。已过期租约不能续租或发布结果，旧凭据返回 409。下一次领取时回收过期任务；显式失败和租约超时都消耗尝试次数，用尽后变为 `failed`。投递语义是 **at least once**；worker 应自行保持业务幂等。

失败：

```json
{"worker_id":"gpu-1", "lease_token":"…", "error":"reason", "retryable":true}
```

上传结果使用原始二进制请求体，不用 multipart，必填头：

```text
X-Worker-Id: gpu-1
X-Lease-Token: 本次凭据
X-Filename: bundle.zip
X-SHA256: 完整文件的 SHA-256（64 位十六进制）
Content-Length: 准确字节数
Content-Type: application/zip
```

也可上传其他格式，缺省类型为 `application/octet-stream`。`X-Filename` 建议使用 ASCII 文件名，最长 180 字节，不允许路径。服务流式写入任务临时目录，校验大小、哈希和当前租约后原子发布；上传失败不会完成任务。上传期间 worker 仍须续租。单任务一个结果，多文件由 worker 自行打包。

完成后任务状态为 `completed`，`result` 包含文件名、类型、SHA-256、字节数、`expires_at` 和 `download_path`，不暴露服务器路径。默认文件保留 24 小时；过期下载返回 410，清理后 `result` 为 null，任务历史仍保留。结果保存在 `SHANYE_RELAY_TEMP_DIR/<task-id>/`，内部文件名与租约关联，原文件名保存于数据库和下载头。

## 先通知、直连优先、按需中转

TTS 适配器采用该流程。通用服务同时保留原来的直接上传接口，其他业务可以自行选择。

持有有效合成租约的 worker 请求 `POST /v1/tasks/{id}/complete`：

```json
{
  "worker_id":"gpu-1", "lease_token":"当前合成租约",
  "filename":"bundle.zip", "content_type":"application/zip",
  "sha256":"64位十六进制", "bytes":99064,
  "direct":{"url":"http://192.168.3.32:17871/v1/results/task-id",
            "token":"单结果访问凭据至少24字节", "expires_at":1794700000}
}
```

服务只存元数据，将任务标为 `completed`，不创建任何结果文件，也不主动访问该地址。详情包含 `result.direct`，列表和 `/status` 省略直连凭据与正文。`result.relay_state` 为 `none / queued / uploading / ready / failed`，与合成状态独立。

客户端直连失败后 POST `/result/request`（空对象即可）。重复请求共用同一个中转；已 ready 的包直接复用。只有原 `result_worker_id` 能从 `/v1/transfers/claim` 领取任务，拿到新的 `lease_token`；随后向原 `/result` 流式上传，改用独立的 transfer heartbeat/fail 接口续租和报错。上传必须与完成通知的文件名、长度、SHA-256 一致。旧上传租约被拒绝，租约超时最多自动尝试 3 次，显式重试可重新排上传队列，始终不会重新排合成。源结果过期后不再允许请求中转。

状态、心跳、完成、失败和上传响应不再重复携带大 payload。只有创建/领取任务和 GET 完整详情需要正文，客户端等候上传时应查询 `/status`。结果临时保留时间从真正上传完成起计，局域网地址有独立有效期。

## 编译与运行

在仓库根目录运行：

```sh
cargo test --locked --manifest-path relay/Cargo.toml
cargo build --release --locked --manifest-path relay/Cargo.toml
export SHANYE_RELAY_TOKEN='replace-with-a-long-private-token'
export SHANYE_RELAY_DATABASE=/var/lib/shanye-task-relay/relay.sqlite
export SHANYE_RELAY_TEMP_DIR=/var/lib/shanye-task-relay/tmp
export SHANYE_RELAY_BIND=127.0.0.1:17870
./relay/target/release/shanye-task-relay
```

SQLite 与临时结果目录须在服务本机磁盘，当前部署模式为单服务进程，多 worker 竞争领取。复制 `deploy/relay.env.example` 到 `/etc/shanye-task-relay.env`，设置私有 token 并限制文件读取权限。`deploy/shanye-task-relay.service` 提供 systemd 示例，调整运行账号与路径后使用。在 Linux 目标主机编译发布；macOS 本地构建产物不能直接用于 Linux。

| 环境变量 | 默认值 |
| --- | --- |
| `SHANYE_RELAY_BIND` | `127.0.0.1:17870` |
| `SHANYE_RELAY_TOKEN` | 必填，不可为空 |
| `SHANYE_RELAY_DATABASE` | `./relay.sqlite` |
| `SHANYE_RELAY_TEMP_DIR` | `./relay-temp` |
| `SHANYE_RELAY_MAX_RESULT_BYTES` | `8589934592`（8GiB） |
| `SHANYE_RELAY_RESULT_TTL_SECS` | `86400` |
| `SHANYE_RELAY_CLEANUP_SECS` | `300` |

## 接入现有 TTS 服务

GPU 服务器运行 `python -m server.remote_worker`，只领取 `kind=tts`。适配器把请求写入现有本地持久队列，驻留 GPU/CPU worker 完成合成与导出，再生成 App 可导入的 ZIP 并发布局域网地址，收到客户端请求后才上传中转。TTS 任务格式及部署步骤见 [TTS 服务文档](../server/README.md#外网任务中转)。

## 验证

```sh
cargo test --locked --manifest-path relay/Cargo.toml
cargo clippy --locked --manifest-path relay/Cargo.toml --all-targets -- -D warnings
server/.venv/bin/python -m unittest discover -s server/tests -v
```

本地验证覆盖类型筛选、优先级、幂等冲突、租约续期、同一 worker 重领后的旧凭据拒绝、失败/超时预算、上传哈希与大小、下载与过期清理、崩溃临时文件回收，以及 TTS 适配器复用合成任务、重建过期 ZIP 和上传期间续租。

已启动 release 二进制进行 HTTP 联调：两章原创测试文本从本地书库快照提交，适配器通过真实中转接口领取，现有 GPU/CPU 队列以模拟推理引擎生成音频，打包上传并下载验证；重启中转服务后任务和结果元数据保留。另验证任意类型 payload 与 18MiB 流式结果上传。

2026-11-14 已部署到 `120.48.49.190:17870`，GPU 主机的轮询适配器同时启用；Android 已通过公网派发原创试音并导入真实 GPU 结果。部署与验收记录见 [App 接入记录](../reviews/app-relay-20261114/README.md)。当前使用 IP/HTTP 联调，可在域名和证书就绪后切换 HTTPS。

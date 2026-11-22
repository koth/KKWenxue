# 山页目录服务（shanye-catalog）部署说明
#
# 构建：须产出 Linux 架构二进制，不能直接上传 macOS 构建产物。
# relay 已安装 /root/.cargo/bin/cargo，可用 CARGO_BUILD_JOBS=1 直接构建；
# 也可在 emotts 上构建后上传。
#
#   rsync -az catalog/ emotts:/h/work/shanye-catalog/ --exclude target
#   ssh emotts 'cd /h/work/shanye-catalog && cargo build --release'
#   rsync -az emotts:/h/work/shanye-catalog/target/release/shanye-catalog relay:/usr/local/bin/
#
# 目录与配置（relay）：
#   install -d -m 700 /var/lib/shanye-catalog
#   install -m 600 deploy/shanye-catalog.env.example /etc/shanye-catalog.env
#   install -m 644 deploy/shanye-catalog.service deploy/shanye-catalog-incremental.{service,timer} \
#                 deploy/shanye-catalog-deep.{service,timer} /etc/systemd/system/
#   systemctl daemon-reload
#   systemctl enable --now shanye-catalog.service
#   systemctl enable --now shanye-catalog-incremental.timer shanye-catalog-deep.timer
#
# nginx（复用现有站点，token 由 relay 侧校验，catalog 本身只监听 loopback）：
#   location /v1/catalog/ {
#       proxy_pass http://127.0.0.1:17872;
#       proxy_set_header Host $host;
#       proxy_read_timeout 30s;
#   }
#
# 首次全量（在 relay 上跑一次，约 50 分钟）：
#   sudo -u root /usr/local/bin/shanye-catalog bootstrap --pages 600
#
# 已有 catalog.sqlite 升级到章节目录后，先回填一次（可后台运行，失败项可重复执行）：
#   sudo -u root /usr/local/bin/shanye-catalog backfill-manifests --limit 100000
#   回填只在完整目录验证后发布；API 会按需补齐缺失目录，不要求等待全库回填完成。
#
# 正文：交互阅读使用 /manifest 和 /chapters/{source_id}，支持目录快照版本和 ETag。
# 服务端合并同一章的源站分页，6 小时缓存，内存上限 64 MB、SQLite 正文上限 256 MB。
# /prefetch 保存逐章检查点，服务重启会恢复 queued/running 项；/retry 只重试失败项。
# /bundle 为显式整本 TXT/ZIP 导出，下载代理保留 Range/If-Range 和 206 响应。
# 不把整本任务用作首次导入路径。
#
# 运维连接（~/.ssh/config 已配 ControlMaster 复用）：
#   ssh relay    # root@120.48.49.190
#   ssh emotts   # koth@192.168.3.32

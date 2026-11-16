# 山页目录服务（shanye-catalog）部署说明
#
# 构建：不在 relay 上编译（1 GB 内存、无 cargo）。在 emotts 上 `cargo build --release`，
#       然后 rsync 二进制到 relay。
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
# 运维连接（~/.ssh/config 已配 ControlMaster 复用）：
#   ssh relay    # root@120.48.49.190
#   ssh emotts   # koth@192.168.3.32

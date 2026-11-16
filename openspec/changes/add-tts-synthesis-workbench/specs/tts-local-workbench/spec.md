## ADDED Requirements

### Requirement: One local launcher with reusable SSH transport
系统 SHALL 提供本地启动器运行 Web 静态服务、同源代理及一条持久 SSH 隧道，所有远程任务及媒体请求 MUST 复用该连接，本地书库接口由本机处理；SSH 凭据不得进入浏览器。

#### Scenario: Start and use the workbench
- **WHEN** 用户运行本地启动命令并已具备服务器 SSH 访问能力
- **THEN** 可打开本地工作台，本地目录直接加载，任务及媒体通过同一受管理连接访问，多次操作不重复创建 SSH 会话

#### Scenario: Local port is already occupied
- **WHEN** 所需本地端口被无关进程占用
- **THEN** 启动器报告冲突或使用显式选择的备用端口，不终止无关进程，不接管无法验证的隧道

### Requirement: Loopback access and protected local writes
系统 SHALL 将远端 API、本地代理及转发端口绑定 loopback，并校验访问凭据；本地写请求 MUST 校验 Host、Origin 与会话凭据，拒绝其他站点驱动本地任务。

#### Scenario: Reject a foreign origin mutation
- **WHEN** 其他网站向本地工作台发送缺失有效会话凭据的写请求
- **THEN** 请求被拒绝，不创建或控制任务，不向页面暴露 SSH 或长期服务凭据

### Requirement: Complete chapter to audio workflow
工作台 SHALL 支持选书、搜索/选择章节、消除同号歧义、查看字数/片段与复用数量、设置预设/速度/停顿、试听、合成缺失或新建版本、播放及下载；本地空库时 MUST 展示本地数据库来源及导入说明。

#### Scenario: Preview and submit selected chapters
- **WHEN** 用户选择章节、试听预设并提交合成缺失内容
- **THEN** 提交明确 chapter_ids 和不可变配置，显示返回任务，完成后可按句播放并下载对应版本

#### Scenario: Connect to an empty catalogue
- **WHEN** 服务器未导入任何书籍但本地存在阅读器书库
- **THEN** 展示本地书籍与章节，允许选择并提交任务，不要求先向服务器导入整本书

### Requirement: Persistent observable task controls
工作台 SHALL 从服务端读取任务及产物状态，展示排队、模型加载、合成、编码、暂停/取消过渡态、失败原文及重试入口；刷新页面 MUST 能恢复列表和控制状态。

#### Scenario: Refresh while a job is pausing
- **WHEN** 用户请求暂停后刷新页面
- **THEN** 从服务端恢复实际 pausing 或 paused 状态，不把本地按钮点击当成暂停已经完成

### Requirement: Recover from disconnection without duplicate work
系统 SHALL 显示连接中断并退避重连，保留待核实提交的幂等键；断线不得停止服务端任务或臆测成功，重连后 MUST 查询真实快照并恢复界面。

#### Scenario: Disconnect during synthesis
- **WHEN** SSH 或浏览器连接中断后恢复
- **THEN** 服务端任务继续按原控制状态运行，界面恢复实际进度和产物，不创建重复合成任务

### Requirement: Honest health estimates and local download status
工作台 SHALL 展示 API/worker/model/GPU/磁盘健康及带来源的估计值，将连接状态、任务状态和浏览器下载状态分别表示；MUST 让用户通过正常浏览器流程选择保存位置。

#### Scenario: Initiate a chapter download
- **WHEN** 用户发起 M4A 或 ZIP 下载
- **THEN** 浏览器处理保存位置，界面显示下载已发起或服务端就绪，不宣称文件已写入未经确认的本地路径

#### Scenario: API is reachable but worker is unavailable
- **WHEN** API 正常且 worker 未运行、模型加载失败或 GPU 被占用
- **THEN** 界面显示具体状态与等待原因，不将 API 可连接显示为 GPU 已可用

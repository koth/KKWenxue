## ADDED Requirements

### Requirement: Versioned downloadable model pack

模型资源 SHALL 发布包含 pack revision、兼容 ABI、引擎 revision、文件大小、SHA-256、最低剩余空间和声线代码列表的 manifest。APK SHALL 只内置运行库和预编码声线代码，大模型由用户确认后从配置的模型源下载到 App 私有目录并校验。

#### Scenario: Prepare the first local synthesis

- **WHEN** 用户首次进入 App 且本地模型尚未就绪
- **THEN** App 提示模型大小、来源和所需空间，用户确认后才开始下载，用户暂缓时书架和阅读功能继续可用

#### Scenario: Keep books out of the model pack

- **WHEN** App 下载或更新模型资源
- **THEN** 下载内容只有声明的模型文件，预编码声线代码来自 APK，小书库和书籍正文不进入模型目录

### Requirement: Resumable and verified storage

`ModelPackManager` SHALL 支持 Range/ETag 断点续传、暂停、取消和重启恢复。所有文件通过大小与 SHA-256 校验后 MUST 原子发布为 ready，校验失败的临时文件不得被推理核心读取。

#### Scenario: Resume an interrupted download

- **WHEN** 模型下载中断后用户重新打开准备页面
- **THEN** App 从已确认的字节范围继续下载并保持同一 pack revision，不从零创建重复任务

#### Scenario: Reject a corrupted pack

- **WHEN** 任一模型文件的哈希或大小与 manifest 不匹配
- **THEN** pack 状态变为 failed，错误包含文件名和校验原因，运行时保持未就绪并允许重新下载

### Requirement: Safe model residency and release

系统 SHALL 在同一进程内最多驻留一套模型，并在模型版本切换或用户释放空间时等待活跃 job 完成后再释放文件句柄和内存。

#### Scenario: Switch model revision after playback

- **WHEN** 当前没有活跃本地 job 且用户确认切换到新 pack revision
- **THEN** 旧模型释放完成后加载新 revision，旧缓存仍按其 model revision 可识别

#### Scenario: Attempt a switch during synthesis

- **WHEN** 本地 job 正在生成片段时请求切换模型
- **THEN** 请求进入等待或被明确拒绝，当前片段不会因文件被删除而损坏

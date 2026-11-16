## ADDED Requirements

### Requirement: Durable idempotent task submission
系统 SHALL 在返回受理成功前持久保存任务与配置快照；合成、试听和下载包创建 MUST 使用幂等键，同键同请求返回原 ID，同键不同请求返回冲突。

#### Scenario: Retry a submission after losing the response
- **WHEN** 客户端在断线后使用相同幂等键与请求重新提交
- **THEN** 返回原任务 ID，重启后仍可查询，不创建第二份任务

#### Scenario: Reuse a key with different content
- **WHEN** 客户端使用已受理的幂等键提交不同章节或配置
- **THEN** 返回冲突错误，不修改原任务

### Requirement: Exclusive resident GPU execution
系统 SHALL 只允许一个执行器拥有 GPU 合成权限，连续任务复用常驻模型；兼容 CLI 与服务 MUST 遵守同一独占锁，API 查询不加载模型。

#### Scenario: Execute consecutive jobs
- **WHEN** 同一 worker 连续执行两个合成任务且模型无故障
- **THEN** 复用已加载模型，API 保持可响应，不为每个任务重新加载权重

#### Scenario: Another executor holds the GPU lock
- **WHEN** worker 启动时另一个兼容执行器已持有独占锁
- **THEN** 不开始第二份推理，健康状态显示外部占用，队列保留等待

### Requirement: Preview priority with bounded fairness
系统 SHALL 在当前 GPU 批次完成后优先处理试听，同优先级按 FIFO；默认连续最多处理 3 个试听后，若普通任务等待，MUST 执行一个普通批次再继续优先调度。

#### Scenario: Submit a preview during a long job
- **WHEN** 普通任务运行期间提交试听且未达到连续优先额度
- **THEN** 当前批次先校验落盘，再执行试听，之后普通任务从检查点继续

#### Scenario: Continuous previews do not starve batch work
- **WHEN** 已连续处理 3 个试听且普通任务仍在等待
- **THEN** 下一个调度单元处理普通任务的一个批次

### Requirement: Complete segment preview ranges
系统 SHALL 从选定章节开头选择若干完整片段生成目标约 30–60 秒的试听，以独立范围产物展示实际时长；不足目标的短章不得补写内容或强制截断句子。

#### Scenario: Preview a short chapter
- **WHEN** 所选章节所有片段预计不足 30 秒
- **THEN** 试听包含已有完整片段并显示实际时长，不新增文本，且不把试听标成完整章节成品

### Requirement: Pause resume and cancel at checkpoints
系统 SHALL 持久保存任务控制意图，运行任务在当前 GPU 批次或 CPU 导出单元完成后暂停/取消；MUST 展示过渡状态，保留已提交缓存，暂停后只能显式继续。

#### Scenario: Pause and resume an active job
- **WHEN** 用户暂停运行任务并在检查点后选择继续
- **THEN** 状态先为 pausing 再为 paused，期间不调度后续单元，继续后仅处理缺失项

#### Scenario: Cancel during export
- **WHEN** 用户在编码期间取消任务
- **THEN** 显示 cancelling，当前导出单元结束后进入 cancelled，不继续后续章节，已提交片段保留

### Requirement: Lease recovery respects control state
系统 SHALL 用持久租约、心跳与持有者令牌保护工作领取和提交；故障恢复 MUST 检查已提交文件，并保留暂停/取消意图，不恢复不存在的旧任务。

#### Scenario: Worker crashes after committing a batch
- **WHEN** worker 在片段提交后、任务完成前退出并重新启动
- **THEN** 过期租约被恢复，校验通过的片段被复用，仅补缺失项，旧持有者令牌不能再发布结果

#### Scenario: Restart with a paused task
- **WHEN** 服务重启时数据库中有暂停任务
- **THEN** 该任务保持暂停，不因租约恢复自动继续

### Requirement: Independent segment validation and bounded retries
系统 SHALL 对同批音频逐段校验并独立保存有效结果，对失败段有界重试、OOM 缩批及必要的有限拆分；MUST 保存尝试次数与错误，不通过无限重试或放宽校验掩盖异常。

#### Scenario: One invalid result in an eight segment batch
- **WHEN** 同批八段中一段超出允许时长，其他七段有效
- **THEN** 七段独立提交，仅异常段进入有限重试或拆分流程，不将整批标为失败

#### Scenario: A segment exhausts the retry budget
- **WHEN** 片段及允许的有限拆分仍无法生成有效音频
- **THEN** 记录最终失败和对应原文，其他章节继续处理，缺段章节不发布为完整，任务最终显示 partial_failed 或 failed

### Requirement: Retry failed work without rewriting history
系统 SHALL 支持显式仅重试失败项，创建关联新任务并复用原配置与有效结果；终态任务与旧产物 MUST 保持可查询且不可原地覆盖。

#### Scenario: Retry a partially failed job
- **WHEN** 用户对 partial_failed 任务选择仅重试失败项
- **THEN** 返回关联新任务 ID，仅调度失败或缺失项，原任务记录保持原状态

### Requirement: Observable progress and disk pressure handling
系统 SHALL 提供真实的任务阶段、章节/片段计数、缓存命中、错误、worker 与磁盘状态；任一工作盘可用空间低于 5GiB 时 MUST 在当前单元检查点后暂停领取新生成/导出工作，不自动删除已确认音频。

#### Scenario: Disk capacity crosses the threshold
- **WHEN** 音频或元数据盘可用空间不足 5GiB
- **THEN** 显示容量不足及暂停原因，当前单元安全收尾后不启动后续单元，释放空间后由用户显式继续

#### Scenario: Estimate without measurements
- **WHEN** 系统尚无足够实测样本估算任务耗时
- **THEN** 显示可复用/缺失数量与估计不可用，不将推测值显示为保证完成时间

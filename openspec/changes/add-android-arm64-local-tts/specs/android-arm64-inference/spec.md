## ADDED Requirements

### Requirement: Android ARM64 native inference

系统 SHALL 为 `arm64-v8a` 提供可由 App 加载的 ZONOS3 原生推理库，且库的公开入口只接受受控的模型路径、文本、声线代码和生成参数。

#### Scenario: Load the native runtime on an ARM64 device

- **WHEN** App 在支持 `arm64-v8a` 的设备启动本地运行时
- **THEN** JNI 成功加载原生库并返回运行时 revision、ABI 和可用声线代码，页面不启动服务器 HTTP 进程

#### Scenario: Reject an unsupported ABI

- **WHEN** App 在没有 `arm64-v8a` 原生库的设备请求本地合成
- **THEN** 本地运行时返回可识别的 unsupported ABI 状态，App 提供远程合成入口，且不创建本地章节缓存

### Requirement: Runtime CPU feature dispatch

原生库 SHALL 在运行时检测 dotprod、FP16、i8mm、SVE 和 SVE2 能力，并选择设备支持的 `ggml`/KleidiAI 内核；缺少某项能力时 MUST 回退到兼容的 AArch64 基线内核。

#### Scenario: Use an optimized kernel when available

- **WHEN** 设备报告 FP16 和 dotprod 能力
- **THEN** 推理事件记录所选内核档位，生成结果通过同一 JNI 接口返回，不能要求用户手动切换编译参数

#### Scenario: Run on a baseline ARM64 device

- **WHEN** 设备没有 i8mm、SVE 或 SVE2
- **THEN** 运行时使用可用的基线或较低优化档完成初始化，不能因缺少高级指令集而加载错误的机器码

### Requirement: Measured ARM64 performance profile

系统 SHALL 记录设备、CPU 特性、线程数、`CPU_CHUNK`、模型 revision、首段延迟、持续 RTF、峰值 RSS 和温度，性能配置 MUST 来源于实际 Android ARM64 基准。

#### Scenario: Compare two runtime profiles

- **WHEN** 同一设备使用两个线程/分块配置完成相同片段基准
- **THEN** 基准记录包含可比较的首段延迟和 RTF，并把通过门槛的配置写入设备运行时档案

#### Scenario: Detect a device below the local playback threshold

- **WHEN** 暖态 RTF 连续超过设备门槛或内存/温度状态不可接受
- **THEN** 运行时停止继续扩展本地队列并返回远程合成建议，已发布片段仍可播放

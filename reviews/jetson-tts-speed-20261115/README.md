# Jetson CosyVoice3 合成速度优化

2026-11-15，`jetson@192.168.3.33`，Orin Nano 8GB / JetPack 6.2，保持 15W。
已将 decoder-only 与 CUDA Graph 部署到正式 GPU worker，原第 1033 章任务断点继续。
模型仍为 CosyVoice3 0.5B，参考音色仍沿用任务配置。

## 定位与实测

正式任务原先热机的加权 RTF 为 2.99：生成 1 分钟音频约需 3 分钟。
配对基准使用相同文本、音色 cwssr、指令、种子和时钟条件；GPU 阶段同步计时显示
约 70% 时间用于逐 token 语言模型解码，其余主要为 flow。单纯调整 Torch 线程数到 1 没有收益。

| 文本 | 原路径 | decoder-only | CUDA Graph | Graph 成品长度 |
| --- | ---: | ---: | ---: | ---: |
| 27 字短段 | 18.78 秒 | 17.88 秒 | 11.36 秒 | 5.76 秒 |
| 46 字中段 | 27.22 秒 | 25.86 秒 | 17.16 秒 | 9.80 秒 |
| 101 字长段 | 未做配对基准 | — | 33.53 秒 | 21.48 秒 |

前两段耗时减少约 38%，吞吐约提高到 1.6 倍；Graph 热机加权 RTF 约 1.83，长段约 1.56。
冷启动、参考音色特征提取和首次 Graph 捕获另计，不包含在热机提速数字中。
字数以 [JSON 原始报告](benchmark.json) 中的文本实际字符数为准。

## 实现

- [cosy_runtime.py](../../server/cosy_runtime.py) 跳过未使用的 151,936 词表投影，直接取 transformer hidden state；CosyVoice 的语音 token 投影仍按原逻辑执行。
- [cosy_graph.py](../../server/cosy_graph.py) 捕获单 token 解码，使用 2,048 token 静态 KV 缓存，每段清空。prompt 仍走正常前向；缓存耗尽转回动态 KV，超长 prompt 直接走动态解码，不截断。
- 单独的非默认 CUDA stream 用于捕获，随后可在 LLM stream 重放；捕获失败在填充请求缓存前回退原路径。
- 采样器、flow、vocoder、分段、音频导出和 UTF-16 cue 均沿用原路径。
- 每段 attempts.runtime_json 记录 decoder_only / cuda_graph 开关，便于区分部署前后样本。

decoder-only 的两段输出与基线逐采样完全相同。Graph 使用固定长度的 attention 缓存，
浮点舍入会使随机采样路径有差异，成品长度可略变，不能宣称波形逐采样相同。
独立 Whisper tiny CPU 识别对比中，两段的字符错误率与原版相同，长段约 3.3%；
tiny 本身有同音字错误，这只是一项完整性检查，不能替代人工听感评价。
见 [Graph 报告](graph-benchmark.json) 和 [ASR 报告](asr-check.json)。

## 验证

- 本地 50 项服务测试通过，其中 3 项 GPU 专项在本地按设计跳过。
- Jetson 上实际运行 3 项 GPU 专项并全部通过：与正常 decoder 数值对比、跨段重置、容量耗尽转换动态缓存、超长 prompt、捕获异常回退。
- GPU 专项使用独立的小模型覆盖容量边界；真实 CosyVoice3 则通过短、中、长三种文本实测。
- 正式服务恢复后的片段耗时与状态见 [正式任务快照](production-report.json)。

正式第 1033 章恢复后已生成 8 段，排除含模型加载的首段，7 个热段的加权 RTF 为 1.84，
原 18 个热段为 2.99，吞吐约提高 1.63 倍。快照时为 27/88 段已完成，GPU worker 重启次数为 0。

## TensorRT 与功耗档

已安装 JetPack 仓库的 TensorRT 10.3 与匹配 L4T 36.4.3 的 DLA compiler，
并在独立、有内存限制的 user unit 中尝试编译官方 flow ONNX。
编译进程的内存已超过 5GB，日志出现 2.62GB tactic 分配不足被跳过；
在引擎生成前主动停止了实验。没有将未验证引擎放入正式合成路径，
也没有把停止实验写成 TensorRT 本身不可用的结论。

当前刷机配置仅提供 15W / 7W。NVIDIA 的 25W / MAXN_SUPER 需要对应 super flash 配置，
本次没有更改板级固件或功耗配置。参考
[NVIDIA 功耗配置文档](https://docs.nvidia.com/jetson/archives/r36.4.3/DeveloperGuide/SD/PlatformPowerAndPerformance/JetsonOrinNanoSeriesJetsonOrinNxSeriesAndJetsonAgxOrinSeries.html)。

## 部署与回退

私有 service.env 已启用：

```ini
SHANYE_COSYVOICE_LOW_MEMORY=1
SHANYE_COSYVOICE_DECODER_ONLY=1
SHANYE_COSYVOICE_CUDA_GRAPH=1
```

代码备份在 Jetson `~/workspace/tts-optimization-20261115/original-server/`。
私有配置备份为 `~/workspace/shanye-tts-service/service.env.before-speed-20261115`，权限 0600。
设后两个开关为 0 并重启 `shanye-tts-gpu` 即恢复原推理路径；保留 low-memory 开关。
GPU worker 的 SIGTERM 会等待当前段落落盘，再退出；已完成片段无需重做。
此修改在后端生效，App 无需更新。

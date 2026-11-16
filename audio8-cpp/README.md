# Audio8 原生 C++/CUDA 推理核心

已在 JetPack 6.2、ARM64、8 GB 共享内存的 Jetson Orin Nano 原生编译并接入山页后端，见 [部署验收](../reviews/audio8-production-20261116/README.md)。文末 Windows/RTX 3080 对照为历史开发机结果。

## 迁移范围

运行时为 C++17 + LibTorch/ATen + CUDA，包含原生 Unicode NFC / ByteLevel BPE 分词、DualAR 生成、重复抑制与 top-k/top-p 采样、KV cache、CUDA Graphs、音频解码和 PCM16 WAV 导出。没有嵌入 Python，也不启动 Python 子进程。原生库提供 `Engine`；`audio8-tts --server` 通过 stdin/stdout JSONL 复用同一个模型进程。

音频解码器原权重为 Python checkpoint，部署前用 `export_codec.py` 做一次离线转换：在原 BF16/CUDA 精度下固化 weight norm，输出仅解码所需的 safetensors。主模型直接读取原 `model.safetensors`。转换脚本、测试脚本使用 Python，推理程序不需要 Python 解释器。

核心使用已编码的声音参考（10 行编码 + 匹配的参考文稿）。外层 `server/audio8_runtime.py` 已接入数据库和 Web 队列，默认音色为用户提供的 cwns.mp3。`server/audio8_prepare.py` 负责一次性 codec 转换和新参考编码；M4A、音量处理、章节包与原文索引由现有导出器处理。

## JetPack 6.2 依赖

[NVIDIA JetPack 6.2 说明](https://docs.nvidia.com/jetson/jetpack/6.2/release-notes/index.html)列出 CUDA 12.6、TensorRT 10.3、cuDNN 9.3。第一版使用 LibTorch，不加载 TensorRT engine。

必须使用与板上 JetPack/CUDA 匹配的 **ARM64 NVIDIA LibTorch/PyTorch 原生库**，并在板上编译，不能复制 Windows exe 或 x86_64 LibTorch。可从匹配的 NVIDIA PyTorch 开发环境取得 `include/`、`lib/`、`share/cmake/`；构建只链接原生 Torch/CUDA 库，不链接 `torch_python`。[NVIDIA 兼容表](https://docs.nvidia.com/deeplearning/frameworks/install-pytorch-jetson-platform-release-notes/pytorch-jetson-rel.html)列有 JetPack 6.2 的 25.02–25.04 / PyTorch 2.7 和 25.05–25.06 / PyTorch 2.8 环境。初期对照应优先选 2.7，避免同时改变语言、算子库和精度。具体 ARM64 库、CUDA 算子与 C++ API 兼容性仍需上板确认。

部署前运行只读检测：

```bash
bash audio8-cpp/probe-jetson.sh
```

再设置原生库路径并构建：

```bash
export LIBTORCH_ROOT=/absolute/path/to/arm64/torch
bash audio8-cpp/build-jetson.sh
export LD_LIBRARY_PATH="$LIBTORCH_ROOT/lib:${LD_LIBRARY_PATH:-}"
```

使用 CMake 3.22+，首次配置联网获取固定版本并校验 SHA-256：nlohmann/json 3.11.3（MIT）、PCRE2 10.45（BSD）、utf8proc 2.9.0（MIT）。也可解压 dependency-cache 中的固定归档并设置 FETCHCONTENT_SOURCE_DIR_JSON / PCRE2 / UTF8PROC。依赖版权文件保留在构建目录下，分发时应一同提供。构建并行度默认 1，减少 8 GB 设备上的编译内存峰值。

## 模型包与离线转换

山页部署从项目根运行：

```bash
python -m server.audio8_prepare decoder \
  --model /home/jetson/workspace/Audio8-TTS-Realistic-Air \
  --output /home/jetson/workspace/Audio8-TTS-Realistic-Air/codec-decoder-bf16.safetensors
```

模型目录需有固定版本的 `model.safetensors`、`config.json`、`tokenizer.json`、`codec.pth` 和 `modeling_arktts_codec.py`。转换输出约 260.8 MB 解码权重，保留原模型许可证。参考码由外层服务按录音哈希与逐字稿缓存。`export_codec.py` 保留了原开发机对照数据的打包流程。

转换时归一化权重逐项一致；BF16 卷积波形未达逐样本一致，转换器保留原始与转换后基准波形，记录最大误差、RMSE、SNR。验收需要同时对照生成编码、波形和正文内容，不能将“可播放”当作数值等价。

## 命令行与常驻模式

请求 JSON 示例（UTF-8；所有路径为设备上的路径）：

```json
{
  "text": "这里是需要朗读的正文。下一句正文。",
  "voice": "/data/audio8/voices/approved/voice.json",
  "output_dir": "/data/tts-output/job-001",
  "seed": 111135,
  "temperature": 0.7,
  "top_p": 0.9,
  "top_k": 50,
  "pause": 0.18,
  "split_sentences": true,
  "minimum_seconds": 0
}
```

```bash
audio8-cpp/build/audio8-tts \
  --model /data/audio8 \
  --codec /data/audio8/codec-decoder-bf16.safetensors \
  --request /data/request.json
```

`--server` 接收一行一个请求，`op` 支持 `synthesize`、`status`、`quit`。模型/分词器/解码器常驻，采样设置不变时复用 CUDA Graphs；随机种子按每个完整句子递增。单进程串行处理，batch=1。并发调用需在外层排队。进度以 JSONL 输出 `loading_engine`、`ready`、`capturing_cuda_graphs`、`generating`、`progress`、`fragment_saved`、`completed` 或 `error`；`progress` 是未保存计算，保存进度应以 `fragment_saved` 为准。

默认启用 `expanded-kv-v3`：在 `compact-prefix-v2` 的 4097 项语音词表与参考前缀缓存基础上，慢模型 KV 直接存为展开到注意力头数的 FP32 缓冲区，每帧仅更新新增位置。原 BF16 math SDPA 本来也会先转为 FP32，这样避免每层每帧复制和转换整块 KV。保留完整 2048 位置、math SDPA、输出 BF16 舍入与原采样顺序；预填充继续使用原 BF16 输入路径。单进程只保留一个参考前缀，切换音色、修改录音编码或逐字稿后重建；外层文件校验缓存有容量上限，并按文件元数据失效。

Jetson 15W 同文基准约从 2.15 降至 1.66 秒生成 1 秒音频，测试的生成编码与 PCM 均逐字节一致，分配器峰值预留内存约多 290 MiB。详见 [实测与验证](../reviews/audio8-kv-speed-20261116/README.md)。`--legacy-kv` 可切回原 KV 存储方式。

`report.json` 的 `reference_cache` 显示音色与前缀命中、准备耗时；片段记录预填充与逐帧生成耗时。可用 `profile_frames:5` 记录前几帧的 CUDA 事件耗时。首次请求仍包含模型启动、图捕获和前缀准备，不能等同于热机速度。

`--full-vocab --no-prefix-cache --legacy-kv` 保留历史数值对照路径，供 `validate.py` 检查原黄金编码。`compact-prefix-v2` 相对最早版本改变 BF16 算子形状、排序及随机数序列，不承诺与最早版本逐编码相同；本轮 `expanded-kv-v3` 相对 v2 保持原计算顺序，配对样本为逐编码和 PCM 一致。使用 `tests/validate_optimizations.py` 检查真实模型的投影、前缀和 KV 数值，`tests/validate_voice_cache.py` 检查切换与失效，`tests/compare_runs.py` 比较完整生成编码和 PCM，并另做朗读内容检查。新的任务快照含 `inference_revision`，运行时也记录实际推理版本。

山页外层对较长中文文本设置保守的最低时长，明显过早结束的结果进入现有重试/拆分流程，不作为完整分段发布。最低时长不能证明逐字完整，内容验收仍需结合识别与试听。

句尾引号与连续标点保留在所属句内，纯符号不会单独合成。上游已分句时使用 `split_sentences:false`，避免再次拆开对白。`--split-text` 可在不加载模型的情况下读取 JSONL text 并输出分句，边界测试为 `python audio8-cpp/tests/test_sentences.py /path/to/audio8-tts`。

可选 `cancel_file` 指定取消文件，逐生成帧检查。异常保留已完成片段及失败报告，达到上下文上限但未出现 EOS 时拒绝发布完整音频。输出目录已有报告则拒绝覆盖。音频文件为 `audio8.wav`，每句另有 WAV、NPY 编码及耗时记录。

## 内存与验证边界

权重 mmap 后逐张量传入 GPU，避免先额外建立一份完整 CPU 权重副本；图和缓存使用固定地址，模型驻留；参考编码在设备上复用。8 GB 是 CPU/GPU 共享总内存，还要给系统、CUDA 库和工作区留空间。短句、batch=1，避免同时运行 ASR、其他大模型或桌面 GPU 任务。Jetson 峰值共享内存和实时率必须通过 `tegrastats` 上板实测，不能用桌面显存峰值直接换算。

开发机对照入口：

```powershell
& audio8-cpp/build-windows.ps1
.\.qwen3-tts-venv\Scripts\python.exe -u audio8-cpp/validate.py --directory tts_output/audio8_cpp_validation_new
```

`validate.py` 对照原分词器、固定种子的原生成编码和解码波形。主程序日志中的 `main_to_ready_seconds` 从进入 `main` 开始，外部测试报告的 wall time 才包含动态库装载。开发机的完整正文验收已完成；还需在目标 Jetson 上重复相同验收。量化、FP16 精度切换和 TensorRT 后端均未启用，需分别验证后再加入。

当前 Windows/RTX 3080 对照已完成：第 911 章 9 段、78.34 秒，9/9 EOS，C++ 与 Python 的 9 组生成 codes 逐项一致；native 生成耗时 24.15 秒（不含动态库装载），常驻 server 连续第二次请求耗时 25.52 秒。CPU Whisper 内容检查未发现长段遗漏或重复。以上是历史开发机结果；Jetson 实测与当前部署见部署验收，不能沿用桌面 GPU 的速度。

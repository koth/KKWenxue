# 《噩夢使徒》本地语音合成

本目录从 Android 使用的 `app/src/main/assets/shanye.db` 读取章节，生成男声有声书。源数据库以只读方式打开。音频文件独立存放，SQLite 保存文本定位、生成参数、文件路径、时长、校验和及任务状态。

当前使用 Qwen3-TTS 1.7B CustomVoice 的 MLX 4-bit 版本，在 Apple Silicon 上本地推理；固定 `Uncle_Fu` 男声，加入沉稳、克制的悬疑旁白指令。模型 revision 固定，首次约需下载 2.3 GB 权重；下载不涉及上传小说内容或付费 API。

## 安装

```sh
uv venv --python 3.12 tts/.venv
uv pip install --python tts/.venv/bin/python -r tts/requirements-lock.txt
```

需要 `ffmpeg` 导出 AAC/M4A。先在项目根目录执行以下命令。

## 分句和合成

```sh
# 导入全部章节，建立与 Android Chapter.text 一致的 UTF-16 文本锚点
tts/.venv/bin/python tts/synthesize.py prepare

# 先生成第一章前 8 段试听
tts/.venv/bin/python tts/synthesize.py generate --first 1 --last 1 --limit 8

# 继续第一章，自动跳过已完成的音频
tts/.venv/bin/python tts/synthesize.py generate --first 1 --last 1

# 逐章生成后续章节；可中断，同样命令可以续跑
tts/.venv/bin/python tts/synthesize.py generate --first 2 --last 1281

# 查看真实进度、已生成音频时长和合成耗时
tts/.venv/bin/python tts/synthesize.py status

# 对试听中发现的问题单独重合成，原片段会留档
tts/.venv/bin/python tts/synthesize.py generate --first 1 --last 1 --retry-segment 2

# 导出前 8 段试听，保留男声音色，语速提高 15%
tts/.venv/bin/python tts/synthesize.py export --profile <profile_id> --partial --limit 8 --speed 1.15

# 验证分句、规范文本、UTF-16（包含 emoji）和重复准备的一致性
tts/.venv/bin/python -m unittest discover -s tts -v
```

生成命令结束后会自动导出章节：完整章节使用 `0001.m4a`，仅有部分内容时使用 `0001.preview.m4a`，对应 JSON 的 `complete` 明确标记是否完整。失败的生成可以重试；在整个生成流程中保存进度，进程退出会释放锁。不同批次大小会生成不同的合成配置，续跑时应保持默认 `--batch-size 4`。

根据试听反馈，当前默认导出为 `--speed 1.15`。使用 FFmpeg `atempo` 对每段保留音高地加速，按加速后真实 PCM 样本数量重新计算 cue；句间停顿保留 180 ms。原始 FLAC 保留，后续调整语速不需要再次运行 TTS 模型。

## 文件和数据库

- `output/tts.sqlite`：规范原文、逐句/长句分片的 UTF-16 范围、独立合成配置、各段结果与错误。
- `output/segments/<profile>/<chapter_id>/*.flac`：可复用的无损语音片段。
- `output/chapters/<profile>/0001.m4a`：章节音频，24 kHz 单声道，AAC 64 kbps。
- `output/chapters/<profile>/0001.json`：章节、音频校验和、完整配置和毫秒级时间索引。

`original_text` 永远是规范原文。`spoken_text` 使用 OpenCC 转为简体并压缩空白，仅用作 TTS 输入。显示层的简/繁切换不改变原文锚点。每条 cue 关联 `chapter_id`、`start_utf16/end_utf16`、原句范围及 `start_ms/end_ms`，因此后续可以实现点句播放、逐句高亮和听读进度。

一句独立合成，句间加入 180 ms 静音；超过 160 字的长句优先按逗号等停顿处分片，仍记录父句范围。这是句/分片级同步，不代表已经有逐字时间戳。当前目录提供合成与索引，Android 播放界面尚未接入。

## 本地语音识别复核

```sh
tts/.venv/bin/python tts/check_audio.py --profile <profile_id> --first 1 --last 1
```

可选的 Qwen3-ASR 0.6B 4-bit（首次另外下载约 708 MB）会逐段转写并生成字符错误率报告，位于 `output/quality/<profile>/`。该报告可以帮助发现明显漏读、重复和不清楚的片段；人名同音字和识别模型自身错误也会产生差异，仍需人工判断。此步骤不改变原文，也不自动把所有差异视为 TTS 错误。

## 方案参考

- [Qwen3-TTS 官方仓库](https://github.com/QwenLM/Qwen3-TTS)：中文预设男声、自然语言风格控制。
- [MLX-Audio Qwen3 使用说明](https://github.com/Neywa-Labs/mlx-audio/blob/main/mlx_audio/tts/models/qwen3_tts/README.md)：Apple Silicon 推理与批量合成。
- [固定的 MLX 模型版本](https://huggingface.co/mlx-community/Qwen3-TTS-12Hz-1.7B-CustomVoice-4bit/tree/f35faf19b0cc2160865af64ecf0f22f83d335135)。
- [IndexTTS 官方仓库](https://github.com/index-tts/index-tts)：也能作为后续后端；IndexTTS2 的官方实现包含 MPS 路径，声音克隆需要参考音频。

## CUDA 服务器

2026-11-09 的任务位于 `koth@192.168.3.32:/h/work/shanye-tts-emengshitu-20261109`，使用 RTX 3090 24GB、PyTorch 2.5.1 + CUDA 12.1、Qwen3-TTS 1.7B CustomVoice BF16。服务器环境独立安装在 `.cuda-venv`，只读复用原有 PyTorch，不修改原环境。模型通过官方 ModelScope 镜像下载，并逐文件校验 `cuda-model-manifest.json` 中固定 Hugging Face revision 的对象哈希。

准备的源文件为 `data/shanye.db`，结果在任务根目录的 `output/`。以下命令在服务器任务目录执行：

```sh
# 首次准备分句库
.cuda-venv/bin/python tts/synthesize.py prepare --source data/shanye.db --output output

# 按当前确认的声音从第 912 章持续合成（前台；断点续跑使用相同参数）
bash tts/run-cuda.sh 912 1281 8

# 后台运行
nohup bash tts/run-cuda.sh 912 1281 8 > logs/synthesis.log 2>&1 < /dev/null &

# 查看批次实测和进度
cat output/progress.json
tail -20 logs/synthesis.log

# 在当前批次完成后安全退出；再次运行同一命令即可续跑
kill -TERM "$(cat logs/synthesis.pid)"
```

`--first/--last` 按章节标题里的书内章号选择，而非数据库的数组下标。第 850 章《鬼霧瓶》的 `chapter_index` 为 848。源书有两个内容不同的第 1169 章，分别导出为 `1169-part1.*`、`1169-part2.*`，防止覆盖；850 至末章共 433 个源章节、60,251 个片段。

CUDA 后端保留 `Uncle_Fu`、悬疑旁白指令与 1.15 倍导出语速，使用与本地版本相同的原文 UTF-16 锚点和音频 cue 格式。每批记录真实耗时、音频时长和峰值显存；OOM 会拆批，连续三批失败会退出并保留断点，磁盘剩余不足 5 GiB 时停止。`progress.json` 的 `realtime_factor` 是原始语音秒数/实际运行秒数，尚未除以导出时的 1.15 倍速。

单章调整旁白语气时，可给 CUDA 命令添加 `--instruct-file tts/styles/neutral-zh.txt`。指令完整保存在 profile 配置中，改变指令会生成独立的 profile，便于保留原版并对比试听。2026-11-10 的第 912 章中性试听输出目录为 `reviews/0912-neutral-20261110/`，采用相同男声和 1.15 倍导出语速。

首轮 RTX 3090 实测：每批 8 段，前 120 段生成 1,159.68 秒原始语音，耗时 365.66 秒（不含模型下载和加载），约 3.17 倍实时。第 850 章已完整导出：106 cues，1.15 倍速成品 908.778 秒。前 8 段 ASR 抽查未见明显漏句；人名同音识别差异仍需听感判断。完整章节已核验音频 SHA-256、源文本哈希及全部 UTF-16 cue 锚点。本地与服务器上的 8 项测试均通过。该数字是当前批次的实测，后续速度随句子长短变化。


试听配置可用 `--speaker Dylan --speed 1.0 --instruct-file tts/styles/bright-neutral-zh.txt` 切换男声音色及正常语速。第 912 章在用户进一步调整音色后，最新输出目录改为 `reviews/0912-dylan-neutral-1x-20261110/`；此前的 `0912-neutral-20261110` 试听已停止。


2026-11-10 用户确认：第 912 章及以后统一采用 **Dylan、明亮沉稳且中性的指令、1.0 倍速**。`run-cuda.sh` 已更新为此默认设置，生成范围默认 912–1281。新的 profile 为 `c3e781727ca44f65`，音频与 JSON 位于主输出的 `output/chapters/c3e781727ca44f65/`。已生成的试听片段会并入主库继续使用。`output/reading-plan.json` 记录各章范围应选择的 profile，第 850–911 章保留此前版本。

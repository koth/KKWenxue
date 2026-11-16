# CosyVoice 3 0.5B 试听

2026-11-15，独立 GPU 环境试听。尚未修改正式 Qwen 合成服务、工作台或 App 的引擎。

- 模型：[`FunAudioLLM/Fun-CosyVoice3-0.5B-2512`](https://www.modelscope.cn/models/FunAudioLLM/Fun-CosyVoice3-0.5B-2512)，使用 `llm.pt` 基础权重。
- 官方代码：<https://github.com/FunAudioLLM/CosyVoice>，位于服务器 `/h/work/CosyVoice`。
- 环境：`/home/koth/miniconda3/envs/shanye-cosyvoice`，Python 3.10、PyTorch 2.3.1 CUDA 12.1、FP16，无 TensorRT/vLLM。
- GPU：RTX 3090。测试过程中现有 Qwen 服务仍占用显存。
- 远端试听目录：`/h/work/shanye-cosyvoice-audition-20261115`。
- 输入：用户已有《噩梦使徒》第 984 章前 8 句，共 300 字。与旧成品使用同一份 `stf.mp3` 克隆参考 WAV；哈希见 `input.json`。
- 播放/合成速度均为 1.0，不做音频加速。
- 相邻句子按模型默认逻辑合并成 4 个片段，保留更多上下文；因此这次比较也包含分组方式的变化。
- 已规整中文输入不含数字、缩写，使用 CosyVoice 内置预处理和句子分组；跳过可选 WeText 多语言资源下载。

| 文件 | 说明 | 时长 | GPU 生成耗时 | RTF |
| --- | --- | --- | --- | --- |
| `cosyvoice3-0.5b-clone.m4a` | 参考音频 + 对应逐字稿直接克隆 | 75.08 秒 | 37.85 秒 | 0.504 |
| `cosyvoice3-0.5b-neutral.m4a` | 同一参考音色 + 平静叙述指令 | 68.16 秒 | 33.42 秒 | 0.490 |
| `qwen3-1.7b-reference.m4a` | 现有第 984 章成品裁出相同文本 | 66.78 秒 | 未重新测量 | — |

模型加载约 26.52 秒，生成耗时不含模型加载，包含参考音色处理。两个样本均在首次加载后的同一进程生成；样本短，不代表全书吞吐量。PyTorch 峰值分配约 4548 MiB，不包含 ONNX Runtime、CUDA 上下文及现有服务占用，不能当作总显存需求。

试听成品全部做两遍响度归一化，目标 -18 LUFS、-2 dBTP，再编码 24kHz 单声道 AAC。实测响度 -18.10 至 -17.99 LUFS，峰值低于 0 dBTP，完整解码通过。原始 WAV、指标及输入均保留；音质和叙事效果仍需试听判断。

复现：

```sh
ssh emotts
source /home/koth/miniconda3/etc/profile.d/conda.sh
conda activate shanye-cosyvoice
cd /h/work/CosyVoice
OPENBLAS_NUM_THREADS=4 OMP_NUM_THREADS=4 python \
  /h/work/shanye-cosyvoice-audition-20261115/run_cosyvoice.py \
  --builtin-normalizer
```

模型的 `speech_tokenizer_v3.onnx` 链接至 `/home/koth/.cache/shanye-cosyvoice3-required/speech_tokenizer_v3.onnx`；保留该缓存。其余必需权重位于 `pretrained_models/Fun-CosyVoice3-0.5B`。未使用额外的 RL 权重、批量 tokenizer 或 TensorRT。

`metrics.json` 为生成实测，`audio-validation.json` 为 AAC 成品检查，`requirements-resolved.txt` 为依赖快照，`run.log` 为推理日志。

## 其他已有参考音色

随后使用相同 300 字、相同平静叙述指令、相同随机种子和 1.0 倍速，生成两份额外试听。均采用 `inference_instruct2`，参考音频提供音色；生成的语音 token 不依赖参考逐字稿。成品同样归一化至约 -18 LUFS。

| 文件 | 参考音频 | 时长 | 成品响度 |
| --- | --- | --- | --- |
| `cosyvoice3-0.5b-demo.m4a` | 已有 `demo.m4a` | 68.16 秒 | -18.27 LUFS |
| `cosyvoice3-0.5b-storyteller.m4a` | 已有 `default_storyteller_c06.wav` | 68.16 秒 | -18.03 LUFS |

各自输入、原始 WAV、指标、成品检查保存在 `demo/` 和 `storyteller/` 子目录。两份峰值均低于 0 dBTP、完整解码通过；没有凭文件名判断音质或给声音特征分类。正式服务仍使用 Qwen。

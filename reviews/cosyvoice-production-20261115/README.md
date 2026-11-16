# CosyVoice3 正式合成切换验收

2026-11-15，正式 GPU 主机 `192.168.3.32`，通过复用 SSH 控制连接部署。正式 worker 仅加载 `FunAudioLLM/Fun-CosyVoice3-0.5B-2512`，FP16，`inference_instruct2`，没有 Qwen TTS 回退。默认参考为已有 `stf.mp3`，默认成品 1.0 倍速、180ms 停顿、统一音量。Web 和 Android 0.9.1 的新任务都使用参考音色，旧固定说话人不再可选。

默认指令与之前中性试听一致：用标准普通话，以平静自然的语气讲述故事，语速适中。引擎标记由服务端加入，界面只展示中文指令。音色特征缓存同时绑定参考 ID、文件哈希和指令，防止缓存遮蔽新指令。原文分段和 UTF-16 时间轴沿用现有协议。

## 实际整章

- 《噩夢使徒》第 984 章，4,373 字，83 段，当前 App 书库中的章节和 revision。
- 成功任务 `82f9f9ce2777411099a0c4a5550a1749`；83 次生成、83 段成功，无失败/重试/缓存复用。
- 片段音频合计 887.72 秒；GPU 单元耗时合计 456.11 秒（含首次模型加载），比值约 0.514。仅代表这次 RTX 3090 实测。
- 最终 M4A 902.48 秒（15:02），含 82 个额外句间停顿。
- 完整 AAC 解码通过，83 个 cue 原文与 UTF-16 范围全部匹配当前 Android 书库；音频 SHA-256 通过。
- 实测 -18.77 LUFS、-1.04 dBTP。响度归一化后 AAC 峰值修正按现有导出规则执行。
- ZIP SHA-256 和所有成员 CRC 通过，包含 `catalogue.json`、完整音频和章节 manifest。未在实体 Android 手机上安装或导入本次包。

文件：`984-cosyvoice-stf.m4a`、`984-cosyvoice-stf.json`、`984-cosyvoice-stf.zip`。详细检查见 `validation.json`，实际配置见 `job.json`；`verify_result.py` 可在本地工作台启动后重复校验及下载，打包请求使用固定幂等键。

## 验证与运行

- 服务测试 41 项通过（含旧引擎拒绝、默认 stf、提示缓存更新、队列、导出、原文对齐和局域网优先传输）；随后新增的提示显示断言单独通过。
- 共享 TTS 文本/音频测试 9 项通过。
- Web TypeScript / Vite 构建通过，浏览器确认默认 stf、第 984 章完整版本可播放及 ZIP 下载入口。
- Android `assembleDebug` 通过，安装包为 `dist/shanye-0.9.1-debug.apk`（versionCode 21）。
- API、CPU、GPU、relay adapter 均运行。GPU 模型与环境路径见 `server/README.md`；依赖快照为 `gpu-requirements-resolved.txt`。

部署中先处理了系统盘空间不足（仅清除 pip 下载缓存，保留模型、环境和音频），再修复公共文件哈希函数对 Python 3.10 的兼容性。首次整章任务因此失败，审计记录保留；以上数据来自修复后的成功任务。原服务的模型/环境未删除；历史 CLI 不属于正式链路。

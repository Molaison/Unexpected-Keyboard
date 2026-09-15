# 豆包语音修复与验收

2026-09-16，基于本地 `main` 的 `d1e1bba` 修复本轮复现的语音异常。
两次真实服务识别、三项模拟器录音触摸检查、五项错误/编辑器回归均通过。

## 问题与修复

- 旧凭据仍可完成 StartTask、StartSession，但随后返回
  `SessionFailed 50700000: service discovery failure`。独立新凭据可识别同一
  音频。现在仅针对这个错误、且尚无识别文字时刷新一次，并重发本次音频；
  成功识别后才保存新凭据。其他错误或重试再次失败均正常报告。
- 重连后集中重发音频同样被服务拒绝。现在重发和后续帧保持 20 ms 节奏，
  并重新对应时间戳。缓存只在首个文字结果前保留，上限为 4 MiB。
- Java Opus 在软件模拟器中处理 1.58 秒语音耗时约 22–45 秒，造成录音停止
  后积压；ART 预编译后仍超过 30 秒。改用原生 libopus 1.5.2 后，同一设备
  编码 79 帧耗时 1644 ms，点按与长按停止检查通过。仍使用 16 kHz、单声道、
  每帧 320 个采样、原有应用模式及默认质量设置。
- 录音采集独立于编码/连接线程，重连期间继续采集，避免硬件短缓冲区漏字。
  PCM 队列最多保留 60 秒，超限会明确报错；音频仅在内存中保存。
- 最终识别包使用同一 composing span 更新，结束时统一完成，防止重复包
  或修订包重复上屏。取消后的旧回调不再修改编辑器。
- 握手失败会释放连接；取消会唤醒控制响应等待；服务错误会停止发送。
  识别结束等待超时会显示错误，服务端成功状态 `20000000` 已加入协议回归。

## 验证结果

| 检查 | 结果 | 记录 |
| --- | --- | --- |
| 旧凭据自动恢复 | 旧凭据失败后自动刷新并识别成功；第二次复用持久化凭据 | `build/validation/voice-recovery-success-java.log`、`voice-recovery-device.log` |
| 原生编码器在线识别 | 两次完整识别 `This is a voice test.`，均复用保存的凭据；`DOUBAO_LIVE_OK passes=2` | `build/validation/voice-live.log` |
| 真实 AudioRecord 与触摸 | 3 项通过：点按开关、长按松手停止、手动拼音输入取消并释放录音；`DOUBAO_MICROPHONE_OK checks=3` | `build/validation/voice-microphone.log` |
| 错误与编辑器回归 | 5 项通过：原生 Opus、服务错误、取消握手、提前结束、重复/修订结果及旧回调 | `build/validation/voice-regression.log` |
| 中文输入触摸 | 原有 14 项通过，包含中文/语音权限交接 | `build/validation/instrumentation.log` |
| JVM 单元测试 | 33 项通过，其中豆包协议 14 项；编码器验证移至 Android 原生测试 | `build/test-results/testDebugUnitTest/` |
| 构建 | Debug 主 APK、测试 APK、四种 ABI 和 Release R8 均通过 | `build/validation/voice-build.log` |
| 最终打包 | 格式整理后重建通过，v1/v2 签名、词库/许可证一致性、两套 JNI 四种 ABI、16 KiB 对齐检查通过 | `build/validation/voice-final-build.log`、`apk-signature.log`、`apk-contents.log` |

实际在线测试使用离线合成的固定语音，通过应用的 `DoubaoAsrClient`、原生
Opus 和真实豆包服务完成；不采集个人录音。错误回归使用确定性服务包和
真实 EditText 的 InputConnection。录音触摸用例使用模拟器的静音麦克风，
不能替代实体手机上实际说话、具体网络与机型兼容性验证。

产物为 `build/outputs/apk/debug/Unexpected-Keyboard-debug.apk`，19,750,905 字节，
使用原有本机 Debug 签名。SHA-256：

```text
24624ae81cd5a0f43af95f2bc24af02799864107c99a3d1b610e074924114eef
```

## 验证命令

```sh
./gradlew --no-daemon --max-workers=2 testDebugUnitTest assembleDebug \
  assembleDebugAndroidTest minifyReleaseWithR8

ANDROID_HOME=/path/to/android-sdk bash tools/run-doubao-live-test.sh emulator-5580
adb -s emulator-5580 shell am instrument -w -r -e voice_regression true \
  com.molaison.unexpectedkeyboard.doubao.test/juloo.keyboard2.PinyinSmokeTest
ANDROID_HOME=/path/to/android-sdk bash tools/run-pinyin-instrumentation.sh emulator-5580
ANDROID_HOME=/path/to/android-sdk bash tools/run-doubao-device-tests.sh emulator-5580
```

在线脚本需要带 `flite` 的 FFmpeg。第二个脚本参数 `true`（对应
`voice_fresh=true`）仅用于独立身份诊断，不替换应用的正式凭据，也不属于
常规验收。麦克风脚本用于已安装两个 Debug APK 的独立模拟器，结束时恢复
原录音权限及选中的输入法。

## 来源与诊断记录

- [doubaoime-asr](https://github.com/yangmoling/doubaoime-asr/tree/267972f815f519fd7c6149f85a8b7cc99daf61a5)
  的会话配置用于对照协议。本版恢复其默认的二遍、三遍识别设置；仅更改
  这组设置未解决旧凭据问题。
- [typeless-ibus PR #2](https://github.com/day253/typeless-ibus/pull/2) 提供了
  50700000 与旧凭据相关的线索，本仓库随后使用实际服务验证了自动恢复。
- [libopus 1.5.2](https://github.com/xiph/opus/tree/ddbe48383984d56acd9e1ab6a090c54ca6b735a6)
  的 BSD 许可证与专利声明保留在 `vendor/opus/COPYING`，随 APK 打包在
  `assets/opus/NOTICE`。固定源码与生成命令见 `vendor/opus/README.md`。

失败记录保留于 `build/validation/voice-discovery-failure.log`、
`voice-discovery-twopass-failure.log`、`voice-replay-burst-failure.log`、
`voice-java-encoder-timeout.log`。它们分别说明错误码、配置排除、重发节奏
与编码积压问题，不应将握手成功直接视为在线识别成功。

软件模拟器曾因启动时遗留的系统/桌面/System UI ANR 弹窗阻挡编辑器焦点。
已通过截图和窗口日志识别并处理，未禁用 ANR 或跳过输入断言。最终在线与
错误回归会话 `94494`、录音会话 `9533`、打包会话 `57788` 均以 0 退出。
模拟器 `emulator-5580` 已关闭；关机阶段宿主模拟器进程发生 SIGSEGV，会话
`6780` 以 139 退出，日志为 `build/validation/voice-emulator.log`，此前所有
验收及取证均已完成。

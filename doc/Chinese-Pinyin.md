# 中文全拼输入

## 范围与基线

基于 `Molaison/Unexpected-Keyboard` 的 `agent/embedded-doubao-asr`，基线提交
`175aa5b52ce12b457de23e251262217d10146b4f`。实现分支为
`feature/chinese-pinyin`。

首版实现 26 键 QWERTY 全拼、离线词语和整句候选、分词选字、空格上屏、
退格编辑、中英切换。保留原有滑动符号、数字键盘、快捷键和豆包语音输入。
密码、数字和电子邮件等字段直接输入；语音开始前结束拼音组合，避免两个
输入来源覆盖同一段 composing text。

## 实现选择

- 解码器：AOSP PinyinIME，Apache-2.0，源代码直接集成到 `vendor/pinyin`。
  源提交 `49aebad1c1cfbbcaa9288ffed5161e79e57c3679`。
- 词库：雾凇拼音的 `cn_dicts/8105.dict.yaml` 和 `cn_dicts/base.dict.yaml`。
  固定源提交 `59fcb4a6bfa71e6ba4fc83af07ee55f0c5b76081`，保留词频并转换为
  AOSP 解码器的二进制词库。APK 内置词库，打字时不需要网络。
- 不引入完整的另一个输入法应用。Java 层负责 Android 编辑会话和候选 UI，
  拼音分词、词频排序、整句组合与用户词库使用已有解码器。

来源：

- <https://android.googlesource.com/platform/packages/inputmethods/PinyinIME/>
- <https://github.com/iDvel/rime-ice>
- <https://github.com/osfans/trime>（集成方式调研）

## 验收清单

2026-09-15 用户反馈修订：

- [x] 中英切换移到 Ctrl 左上角，空闲时不占候选行高度。
- [x] 拼音原文和候选共用紧凑的一行，保留点击原样上屏。
- [x] 补充模糊音、常见错序和邻键候选，原始拼音与精确候选仍可使用。
- [x] 验证简拼、容错选词、部分选词后的编辑及真实角键滑动，重新交付 APK。

原生容错、编辑和文件描述符测试已通过，15 组简拼、模糊音、错序、邻键、
重复及缺字案例的目标词均在前 16 项候选内。补充的混合简拼、部分选词后
纠错和禁止学习检查通过；706 次本机查询 p95 为 4.3 ms，最大 15.7 ms。
这不是手机性能测量。最终构建、14 项触摸测试及冷启动测量会话 `57150`
已以 0 退出。触摸测试标记为 `PINYIN_SMOKE_OK checks=14`，冷启动测量标记为
`PINYIN_BENCHMARK_OK`，二者均为 `INSTRUMENTATION_CODE: -1`。

- [x] 拼音引擎与 JNI 可在 Android 全部目标 ABI 构建。
- [x] 词库来源、版本、许可证、生成命令与过滤范围可复现。
- [x] `nihao`、`zhongguo`、整句、隔音符号和 `v` 能实际产生中文候选。
- [x] 点选、空格、退格、原样上屏、中英切换与部分选词行为正确。
- [x] 密码和数字字段不进入拼音组合；移动光标和切换编辑器不留下旧组合。
- [x] 豆包语音开始前结束拼音组合，原有语音协议测试通过。
- [x] 生成布局检查、单元测试、Android 构建通过并产生可安装 APK。

## 词库与格式范围

词库转换保留 **545,441 条字词及读音**，共 **13,299,106 字节**。按格式限制排除
1,145 条含非 BMP 或非汉字字符的记录、5,058 条超过八字的记录。长句仍可
通过词语组合输入；这些计数不包含源文件中已经注释掉的记录。

源文件哈希和过滤计数保存在 `assets/pinyin/dictionary.json`，许可证和归属
保存在同目录的 `NOTICE`、`AOSP-NOTICE`、`RIME-ICE-LICENSE`。词库 SHA-256：

```text
1cfbcd82aea989f302db62dcb3c90d8edbd8feea221e1d54e62354bf5ee9ef97
```

重新生成固定版本词库：

```sh
python3 tools/build_pinyin_dictionary.py --download --jobs 2
```

源缓存和构建器位于 `build/pinyin-dictionary/`；普通 Android 构建直接使用
已入库的词库文件。

## 验证与产物

2026-09-15 验证环境：JDK 21、Android SDK 36、NDK 27.2.12479018；
界面验证使用 Android 35 x86_64 软件模拟器 `emulator-5580`。

| 检查 | 结果与覆盖范围 | 记录 |
| --- | --- | --- |
| 原生 JNI 和组合编辑 | 通过。实际词库、用户词库、错误路径、未解析尾部、200 组固定随机输入，以及 15 组容错案例、混合简拼、部分选词和禁止学习 | `build/validation/native-tests.log` |
| APK 文件描述符加载 | 通过。带前后缀词库文件的非零偏移正确，解码器打开和关闭后调用方描述符仍可读取 | 同上 |
| JVM 单元测试 | 32 项通过，其中豆包协议测试 13 项、中文编辑器策略 3 项 | `build/test-results/testDebugUnitTest/` |
| 布局与 Android 构建 | 通过。Debug 主 APK、测试 APK，以及 `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64` 原生库 | `build/validation/android-build.log` |
| Release R8 | `minifyReleaseWithR8` 通过；未签名或在设备上验证 Release APK | 同上 |
| 真实触摸与编辑器 | 14 项通过，包含 Ctrl 左上角滑动切换、单行高度、简拼、模糊音、错序、邻键及重复字母 | `build/validation/instrumentation.log` |
| Android 冷启动诊断 | 词库打开 125 ms、容错初始化 113 ms；初始化从旧版的 3317 ms 降低，首次按键超时已在完整触摸测试中消除 | `build/validation/android-timing.log` |
| APK 检查 | v1/v2 签名验证通过；四种 ABI、词库、元数据和许可证均已打包，词库哈希匹配 | `build/validation/apk-signature.log`、`build/validation/apk-contents.log` |

界面测试向真实键盘窗口注入触摸事件，并检查实际 EditText：候选点选、整句
空格上屏、部分选词、退格、回车原样上屏和编辑器动作、Ctrl 左上角中英切换、密码、
邮箱、数字、光标移动、横向滑动后加载更多候选，以及语音权限请求前的
中文上屏。新增用例验证简拼、模糊音、错序、相邻键、多按字母以及部分选词
后的纠错；检查空候选行隐藏、拼音和候选总高度不超过 48 dp。测试不授予
录音权限，不连接豆包在线识别服务。

执行命令：

```sh
bash test/native/run-pinyin-tests.sh

./gradlew --no-daemon --max-workers=2 checkKeyboardLayouts testDebugUnitTest \
  assembleDebug assembleDebugAndroidTest minifyReleaseWithR8

ANDROID_HOME=/path/to/android-sdk \
  bash tools/run-pinyin-instrumentation.sh emulator-5580
```

功能验收使用上述完整 Gradle 检查命令构建，随后运行触摸测试和独立的冷启动
诊断，全部成功。构建日志在 `build/validation/android-build.log`，安装和
触摸测试日志在 `build/validation/device-tests.log`。

正式提交前清理了两个 AOSP NOTICE 文件的末尾空行及一处 C++ 注释行尾空格，
随后使用 `assembleDebug` 重新打包。构建、APK 签名、四种 ABI、包内词库及
许可证一致性和 16 KiB 对齐检查通过，日志为
`build/validation/submission-build.log`。此次清理未修改执行逻辑。

可安装的 Debug APK：`build/outputs/apk/debug/Unexpected-Keyboard-debug.apk`，
19,070,207 字节，使用本机 Debug 签名。SHA-256：

```text
de08dbd2e0931edad5740f1bdbe36ad7945fb756af86b47993a1d79d155abb27
```

最终候选界面截图：`build/validation/pinyin-keyboard.png`。
实体手机和豆包在线识别尚未验证。软件模拟器中诊断输入的单次候选查询
为 9–1307 ms，不能用它推断真机打字延迟。

## 已修复问题与诊断顺序

- 上游编译器在 64 位主机上使用 `sizeof(size_t)` 写固定宽度字段，现使用
  `uint32`。失败路径不再 flush 尚未打开的用户词库。
- 大词库耗尽原候选去重缓冲区和 600 个解析标记。现使用独立去重缓冲区、
  1,024 个 milestone 和 32,768 个解析标记；200 组编辑检查全部通过。
- Android 首次按键曾因 `fdsan` 中止：原生代码直接 `fdopen` Java 管理的
  文件描述符。现先 `dup`，只关闭副本。排查原生崩溃时同时检查 `libc`、
  `DEBUG` 日志，不能只检查 `AndroidRuntime` 或将超时归因于词库加载速度。
- 候选测试原先使用 `requestRectangleOnScreen`，末尾按钮仍不可见。现注入
  实际横向滑动后再点击，并保留候选数量增加的断言。
- 简拼查询 `zg` 曾缺少「中国」：上游只保留前 200 个匹配节点，结果数组
  也只保留最先遍历到的词条。现完整访问匹配分支，并在容量范围内按词频
  保留最佳候选，同时覆盖候选展示与整句搜索两条路径。
- 新增容错层只查询词库，不修改原始拼音或用户词频。比较候选期间关闭
  学习，按各次选词的原始拼音边界恢复已选前缀，选中纠错结果时才上屏。
  回归检查曾发现将「我爱中国」误排到「我在中国」之后；现能完整解析的
  全拼保留精确首选，模糊候选作为补充，不能仅依赖较高词频覆盖原输入。
- 新增 Android 冷启动测量发现，提前枚举所有可能误拼需要 3317 ms，远高于
  词库打开的 146 ms；主机逐键查询测试没有覆盖这段首次初始化开销。
  现按实际输入生成规则，并使用最多 2048 项的缓存。首次测量保存在
  `build/validation/android-timing-eager.log`，最终测量使用
  `build/validation/android-timing.log`；最终初始化为 113 ms，完整触摸测试也
  已通过，诊断测量本身不能替代触摸测试。
- 无 KVM 的软件模拟器首次启动约需 468 秒，本轮启动约 192 秒，曾出现
  Pixel Launcher 和 System UI 的 ANR 弹窗占焦点。
  输入超时时先用截图和 `dumpsys window` 确认焦点；处理实际弹窗后重新测试，
  不隐藏 ANR 或跳过断言。模拟器文件位于 `build/avd/`。
- 测试完成后 `adb -s emulator-5580 emu kill` 返回成功；宿主模拟器在关机
  阶段发生 SIGSEGV，会话 `41628` 以 139 退出，详见
  `build/validation/emulator.log`。此前 14 项界面测试、截图和冷启动测量均已
  成功完成；模拟器进程已经结束。

## 使用与范围

- 常规文本框默认中文，从 Ctrl 键向左上角滑动切换中英并记住选择。
  角标「中 / 英」表示当前模式；Ctrl 的中心仍是原有 Ctrl 键。
- 拼音与候选共用 48 dp 高的一行。原始拼音用小字显示，长输入省略显示；
  没有拼音、联想或英文建议时整行隐藏。
- 支持全拼、声母简拼与混输，例如 `nh` 可选「你好」，`zg` 可选「中国」。
  多词简拼有歧义时可以先选择前面的词，再选择剩余部分。
- 支持 `z/zh`、`c/ch`、`s/sh`、`n/l`、`f/h`、`an/ang`、`en/eng`、`in/ing`
  等模糊音，以及字母错序、相邻键误触、重复或缺字。每次最多考虑两个
  音节的变化，并补充最多八个容错候选；精确候选保留，未解析尾部不丢弃。
- 空格选首选；回车或点击小字拼音原样提交。已明确选择的汉字前缀仍保留，
  退格始终编辑实际输入的字母，容错建议不会悄悄改写拼音。
- 滑动候选栏查看更多，末尾「更多」继续加载，候选不被限制在前三项。
- `v` 表示 `ü`，如 `lvse` 输入「绿色」。隔音符号使用 `m` 左下角的 `'`。
- 密码和数字字段不启用拼音；网址、邮箱和终端默认英文。终端切到中文后，
  拼音显示在键盘内，上屏时才向终端发送汉字。
- 移动光标会结束当前转换并保留编辑器中的原文字；切换语言、隐藏键盘或
  启动语音时会先完成当前拼音。手动输入会停止正在进行的语音识别。
- 用户词库保存在应用私有目录，遵守编辑器的禁止个性化学习标志。

首版提供简体中文 26 键拼音，支持简拼和常见容错，不包含九宫格、双拼、
手写或繁简转换。
解码器每段最多 39 个拼音字符（含隔音符号），继续输入会先上屏当前段。
构建、主机解码与模拟器检查分别记录；不能视为实体手机或豆包在线识别证明。

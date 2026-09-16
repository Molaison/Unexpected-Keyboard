# Unexpected Keyboard [<img src="https://hosted.weblate.org/widget/unexpected-keyboard/svg-badge.svg" alt="État de la traduction" />](https://hosted.weblate.org/engage/unexpected-keyboard/)

---

⚠️ Google wants to kill the open-source Android community.

See [keepandroidopen.org](https://keepandroidopen.org/) and the [F-Droid blog](https://f-droid.org/en/2025/09/29/google-developer-registration-decree.html).

---

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="80">](https://f-droid.org/packages/juloo.keyboard2/)
[<img src="https://play.google.com/intl/en_us/badges/images/generic/en-play-badge.png"
     alt="Get it on Google Play"
     height="80">](https://play.google.com/store/apps/details?id=juloo.keyboard2)

Lightweight and privacy-conscious virtual keyboard for Android.

https://github.com/Julow/Unexpected-Keyboard/assets/2310568/28f8f6fe-ac13-46f3-8c5e-d62443e16d0d

The main feature is that you can type more characters by swiping the keys towards the corners.

This application was originally designed for programmers using Termux.
Now perfect for everyday use.

This application contains no ads and is open source.

Usage: to apply the symbols located in the corners of each key, slide your finger in the direction of the symbols. For example, the Settings are opened by sliding in the left down corner.

| <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" alt="Screenshot-1" /> | <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" alt="Screenshot-2"/> | <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" alt="Screenshot-3"/> |
| --- | --- | --- |
| <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" alt="Screenshot-4" /> | <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/5.png" alt="Screenshot-5" /> | <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/6.png" alt="Screenshot-6" /> |

## Help translate the application

Improve the application translations [using Weblate](https://hosted.weblate.org/engage/unexpected-keyboard/).

[<img src="https://hosted.weblate.org/widget/unexpected-keyboard/multi-auto.svg" alt="État de la traduction" />](https://hosted.weblate.org/engage/unexpected-keyboard/)

## 中文全拼与豆包语音

本分支在豆包语音输入的基础上提供 **26 键中文全拼**。常规文本框默认使用
中文，从 **Ctrl 键向左上角滑动**即可切换中英。拼音和候选共用紧凑的一行，
空闲时隐藏；候选可以横向滚动并继续加载。支持声母简拼、全拼混输、常见
模糊音和错序、邻键、多按字母的容错候选，如 `zg`、`zongguo`、`zhognguo`
都能选出「中国」。空格选择首选，回车原样输入拼音，`v` 输入 `ü`，`m` 键左下角是隔音符号
`'`。密码与数字输入框保持直接输入，电子邮件和网址默认使用英文。

中文输入使用 AOSP 拼音解码器和雾凇拼音的 **545,441 条字词及读音**，
词库随 APK 提供，支持整句、分词选字、后续词联想和本地用户词库。
来源、许可证、使用说明和验证记录见 [中文全拼说明](doc/Chinese-Pinyin.md)。

麦克风键点按开始、再次点按停止，长按时松开即停止。点按模式在句间停顿后
继续聆听，多句按识别顺序追加，直到再次点按。豆包凭据失效时会
自动刷新一次并按实时节奏重发本次音频，成功后保存新凭据；手动打字会
停止录音。实际网络识别与录音验证记录见 [豆包语音说明](doc/Voice-Input.md)。

## Contributing

For instructions on building the application, see
[Contributing](CONTRIBUTING.md).

## Acknowledgement

The [NLnet foundation](https://nlnet.nl/) funded the work on the spell checking feature.

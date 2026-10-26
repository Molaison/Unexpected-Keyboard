# UI iteration 1 — candidate loading and complete interaction targets

Owner: parent Codex. Date: 2026-10-26. Baseline: `67d557d6d1ef7efac83bb6a27f86434b702df201`. Status: code, independent review and local regression complete; Android acceptance carried forward. This is the single execution record for the four-round outcome, with subsequent stages linked from CONTEXT.md.

## 1. Four real analysis perspectives

All three children were launched concurrently using native `multi_agent_v1`; no model overrides. They analyzed before receiving implementation authority. No original ten screenshot attachments were available. Parent inspected the real failed-run `failure-ux.png`; children reported image-view limitations and used source evidence.

| Perspective | Real agent | Evidence and parent decision |
|---|---|---|
| Parent | current session | `f0dab84..67d557d` changes only six lines in Keyboard2View's draw order. Failure is real, but a candidate-height cause is not established. Error-text files masquerade as PNGs in failed-run artifacts. Preserve failing assertion and fix evidence extraction. |
| Rendering | Socrates `01a17839-ee63-7613-9ae0-e5cc768326c4` | Candidate sp/dp concern: keep sp, reject freezing accessibility fonts in dp. Indication overlap is a hypothesis. Parent verified concrete `drawSubLabel` truncation to three UTF-16 units and unbounded label widths; implement full-string fitting without altering geometry/mappings. |
| Input | Harvey `01a17839-f16d-7933-b3d1-8cbfc2fc0207` | Confirmed candidate code unchanged between SHAs; pagination inside a moving scroller is fragile. Tail-duplicate `hasMore` explanation was asserted without a runtime reproduction: parent rejects it as a proven root cause. Inertial tap consumption remains a hypothesis. Implement stable pagination action and retain strict growth assertion plus real scrolling coverage. |
| Panels | Kant `01a17839-f199-7ee1-bf53-588a58aac92a` | Verified emoji group 18dp targets and selection on ACTION_DOWN, no visible selected group. Accept. Reject claimed 31dp candidate touch area (vertical padding is zero and the view is 48dp). Clipboard height collapse is unproven: no speculative window-height patch. Keep entire-card paste. |

## 2. Competitor delta, checked after analysis

Checked 2026-10-26. These are documented features, not claims of current installed-app behavior.

- [Sogou general instructions](https://shouji.sogou.com/wap/htmls/instructions.php): multiple layouts and toolbar editing/emoji entries. Page explicitly labels an older V8.24.2 version.
- [Sogou nine-key candidate help](https://shouji.sogou.com/bangzhu.php?cid=53&pid=5): expanded candidates, separate syllable area, page/return controls, separator key. Project implements expansion, spelling popup and separators; stable pagination touch target needs improvement. An inline four-syllable rail is missing; popup is an alternative interaction, not parity.
- [Sogou gestures/custom symbols](https://shouji.sogou.com/bangzhu.php?cid=52&pid=5): swipe digits, idle long-press digits, clearing composition, up-to-30-character custom symbols. Project supports directional digit keys; long-press digit parity and custom-symbol workflows still require code/behavior inspection. Do not replace existing swipe mappings with competitor conventions.
- [Baidu official help](https://srf.baidu.com/?c=index&e=help): directional letters/digits, candidate expansion, separators, editing and keyboard height. Project has these entry paths; actual behavior needs harness evidence.
- [YuyanIme README](https://github.com/gurecn/YuyanIme) and [releases](https://github.com/gurecn/YuyanIme/releases): feature/release claims include nine-key and panel/layout fixes. No source-level or installed-app validation performed here; do not infer current latest release from an older search snippet.
- [iFlytek official site](https://srf.xunfei.cn/) and [official experience program](https://srf.xunfei.cn/ImeExp.html): accessible, but insufficient evidence for exact nine-key pagination, long-press or syllable behavior. Marketing speed claims are excluded.

## 3. Three-agent implementation ownership

Socrates exclusively owns `Keyboard2View.java`; Harvey exclusively owns `pinyin/PinyinCandidatesView.java`; Kant owns `EmojiGroupButtonsBar.java`, `EmojiGridView.java`, `res/layout/emoji_pane.xml` and only emoji styles in `res/values/styles.xml`. Parent owns the Android regression harness, screenshot extraction and records. No concurrent shared-file writers. Children write real production code, without committing or pushing.

## 4. Baseline and pending verification

- Read CONTRIBUTING.md, three CI workflows, existing instrumentation/native harnesses; no repository AGENTS.md found in the checkout inventory. Submodule initialized at its pinned commit.
- Previous UX run [40321214461](https://github.com/Molaison/Unexpected-Keyboard/actions/runs/40321214461), SHA `f0dab8412d70c87fe5ca04f83360879acc9addab`: successful, 36 input checks. Artifacts downloaded under `build/validation/previous-success/`.
- Baseline UX run [40321335423](https://github.com/Molaison/Unexpected-Keyboard/actions/runs/40321335423), job `121566728367`, artifact `12650090430`: fails after PASS 21 at `PinyinSmokeTest.java:237`, waiting for additional candidate cells. APK/generated checks pass but do not satisfy UX acceptance. Artifacts downloaded under `build/validation/baseline/`; parent visually inspected the valid failure screenshot.
- Real local native regression: `wsl -d Debian -- bash -lc 'cd /mnt/c/Users/ADMIN/work/Unexpected-Keyboard-ui-iterative && bash test/native/run-pinyin-tests.sh'`: PASS; nine-key 1009 checks, lexicon 205 checks, ranking 14 checks, tolerance 706 key queries. Log `build/validation/baseline-native.log`. Initial CRLF shell failure was corrected in the working copy without semantic changes.
- Parent ran a real-JNI `shi` probe: 89 native candidates, 89 unique, 32nd unique at native index 31. This disproves the claimed duplicate-tail cause for this input. Probe saved at `build/validation/01_CandidateProbe.java`; no dictionary changes. Stable pagination addresses a moving target; exact historical touch cancellation remains an inference until runtime evidence.
- Initial SDK inventory was misleading: the SDK exists at `%LOCALAPPDATA%/Android/Sdk`, with platforms 35/36.1 and an AVD. No emulator is connected. Project NDK/AGP are not locally available; child's offline Gradle attempt fails during configuration. A child's `javac errors=0` claim used English-only filtering of localized errors without checking exit status: parent explicitly rejects it as compile evidence. Build/real-touch/screenshots for the modified candidate remain pending Actions. Original screenshots and real-device TalkBack remain gaps.

## 5. Independent review, adjudication, regression and carry-forward

All three implementation agents wrote the production files assigned above. Parent added stationary-footer bounds assertions, retained `items.childCount > beforeMore`, added real scrolling/last-candidate selection, and validates PNG magic during artifact extraction.

Three new, independent read-only reviewers were launched together after code freeze, reviewing the complete `git diff origin/main` including inherited PR changes:

| Reviewer | Findings | Parent adjudication |
|---|---|---|
| Franklin `01a1784c-533b-76a0-821a-d4ee65a3901d` — visual | High: expanded-row cache ignores width. Medium: initial emoji selection can be offscreen. | Accept both. Parent added width to render cache, reflow on size changes, removed the artificial 240dp minimum, and reveals initial selected emoji category. |
| McClintock `01a1784c-575a-7ea0-852a-d297038831a1` — input | No confirmed findings. Runtime evidence pending. | Accept bounded static result only; do not infer behavioral success. |
| Wegener `01a1784c-5781-79b1-a287-0915e83e4fb2` — engineering | Same high width-cache issue; medium hypothetical sub-48dp auto-fit cells; medium icon-only category discoverability. | Width issue fixed. Reject sub-48dp assertion for normal phone widths: auto-fit floors the column count; a viewport narrower than one 48dp cell is not the supported trigger given. Large-font glyph fit remains a real next-round check. Icon-only groups preserve content priority; localized labels exist, further discoverability is a next-round option. |

Parent also checked actual emoji group boundaries: there are nine groups, not eight. Corrected missing People/body and travel/activity order in resource mapping. No data or category was removed.

Post-review regression command: native suite plus `python check_layout.py` and `git diff --check`; see `build/validation/round-1-native.log` and `round-1-layout.log`. Results are recorded with the command execution; no modified Android build/touch pass claimed in this round. Carry forward Android paging gate, large-font/narrow-screen sizing, emoji pane height, accessibility and requested coverage gaps to round 2. Exact historic lost-click mechanism remains an inference; unchanged candidate dimensions and real available second-page data are established facts.

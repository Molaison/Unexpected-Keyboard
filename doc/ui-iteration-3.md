# UI iteration 3 — width, state semantics and clipboard content

Date: 2026-10-26. Start `36f7955`. Status: implementation, independent review and local regression complete; Android gate remains pending. Same parent outcome and V1 agent identities as rounds 1–2.

## 1. Parallel four-perspective analysis

Socrates, Harvey and Kant received read-only briefs together before implementation. Parent separately traced the real nine-key layout, KeyModifier long-press mapping, Pointers and existing touch assertions.

- Rendering/Socrates: `Keyboard2View.onMeasure` ignores widthSpec and forces display width. Accept width-contract defect; drawing and hit testing already share `_keyWidth`, so reject the claim that their internal coordinate systems differ. Reject blanket reserving a middle horizontal band for all top/bottom legends; they are vertically separated and no rendered collision was supplied. Swipe thresholds remain screen-based preferences; do not silently retune them.
- Input/Harvey: expanded arrow keeps the accessibility name “Expand candidates” when open; idle preedit advertises a no-op click. Accept state/action semantics fixes. Missing long-press digit parity is recorded, not invented or silently mapped over existing behavior.
- Panels/Kant: fixed 44dp tabs override a 48dp minimum; persistent 48dp status footer consumes content space; fixed-height recording text can clip under scaling; tabs lack selected semantics. Accept source-backed fixes. Reject static arithmetic as measured TalkBack or font2 evidence.
- Parent: existing nine-key layout explicitly binds digits in northeast corners and separator on ABC's southwest corner. Real existing harness covers nine-key commit, ambiguous reading selection, password/editor switches and deletion. Long-press digit conversion and a Sogou-style 30-character symbol rail are not established project features.

## 2. Competitor delta after analysis

Reopened [Sogou gestures](https://shouji.sogou.com/bangzhu.php?cid=52&pid=5), [candidate help](https://shouji.sogou.com/bangzhu.php?cid=53&pid=5), and [Baidu help](https://srf.baidu.com/?c=index&e=help). Sogou documents idle long-press digits, left-delete clearing and custom-symbol paging; project preserves its own existing directional mappings and has no verified equivalent for those exact interactions. Syllable filtering/expanded candidates/separators exist in project code. Targeted iFlytek official search did not establish these fine-grained gestures. No marketing claim is treated as behavior proof; Yuyan feature lists remain documentation-level evidence from round 1.

## 3. Three actual code owners

- Socrates: only `Keyboard2View.java`, honoring parent width measurement without changing layout XML/swipes.
- Harvey: `PinyinCandidatesView.java` and English/Chinese `pinyin_controls.xml`, state-dependent expand/collapse accessibility names and meaningful preedit actions.
- Kant: only `ClipboardPaneView.java`, full-size tabs, selected state, scalable recording/header and content-first status footer; whole-card paste and storage semantics remain.
- Parent: Android regression coverage and adjudication. No shared production-file writers.

## 4. Independent review and regression

Three independent reviewers resumed together after freeze and read the complete diff:

| Reviewer | Finding | Parent decision |
|---|---|---|
| Franklin | High: selected-tab assertions have no matching implementation. | Accept. Worker honestly reported the omission; parent added mutually exclusive selected flags in refresh, preserving the assertions. |
| McClintock | No confirmed findings. | Accept static coverage only. |
| Wegener | High: action-height floor still permits very-short-screen overflow; medium: width test allegedly does not constrain below display width; considered then withdrew status-layout concern. | Accept height-floor issue: clamp remaining height to zero. When full footer plus candidate row cannot fit, preserve horizontal candidates and expose more through holding the existing arrow, with a localized action description; no new row. Reject width claim: 240dp is below emulator display width and AT_MOST is already tested. Add independent key-bounds assertions as useful coverage. No action on withdrawn status concern. |

Parent corrected accessibility comments: selected is not proof that TalkBack says “expanded”. Explicit action labels distinguish expand/collapse; actual TalkBack remains unrun. New Android tests retain strict paging growth, check clipboard selected flags and create a real Android key canvas constrained to 240dp in EXACTLY/AT_MOST modes, checking every layout key center and visible bounds. These are authored, not yet Android-executed.

Per-round post-review commands: real JNI suite, `python check_layout.py`, `git diff --check`; logs `build/validation/round-3-native.log` and `round-3-layout.log`. No modified Android compile/touch or actual rotation result claimed. Carry forward to round 4: short-height paging fallback verification, stale measurement after configuration/reopening, emoji interaction evidence, font renders, full build/UX workflow and final three independent verdicts.

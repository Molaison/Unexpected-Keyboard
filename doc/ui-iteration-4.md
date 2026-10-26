# UI iteration 4 — contrast, compact paging and actual acceptance

Date: 2026-10-26. Start `652c786`. Status: four rounds of code, independent review and local regression complete; final remote build/touch/visual gate pending. Do not infer runtime completion from this record's existence.

## 1. Four-perspective parallel analysis

Same three real V1 workers received bounded read-only briefs together before development. Parent owns runtime/measurement evidence and final acceptance.

- Socrates: computed actual token contrast using WCAG relative luminance. Light secondary `#60646C` on function `#D9D9E0` is about 4.23:1; regular/canvas surfaces are higher. Accept a small neutral token correction. Their pressed-state claim was retracted in the same report because pressed text takes the activated color; do not count that as a defect. Dark secondary contrast passes the source-color calculation.
- Harvey: arrow has a long-click action advertised even when no useful long-click is available; normal expanded-state description does not mention loading. Accept restricting long-click to the compact fallback with explicit hint. Stale measured canvas height can influence expansion after configuration; accept a coalesced post-layout budget refresh. No runtime rotation proof yet.
- Kant: hardcoded black/white category ripples do not follow themes. Accept theme-accent feedback. A reattach category mismatch was initially called high then retracted: current click paths update both states. Treat synchronization on attachment as an invariant improvement, not a reproduced bug. Localized icon tooltips can improve discovery without taking a content row.
- Parent: real emoji window touches/commit/drag cancellation and narrow/font-render checks need executing, not just compiling. Retain every prior strict input assertion and collect only real PNGs. Original ten screenshots remain unavailable; baseline Actions screenshots are evidence of the old app, not proof of the modified candidate.

## 2. Competitor delta after analysis

Rechecked [Baidu settings help](https://srf.baidu.com/?c=index&e=help), [Yuyan release notes](https://github.com/gurecn/YuyanIme/releases), and [Android accessibility guidance](https://developer.android.com/guide/topics/ui/accessibility/apps#color-contrast). Competitor evidence supports discoverable layout/panel controls and adjustable presentation; it does not prove this app's contrast or touch behavior. Exact Sogou comparisons and their limitations remain linked in rounds 1–3. iFlytek fine-grained nine-key behavior remains evidence-insufficient. No new dictionary/cloud feature is claimed.

## 3. Three real implementation owners

- Socrates: only `res/values/ui_colors.xml`, light secondary neutral contrast correction.
- Harvey: only `PinyinCandidatesView.java`, compact long-click semantics and coalesced measured-height refresh.
- Kant: only `EmojiGroupButtonsBar.java`, themed ripple, attachment synchronization and localized tooltips.
- Parent: existing Android harness extensions and final evidence. No shared production-file writers.

## 4. Final independent reviews, adjudication and regression

Three independent final reviewers resumed together against the complete `origin/main` diff and R4 increment. Franklin (visual) and McClintock (input) reported no confirmed findings. Wegener (engineering) reported no blocker/high and two medium findings:

1. Claimed compact test lacks expanded/hasMore/listener setup: parent rejects; the same widget was set to hasMore, expanded via its actual arrow, then receives a listener before the short-budget action. The full earlier setup is part of the test, not an isolated snippet. Keep assertions.
2. Budget refresh should consider changed screen/font metrics even if canvas height is unchanged: accept simplification; remove the keyboard-height-only early return from the coalesced post. `refreshConfig` already compares all resulting metrics before relayout, preserving idempotence.

Parent also verified an Android framework detail missed by all reviewers: [AOSP View.setOnLongClickListener](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/view/View.java) enables long-clickability even when clearing the listener. Fix call order by clearing the listener before setting longClickable(false), and add an assertion that normal full-height paging exposes no inactive long-click. Correct the obsolete half-height footer comment.

All reported final blocker/high items are resolved at the source-review boundary. Actual Android acceptance remains mandatory. Final local regression is the real JNI suite, layout checker and whitespace check, with `build/validation/round-4-native.log` and `round-4-layout.log`. Parent also independently computed light secondary/function contrast 4.228→4.921, dark secondary/function 7.638. These are token calculations at full opacity, not whole-screen contrast certification.

Next owner/action: parent commits and pushes the four-round batch to the same PR branch, monitors the existing full CI/UX workflow, downloads artifacts, visually inspects valid PNGs and supplies runtime results to these same three independent reviewers. Keep PR #8 Draft. Until those results exist, Android test additions and screenshot paths are pending, not pass claims.

## 5. Coverage and remaining limits

| Requested area | Evidence route / current boundary |
|---|---|
| Nine-key composition, ambiguity, expanded paging, deletion/retyping | Real JNI native suite and existing real-window PinyinSmokeTest, including strict increase of candidate cells. Final modified-window result pending. |
| Direct digits / long-press digits | Layout retains northeast digits and numeric layout. No special long-press PINYIN_2–9-to-digit mapping in KeyModifier; exact Sogou idle-long-press parity is missing. Do not call repeated nine-key events numeric input. |
| Separators | Actual native test explicitly selects 西安 with separator; southwest apostrophe on ABC retained. |
| Chinese/English, 9/26 layout, editor policies | Existing window smoke plus source mappings; password/email/number checks retained. |
| Portrait/narrow/landscape, large fonts | Parent-width tests, wide-to-narrow reflow, actual Android widget font1/2 renders and short-height configuration checks authored. These are not whole-device rotation or a physical-device matrix. |
| Symbols/custom symbols, arrows/Ctrl/Fn/directional gestures | Layout sources and mappings unchanged; layout checker plus real existing swipe paths and narrow key-center bounds. No claim that every directional gesture was manually exercised, or that a 30-character custom-symbol rail exists. |
| Clipboard/editing | Whole-card paste, pin/preview/delete/undo/clear, sensitive exclusion, disabled recording, focus return, selected tabs and light/dark screenshots in harness. Existing shortcuts remain. |
| Emoji | New real window swipe entry, category click/drag cancellation, actual emoji commit and focus-preserving return; new native screenshot. |
| Accessibility | Action labels, selected states, 48dp controls and full text semantics improved. Actual TalkBack/physical switch access unrun; main canvas has no newly implemented per-key virtual accessibility tree. |
| Themes/contrast | Light function-secondary ratio 4.228→4.921 computed by parent; theme-based controls; real light/dark screenshots pending modified CI. User-adjusted opacity and every legacy theme are not covered by that token calculation. |

No changes to decoder dictionaries, cloud input providers or credential handling. All original key layout definitions remain intact. Missing competitor functions and unrun tests are limits, not implemented features.

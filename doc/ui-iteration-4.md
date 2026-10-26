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

### First actual Android acceptance and correction

Code `0cd7f6b`: APK and generated checks pass. [UX run 40332545258](https://github.com/Molaison/Unexpected-Keyboard/actions/runs/40332545258) builds the actual app/test APKs and reaches PASS 38: strict additional candidate cells (PASS 22), scrolling last-candidate commit (23), nine-key/editor tests, clipboard (33–37), and real emoji gesture/drag/commit/focus return (38). Then the new Android widget reflow assertion fails at fontScale 1, row 0. This is not the original timeout and is not accepted as a green UX run.

Parent correction: width-dependent row rebuilding now occurs before child measurement in `onMeasure`, instead of creating unmeasured cells from `onSizeChanged` during layout. Keep the clipping assertion and both font-scale cases; add actual cell/font/bounds dimensions to failure diagnostics. Logs retained in `build/validation/round4-ci-failed.log`, artifact download under `build/validation/candidate-0cd7f6b/`. Corrected candidate must rerun the same UX workflow.

Actual screenshot inspection found an additional high defect missed by source-only reviews: `emoji-panel.png` fills the screen and overlaps the editor/status bar, despite the successful touch/commit test. Parent verified [AOSP InputMethodService.setInputView](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/inputmethodservice/InputMethodService.java): it replaces the supplied layout params with MATCH_PARENT/WRAP_CONTENT. R2's pre-attachment height was ineffective. Original panel worker owns the correction in `Keyboard2.setInputView`: preserve explicit nonnegative pane height after the superclass attaches it, retaining the framework's FrameLayout params; also give emoji root an opaque themed canvas. Parent adds a real-window assertion that emoji height equals the originating keyboard height. This is the observed owner boundary, not the earlier unproven clipboard collapse theory.

### Original-reference acceptance corrections

The supervising ChatGPT agent independently inspected the user-provided original ten screenshots and supplied a concrete same-scale comparison: at 691px width, current keyboard top-to-first-row spacing is 182px (840→1022), reference 109px (955→1064). These are image coordinates, not dp measurements. This parent has still not directly seen those originals; it consumes the supervisor's visual findings alongside its own CI-image inspection.

Two requested acceptance corrections remain in R4: Chinese truly idle should use one toolbar slot rather than header plus blank candidate row; clipboard should combine back/recent-pinned/clear in one header with a narrow fallback, and move recording out of a permanent row while retaining opt-in and marked-sensitive exclusion. Original V1 code workers own disjoint candidate/English-content, clipboard/resources, and Chinese-locale/window-test packages. Core key coordinates/focus must remain stable through idle→composition→empty; real English suggestions must remain visible. New real Chinese-locale idle-nine-key/QWERTY/clipboard/emoji screenshots and another independent three-reviewer acceptance are required.

Intermediate corrective commit `fa25554b8aa61bde380eaf945b8649a870a3098c` has a successful [UX run 40335299255](https://github.com/Molaison/Unexpected-Keyboard/actions/runs/40335299255), after the previous run's clipping and full-screen emoji defects. Its artifacts are being retrieved; the original-reference corrections above are not part of that SHA and are not yet accepted.

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

## Final integration of the reference-based corrections

The supervising agent took over the frozen worktree after the three implementation owners finished. It corrected the actual View ownership/layout, not merely the worker reports: the clipboard has a stable full-width first row; tabs detach from their old parent before moving between its flexible slot and a second row; measurement deducts pane insets and budgets two equal tab widths. The unused detached Switch was removed. The visible overflow menu changes the existing history preference and preserves opt-in, current-clip access and source-marked sensitive exclusion.

English candidate presentation now has one state update path for both pinyin-state updates and asynchronous Latin suggestions. The focused Android test publishes at the production Suggestions callback boundary (synthetic test data, not a new English dictionary/decoder), without calling the panel state setter, and checks actual shown/hidden content.

Final integration also corrected invalid test API calls, stale text-only selectors after icon conversion, deterministic clipboard sample seeding, visible menu interaction, width-to-narrow-to-width clipboard reparenting, symbol-page identity and return, and screenshot validation order. Input key layout XML, KeyValue, KeyModifier and Pointers mappings remain unchanged. No assertion establishing successful paging, native decoding, focus preservation or key-position stability was removed.

The intermediate fa25554 run passed 40 real-window/widget checks. Its detached pinyin-font-1/2 Bitmap exports show only the header and are NOT accepted as visual proof of candidate appearance. The metric assertions remain real Android-widget geometry checks; final visual evidence must use the attached-window Chinese screenshots. The new integration commit and independent final re-review/CI results are recorded separately after execution; this paragraph is not a pass claim.

The first compact-toolbar runtime reached the legacy invariant that the entire idle/composing candidate panel must have the same height (126px -> 252px on this emulator). That invariant contradicts removing the blank idle row. It is replaced, not silently skipped, by stricter behavior-specific assertions: idle row GONE, composition adds exactly one actual measured row, key screen Y remains within 1px and editor focus is retained; the existing 96dp two-row ceiling remains. Paging/cell-growth assertions are unchanged. This is a specified layout-contract update, not a successful-run claim.

The 701aa37 run passed real candidate expansion/pagination and selection, then a legacy popup check reported failure after choosing ming. Its failure-ux.png visibly shows the popup already dismissed, with ming correctly in the editor/preedit and 明 first among candidates. The check had searched the entire active window for absence of ming, which incorrectly matches the successful resulting input. It now asserts that the actual picker window ID leaves the active window, while preserving the composition rewrite and subsequent exact 明 commit assertions. This diagnosis is grounded in the captured real window, not an assumed flaky timeout.

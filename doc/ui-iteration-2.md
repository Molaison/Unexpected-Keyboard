# UI iteration 2 — font scaling and panel space

Date: 2026-10-26. Parent owns adjudication. Start commit `cd7607a`. Status: implementation, independent review and local regression complete; modified Android UX carried forward.

## 1. Four-agent parallel analysis

Reused three real V1 workers concurrently, with new read-only briefs before implementation: Socrates `01a17839-ee63-7613-9ae0-e5cc768326c4` (shared typography/English candidates), Harvey `01a17839-f16d-7933-b3d1-8cbfc2fc0207` (Chinese candidate geometry), Kant `01a17839-f199-7ee1-bf53-588a58aac92a` (emoji panel). Parent separately traced clipboard space and the actual keyboard measurement budget. No original screenshot attachments became available.

- Socrates: English candidates fixed 48dp with 18sp text and 8dp top/bottom margins; auto-size minimum can become 9sp, counteracting the user's font preference. Parent accepts accessibility shrink risk; rejects the assertion that Android auto-size considers width only, and rejects claiming a measured clipping threshold from static arithmetic.
- Harvey: Chinese candidate rows fixed 48dp at 20sp; expanded viewport ignores screen/keyboard budget; cache also needs font metric invalidation. Accept bounded metric-based sizing while preserving compact defaults. Spelling popup indexes match the composition API; selected-spelling feedback is a later opportunity, not an index bug.
- Kant: emoji grid fixed 250dp plus category bar and bottom keys can consume a short landscape viewport; 30sp glyphs at fontScale 2 exceed a 48dp column. Accept. Their numeric landscape estimate used an incorrect row-height calculation, so only the source constraints are accepted as evidence, not the quoted pixel totals.
- Parent: clipboard reserves several control rows independent of content. Carry its adaptive height/semantics work to round 3; do not introduce an unproven window-height pinning workaround.

## 2. Competitor delta after all analysis

Rechecked [Baidu official help](https://srf.baidu.com/?c=index&e=help) for candidate/font/height controls and [YuyanIme releases](https://github.com/gurecn/YuyanIme/releases) for height-related reported fixes. The latter is maintainer release evidence, not a source-level reproduction. [Sogou instructions](https://shouji.sogou.com/wap/htmls/instructions.php) document panel/layout switching. Targeted [iFlytek official-site search](https://srf.xunfei.cn/) still provides insufficient evidence for exact height/font behavior. Project has font/layout settings; defects concern their usable geometry, not missing cloud inference or a reason to replace dictionaries.

## 3. Disjoint three-agent implementation

- Socrates: `KeyboardUi.java` and `suggestions/CandidatesView.java`; shared `rowHeight(float sp)` contract is font metrics plus 12dp, minimum 48dp, no frozen fonts.
- Harvey: only `pinyin/PinyinCandidatesView.java`, consuming that settled helper; flexible text rows, screen-aware expanded budget, font/width reflow.
- Kant: `EmojiGridView.java`, `EmojiGroupButtonsBar.java` if needed, `res/layout/emoji_pane.xml`, and only emoji entry/sizing in `Keyboard2.java`; bounded pane with weighted content grid and glyph-aware columns.
- Parent: instrumentation and this record. No simultaneous shared-file writers.

## 4. Independent review, parent adjudication and regression

Three existing independent reviewers were resumed together after code freeze, each reading the complete diff against origin/main plus isolated R2 changes.

| Reviewer | Finding | Parent decision |
|---|---|---|
| Franklin (same V1 ID as round 1) | High: forced two candidate rows plus footer overflow short screens. Medium: initial emoji adapter captures invalid cell metrics. | Accept both. Remove the two-row overflow floor; keep the footer intact and scroll the available content. Initialize emoji metrics before adapter construction, refresh adapter on configuration changes and reattachment when metrics differ. |
| McClintock (same ID) | Same emoji initialization/adapter issue, medium. | Fixed as above. No other confirmed findings. |
| Wegener (same ID) | High height budget; medium empty/no-scale false positives in geometry test; medium stale emoji metrics. | Accept overflow issue; reject the assertion that `keyboard_view` includes the candidate/header: it is the canvas sibling. Actual measured canvas is used; fallback four-row estimate remains explicit. Add nonempty-row and actual glyph-metric growth assertions; add metric comparison on reattachment. |

Parent additionally corrected an implementation that always capped row heights at 48dp (discarded before acceptance), prevented default English rows growing to 64dp, accounted for actual candidate padding as well as margins, and preserved parent margin settings. The helper uses SP conversion and actual font metrics; text is not frozen in dp.

Actual changes: shared typography helper, scalable Chinese/English rows, width/font cache, weighted emoji content with pane height recomputed at entry, glyph-aware grid columns, localized category resources retained. Parent instrumentation adds actual Android widget measurement/rendering for font scales 1/2 and wide-to-narrow reflow. Those tests are authored but not yet executed; they do not claim real orientation/touch evidence.

Post-review regression: native suite, layout checker, whitespace check; logs `build/validation/round-2-native.log` and `round-2-layout.log`. Preserve the actual command exit results. Android compile, real-touch paging and modified screenshots remain the final Actions gate. Carry to round 3: keyboard measured-width contract, clipboard content/control space, accessibility semantics, remaining nine-key behavior coverage. Extremely short windows can expose less than one full expanded row; no claim of universal 48dp visible targets or real-device accessibility acceptance.

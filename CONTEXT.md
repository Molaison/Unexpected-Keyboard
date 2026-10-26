# Project Context

Updated: 2026-10-26

## Goal And Success Criteria

Refine PR #8 in four evidenced UI/input iterations on `codex/keyboard-ui-refinement`; preserve every key, character, directional mapping and shortcut. Finish with real build, input regression, screenshots, three independent final reviews, and a Draft PR. Never modify main or merge.

## Strategy And Rationale

Each round: parent plus three V1 agents analyze, compare official competitor evidence, three agents implement disjoint modules, three independent agents review the complete diff, parent adjudicates, then regression. Prefer stable geometry and content over decorative controls.

## Key Decisions And Reasons

Quiet neutral light/dark surfaces; readable main and secondary legends. No cloud-input or dictionary replacement. Commercial documentation is evidence of documented interactions, not proof of this application's behavior.

## Lasting Findings And Discontinued Approaches

Baseline `67d557d` builds but UX run 40321335423 fails waiting for additional candidate cells. Successful `f0dab84` differs only in glyph drawing order, not candidate dimensions. Do not infer a layout regression from chronology. Original ten user screenshots are unavailable in this execution context.

## Project Constraints And Assumptions

Work only in this isolated checkout. Native V1 collaboration and native role models. Local Windows has an Android SDK (platforms 35 and 36.1) and an unstarted AVD, but lacks the project NDK 27.2.12479018 and cached AGP 8.13.2. Debian WSL provides real C++/Java native tests. Android acceptance uses the existing GitHub Actions emulator harness; do not claim a local device run.

## Task Tracker And Evidence Locations

The requested iteration records own this outcome's stage results, adjudications, blockers and resumption: [round 1](doc/ui-iteration-1.md), then rounds 2–4. Existing local `bd` is unavailable; do not initialize a second tracker. Local logs and downloaded Actions artifacts live under ignored `build/validation/`. The existing `doc/UI-refinement.md` describes design intent.

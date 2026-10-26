# Keyboard UI refinement

## Direction

The reference screenshots share a quieter visual system: a single neutral canvas, white key surfaces, restrained functional keys, consistent spacing, and a clear difference between primary labels and secondary legends. Preserve the existing keyboard layout language and directional gestures; do not simplify away corner symbols, number-row hints, Ctrl/Fn, arrows, language switching, or the long-press/symbol behavior.

## First implementation pass

- Move Soft Light and Soft Dark toward neutral, low-chroma surfaces with distinct key, function-key, and space-bar tones.
- Slightly reduce key-corner legend prominence relative to the primary character.
- Reduce the generic suggestion strip to a predictable 48dp slot instead of a full key-row height. Suggestions should be easy to scan without making the keyboard feel top-heavy.

## Review checklist

- [ ] Inspect 26-key QWERTY, 9-key Pinyin, number/symbol layout, emoji panel, and clipboard panel.
- [ ] Verify all nine key positions still render; ensure corner legends never obscure the main glyph or each other.
- [ ] Exercise taps and directional swipes on keys with dense legends, especially Ctrl/Fn, punctuation, and cursor arrows.
- [ ] Check compact/tall portrait, landscape, large font scale, and Android 15+ navigation insets.
- [ ] Check light and dark contrast for key labels, functional labels, candidate text, and pressed state.
- [ ] Check candidate strip transitions, clipboard/emoji entry and return, and editor focus preservation.
- [ ] Run native layout tests, Gradle checks, and emulator touch/text/clipboard tests; inspect captured screenshots before declaring the redesign complete.

## Acceptance principle

Visual polish is not sufficient if it reduces typing information density or changes a gesture mapping. The redesign is complete only after both screenshot review and real-touch regression checks pass. A successful compile alone does not establish visual acceptance.

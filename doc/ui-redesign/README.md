# UI redesign evidence

Base revision: `9739ea80a9116acced9fc5270182c297e5a70f81`.

## Design decision

Keep the native Android implementation and the keyboard's corner-swipe symbols.
The user's primary tasks are enabling the keyboard, typing Chinese or English,
choosing 26-key or 9-key input, reusing clipboard text, and adjusting the keyboard.
Use a state-aware setup page, a task-based settings index, and contextual keyboard
tools. Keep the keyboard origin stable during composition. Preserve stored
preferences, pinned snippets, voice input, editor-specific behavior, and all
existing layouts.

The visual reference is Doubao IME: neutral deck, clear letter/modifier distinction,
restrained blue actions. Map light and dark colors separately through semantic
tokens; keep 48dp tool targets and a compact 14/16/20sp text hierarchy. The dense
corner labels on the typing surface remain because they convey working gestures.

## References inspected

| Product | Primary source | Applied principle |
| --- | --- | --- |
| Doubao IME | [Official Android screenshots](https://play.google.com/store/apps/details?hl=zh-CN&id=com.bytedance.android.doubaoime) | Quiet neutral keys; action color carries meaning; contextual tools |
| Gboard | [Official screenshots](https://play.google.com/store/apps/details?id=com.google.android.inputmethod.latin&hl=en), [customization help](https://support.google.com/gboard/answer/6102154?hl=en) | Predictable return to typing; contextual controls; explicit settings routes |
| Microsoft SwiftKey | [Official setup and settings guide](https://support.microsoft.com/en-us/swiftkey-keyboard/how-to-set-up-microsoft-swiftkey-keyboard) | Task-based settings groups; frequent tools separate from full customization |
| Radix Colors | [Scale roles](https://www.radix-ui.com/colors/docs/palette-composition/understanding-the-scale) | Semantic surface, border, text and action roles in both modes |

The full Doubao settings hierarchy was not independently available in official
screenshots. The proposed settings groups are our inference from this codebase's
features, not a claim that they reproduce Doubao's architecture.

## Ownership

Three `gpt-6.1-sol` agents separately researched references, audited user tasks,
and inspected code. Implementation ownership: shared keyboard palette and emoji;
launcher and settings; candidates and clipboard. The lead owns service integration,
dictionary state handling, Android execution, acceptance, and the final decision.

## Validation protocol

Capture actual Android application windows and actual IME touch interactions.
Keep viewport, density, theme, seeded text and input mode the same within each A/B
pair. Screenshots of the test editor contain diagnostic fields; the keyboard below
those fields is the installed production IME. Distinguish observed states from
unverified external-service behavior. Retain a round only if task completion improves
and its functional regressions are acceptable.

Round evidence and final verification are recorded below after execution.

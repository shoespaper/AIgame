# Repository Guidance

## Worldbook and Engine Decoupling

- Keep worldbooks and optional game engines independent behind engine-neutral protocols. Worldbooks own setting content and shared initialization fields; a selected engine owns its typed state, engine-specific initialization fields, action validation, and rule resolution. API, application orchestration, and narrative contracts must not depend on a concrete engine's types or enumerate its fields (for example, AP battle, quest, NPC, or resource fields). Pass structured `ActionIntent` values through these layers and let the selected engine validate and interpret them. With no engine selected, engine-specific actions must be rejected explicitly.

## Code Comments

- When writing or modifying code, add Chinese documentation comments to classes and methods. Each comment must briefly describe the class or method's purpose in one sentence; expand it when the behavior is complex.
- For every method, document the meaning of each parameter in Chinese. Keep parameter descriptions specific to the method's contract, including units, identifiers, expected values, or nullability when relevant.

## No Dead Code

- Do not leave obsolete or unused code behind unless it is genuinely necessary. When a feature, endpoint, field, configuration key, import, or method is superseded or no longer reachable, delete it together with its now-unused imports, configuration entries, and documentation references, rather than disabling it with sentinel conditions or comments. Prefer removal over commenting out.

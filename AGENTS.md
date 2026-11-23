# Repository Guidance

## Worldbook and Engine Decoupling

- Keep worldbooks and optional game engines independent behind engine-neutral protocols. Worldbooks own setting content and shared initialization fields; a selected engine owns its typed state, engine-specific initialization fields, action validation, and rule resolution. API, application orchestration, and narrative contracts must not depend on a concrete engine's types or enumerate its fields (for example, AP battle, quest, NPC, or resource fields). Pass structured `ActionIntent` values through these layers and let the selected engine validate and interpret them. With no engine selected, engine-specific actions must be rejected explicitly.

## Code Comments

- When writing or modifying code, add Chinese documentation comments to classes and methods. Each comment must briefly describe the class or method's purpose in one sentence; expand it when the behavior is complex.
- For every method, document the meaning of each parameter in Chinese. Keep parameter descriptions specific to the method's contract, including units, identifiers, expected values, or nullability when relevant.

## No Dead Code

- Do not leave obsolete or unused code behind unless it is genuinely necessary. When a feature, endpoint, field, configuration key, import, or method is superseded or no longer reachable, delete it together with its now-unused imports, configuration entries, and documentation references, rather than disabling it with sentinel conditions or comments. Prefer removal over commenting out.
- 项目当前处于开发阶段，尚未上线且没有需要保留的正式游戏数据；删除或调整数据结构时无需为历史存档增加迁移、兼容读取或旧字段保留逻辑，直接保持当前代码和数据模型精简即可。

## Code Style

- 将依赖统一写在文件顶部的 `import` 区域；不要在方法、表达式或其他局部位置使用全限定类名代替 import。仅在发生命名冲突或确有必要限定类型来源时例外。
- 优先使用明确的变量类型，便于阅读和理解代码；非必要不得使用 `var`。只有类型非常冗长、从右侧表达式可以无歧义推断且不会降低可读性时，才允许使用 `var`。

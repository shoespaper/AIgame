# AIgame

AIgame 是一个面向单人 AI 角色扮演游戏的 Spring Boot 模块化单体。应用程序和规则集负责权威游戏事实与规则结算，AI 只基于服务端确认的状态和事件生成叙事或受限提案。

## 当前实现

后端已经包含以下运行链路：

- `api`：HTTP 控制器、请求/响应 DTO、动作映射和全局异常处理。
- `application`：世界初始化、游戏实例创建、动作结算、版本检查、存档和叙事编排。
- `engine`：规则集无关的 `GameInstance`、`WorldState`、动作、事件和 runtime 契约。
- `rulesets.ap`：AP 规则集的类型化状态、动作验证和结算，覆盖角色资源、每日推进、经验、任务、NPC、物品和战斗基础模型。
- `content`：从 classpath 读取并校验静态世界定义，通过世界书目录暴露可选世界。
- `persistence`：通过 `GameRepository` 接口提供 JSON 文件存档，默认目录为 `./data/saves`。
- `narrative`：本地确定性叙事 provider，以及可选的 OpenAI Chat Completions 兼容 provider。

游戏状态由 `GameInstance` 包装，包含游戏 ID、世界和内容版本、可选引擎及版本、生命周期、revision、`WorldState` 和引擎拥有的类型化状态。`engineId: null` 表示纯叙事模式。客户端不能提交任意状态 patch；`ActionIntent` 必须先由所选引擎验证和结算。

## 请求入口

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| `GET` | `/api/health` | 应用健康检查 |
| `POST` | `/api/worlds/initialize` | 只初始化并返回世界状态 |
| `POST` | `/api/games/{gameId}/initialize` | 创建完整游戏实例并保存 |
| `GET` | `/api/games/{gameId}` | 读取完整游戏实例 |
| `GET` | `/api/games/{gameId}/save` | 读取世界状态 |
| `POST` | `/api/games/{gameId}/save` | 按 `expectedRevision` 创建 checkpoint |
| `POST` | `/api/games/{gameId}/actions` | 执行 AP 或世界动作 |
| `POST` | `/api/games/{gameId}/actions/narrate` | 执行动作后基于确认事件生成叙事 |
| `POST` | `/api/games/{gameId}/narration` | 基于当前存档生成叙事并结算动作及字段声明 |
| `POST` | `/api/games/{gameId}/narration/options` | 将所选 GM 意图交给引擎校验、结算并生成后续叙事 |
| `POST` | `/api/games/{gameId}/narration/proposals` | 重新转换、校验并应用一个提案 |
| `GET` | `/api/games/initialization/catalog` | 查询世界书和可选引擎目录 |
| `GET` | `/api/games/initialization/schema?worldId=isekai_grey&engineId=ap` | 查询此次创建组合后的初始化字段 schema |
| `GET` | `/actuator/health` | Spring Boot Actuator 健康检查 |

动作、选项和提案都带有 `expectedRevision`。服务端只接受当前版本，成功结算后 revision 加一；版本不匹配时拒绝请求。GM 选项和自由文本都不能直接修改存档：选项中的 `intent` 必须由当前引擎验证，纯叙事实例会返回 `engine_required`。

创建请求明确选择世界书及可选引擎。例如纯叙事请求设置 `engineId` 为 `null`，不发送引擎专属初始化字段；AP 请求则显式传入 `engineId: "ap"`：

```json
{
  "worldId": "isekai_grey",
  "engineId": null,
  "characterName": "旅人",
  "age": 16,
  "gender": "未定"
}
```

通用动作请求使用 `type`、`target` 和类型化 `arguments`，参数项形状为 `{ "name": "amount", "value": { "type": "integer", "value": 20 } }`（也可使用 `text`、`decimal` 或 `flag`）。GM 输出仅包含 `narration`、`action`、`options`、`updates`；`updates` 以字段名映射类型化确定值，无变化时为 `{}`。角色初始化由 AI 根据已收集内容和服务端提供的 schema/缺失必填字段逐轮访谈，Turn 返回 `narration`、`options`、`collected` 和 `done`。玩家点击建议选项时，将选项作为自由文本与当前 `expectedRevision` 提交到 `/api/games/{gameId}/narration`。详见 [初始化与 GM 输出协议](docs/INITIALIZATION_AI_REFACTOR.md)。

## 自定义世界与模板

创建新冒险时可选择「自定义世界」，与 GM 对话确认类型、背景、名称、规则、势力和故事基调。世界书保存后可选择纯叙事或 AP，再完成角色初始化。

确认开局时，服务端按世界书引用的模板 ID 精确匹配。世界书未指定模板时，使用世界类型作为模板 ID。已登记的素材及规则可跨局复用，所选引擎缺少引用或规则时才生成补充提案。纯叙事模式只使用叙事素材。

内置世界模板、世界书和 AP 预置规则分别位于 `src/main/resources/world/templates/isekai.json`、`src/main/resources/world/worldbooks/cinder_continent.json` 和 `src/main/resources/rulesets/ap/template-rules.json`。用户模板、世界书和开局确认的模板目录分别写入 `data/templates/`、`data/worldbooks/` 和 `data/confirmed-templates/registry.json`。`aigame.content.root` 可覆盖用户内容根目录。

新增接口：

- `POST /api/initialization/worldbuild/sessions` 创建 AI 世界构建会话。
- `POST /api/initialization/worldbuild/sessions/{id}/answer`：提交 `{ "answer": "..." }`，返回结构化配置和 `done`。
- `POST /api/games/initialization/templates` 保存模板，接收 `id`、`name`、`type`、`tags` 和可选的 `baseTemplateId`，返回 `templateId`。
- `POST /api/games/initialization/worldbooks`：保存世界书实例，返回后续角色初始化使用的 `worldbookId`。

## 叙事边界

世界构建与角色初始化使用同一套 OpenAI 兼容模型配置。请在前端「设置」页启用模型并填写 API Key、Base URL 和模型名；配置保存在 `data/narrative-settings.json`，也可通过 `aigame.narrative.settings-file` 指定配置文件。未配置模型或请求失败时会显示明确错误，可保留当前会话重试。自动化测试使用模型桩，不依赖外部模型。

AI 收到服务端生成的只读快照和确认事件，返回叙事正文、可空引擎动作、建议选项与确定字段声明。动作由选中引擎验证和结算；世界字段经应用层验证后合并。同轮动作和字段更新仅推进一次 revision 并保存一次，非法 region 独立忽略。

## 随机性

部分动作接受显式 `seed`，并使用 `SplittableRandom` 生成可复现结果。当前用途包括战斗开始时的怪物生成、NPC 人格选择、NPC 交互、NPC 每日变化和物品属性生成。战斗快照会保存启动 seed。

世界每日推进的偶发事件使用 `day ^ revision` 生成确定性随机源；AI 物品提案则由游戏 ID、revision 和提案索引生成稳定 seed。未提供动作 seed 时，HTTP 映射层默认使用 `0`，因此这不是自动生成的安全随机数，也不是完整的整局回放系统。显式随机源的目的是让测试、问题复现和规则迁移可以重复计算。

## 构建与运行

需要 JDK 21 和 Maven：

```text
mvn verify
mvn spring-boot:run
```

也可以使用仓库中的 Maven wrapper：

```text
./mvnw verify
./mvnw spring-boot:run
```

主要配置位于 `src/main/resources/application.properties`：

- `aigame.persistence.root`：存档目录，默认读取 `AI_GAME_SAVE_ROOT`，否则使用 `./data/saves`。

## 测试

测试位于 `src/test/java`，覆盖 AP 初始化与动作、世界初始化、游戏实例、文件存档、叙事提案和架构边界。提交改动前运行：

```text
mvn verify
```

架构测试要求 `engine` 和 `rulesets` 不依赖 Spring、HTTP、持久化或应用层；控制器位于 `api` 包中。存档信封 schema v2 显式保存世界书/版本与可选引擎/版本；schema v1 继续读取并在下次保存时迁移。生产恢复通过 `GameStateTypeResolver` 按引擎 ID 还原类型化状态，未知引擎会明确失败。

## 外部参考

旧 Python skill 和 DiceFrame 目录是本机上的迁移参考，不属于本仓库，也不是运行时依赖。它们只能用于核对历史行为；当前 Java 源码、测试和本 README 才是项目现状的依据。

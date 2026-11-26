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
| `POST` | `/api/games/{gameId}/narration` | 基于当前存档生成叙事并结算动作及字段声明 |
| `GET` | `/api/games/initialization/catalog` | 查询世界书和可选引擎目录 |
| `GET` | `/actuator/health` | Spring Boot Actuator 健康检查 |

动作和叙事请求携带 `expectedRevision`。服务端对照存档版本，成功写入时推进修订号。玩家选择 GM 选项后提交选项文本，模型提出的引擎动作由当前引擎验证。纯叙事实例收到引擎动作时返回 `ENGINE_REQUIRED`。

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

直接动作请求使用 `type`、可选的 `target` 和类型化 `arguments`。整数参数示例为 `{ "name": "amount", "type": "integer", "integer": 20 }`。GM 输出包含 `narration`、`action`、`options` 和 `updates`，其中 `updates` 按字段名提交类型化的值。角色初始化访谈返回 `narration`、`options`、`collected` 和 `done`。玩家点击建议选项后，客户端将选项文本与 `expectedRevision` 提交到 `/api/games/{gameId}/narration`。

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

AI 收到服务端生成的只读世界快照、确认事件和所选引擎投影，并可提出动作与世界字段变更。区域以叙事文本保存，是否发生遭遇由 AI 根据场景决定。服务端确认动作和字段变更后在同一修订号下保存。

世界推进记录期限事件到期的事实。登记期限事件时可提供世界事件素材标识 `materialId`。AP 日结使用本局素材引用的规则模板处理补给或 NPC 心情变化，威胁计数和升级也由 AP 状态保存。世界状态、世界事件和叙事协议共用层仅携带引擎中立的数据。

## 随机性

AP 使用确定性随机源计算 NPC 的日常和交互变化以及部分道具属性。NPC 日结使用游戏 ID 和当前修订号生成种子。普通遭遇使用 GM 指定的怪物素材，AP 根据模板和难度建立战斗状态。

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
- 每次外部模型调用都会记录发送的系统消息、用户消息和收到的回复原文。重试轮次单独记录，可从「设置」导出日志。

## 测试

测试位于 `src/test/java`，覆盖 AP 初始化与动作、世界初始化、游戏实例、文件存档、叙事提案和架构边界。提交改动前运行：

```text
mvn verify
```

架构测试要求 `engine` 和 `rulesets` 不依赖 Spring、HTTP、持久化或应用层；控制器位于 `api` 包中。存档信封 schema v2 显式保存世界书/版本与可选引擎/版本；schema v1 继续读取并在下次保存时迁移。生产恢复通过 `GameStateTypeResolver` 按引擎 ID 还原类型化状态，未知引擎会明确失败。

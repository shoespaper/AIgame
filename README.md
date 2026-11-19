# AIgame

AIgame 是一个面向单人 AI 角色扮演游戏的 Spring Boot 模块化单体。应用程序和规则集负责权威游戏事实与规则结算，AI 只基于服务端确认的状态和事件生成叙事或受限提案。

## 当前实现

后端已经包含以下运行链路：

- `api`：HTTP 控制器、请求/响应 DTO、动作映射和全局异常处理。
- `application`：世界初始化、游戏实例创建、动作结算、版本检查、存档和叙事编排。
- `engine`：规则集无关的 `GameInstance`、`WorldState`、动作、事件和 runtime 契约。
- `rulesets.ap`：AP 规则集的类型化状态、动作验证和结算，覆盖角色资源、每日推进、经验、任务、NPC、物品和战斗基础模型。
- `content`：从 classpath 读取并校验静态世界定义，目前注册了 `isekai_grey`。
- `persistence`：通过 `GameRepository` 接口提供 JSON 文件存档，默认目录为 `./data/saves`。
- `narrative`：本地确定性叙事 provider，以及可选的 OpenAI Chat Completions 兼容 provider。

游戏状态由 `GameInstance` 包装，包含游戏 ID、世界和内容版本、规则集及版本、生命周期、revision、`WorldState` 和规则集拥有的类型化状态。客户端不能提交任意状态 patch；动作必须先映射为封闭的 AP 或世界命令，再由规则集验证、结算和持久化。

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
| `POST` | `/api/games/{gameId}/narration` | 基于当前存档生成叙事和提案候选 |
| `POST` | `/api/games/{gameId}/narration/proposals` | 重新转换、校验并应用一个提案 |
| `GET` | `/actuator/health` | Spring Boot Actuator 健康检查 |

动作和提案都带有 `expectedRevision`。服务端只接受当前版本，成功结算后 revision 加一；版本不匹配时拒绝请求。

## 叙事边界

默认使用本地 provider，因此开发和测试不需要外部模型。启用 OpenAI 兼容 provider 时设置：

```text
AI_GAME_NARRATIVE_ENABLED=true
AI_GAME_NARRATIVE_API_KEY=...
AI_GAME_NARRATIVE_BASE_URL=https://api.openai.com/v1   # 可选
AI_GAME_NARRATIVE_MODEL=gpt-4o-mini                    # 可选
```

AI 收到服务端生成的只读快照和确认事件，可以返回文本、媒体请求以及以下提案：`gain_experience`、`acquire_item`、`offer_quest`、`schedule_event`。提案不会随叙事响应自动改变存档，必须在单独请求中重新转换并通过规则验证。

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
- `aigame.narrative.enabled`：是否启用外部叙事 provider，默认 `false`。

## 测试

测试位于 `src/test/java`，覆盖 AP 初始化与动作、世界初始化、游戏实例、文件存档、叙事提案和架构边界。提交改动前运行：

```text
mvn verify
```

架构测试要求 `engine` 和 `rulesets` 不依赖 Spring、HTTP、持久化或应用层；控制器位于 `api` 包中。当前实现仍有两个明确限制：规则集选择在应用层直接实例化 AP runtime，文件存档反序列化也只支持 `ap`；未来增加规则集时需要补充 runtime 注册和存档适配。

## 外部参考

旧 Python skill 和 DiceFrame 目录是本机上的迁移参考，不属于本仓库，也不是运行时依赖。它们只能用于核对历史行为；当前 Java 源码、测试和本 README 才是项目现状的依据。

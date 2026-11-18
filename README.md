# AIgame

AIgame 是一个面向单人 AI 角色扮演游戏的模块化单体项目。应用程序负责权威游戏事实和规则结算，AI 负责基于已确认结果进行叙事。当前 Java 运行时以 AP（行动力）规则集为首个目标，同时保持引擎边界不依赖具体规则集。

## 当前状态

项目目前完成了 Spring Boot 基础骨架、健康检查、初步模块边界和架构测试。引擎运行时接口、仓储接口、内容目录接口、世界初始化、类型化 AP 初始状态、文件存档读档和 AP 动作结算已经建立。当前已支持休息、消耗口粮、使用药水和每日推进；每日推进包含口粮/饥饿、HP/SP 恢复、状态效果递减、威胁计数与危局锁存。

主要文档：

- [系统架构](docs/ARCHITECTURE.md)
- [迁移决策](docs/MIGRATION_DECISIONS.md)
- [世界初始化需求](docs/WORLD_INITIALIZATION_REQUIREMENTS.md)
- [AP 规则集迁移设计](docs/AP_MIGRATION_REQUIREMENTS.md)

## 技术与边界

- JDK 21、Maven、Spring Boot。
- `api` 处理 HTTP 输入输出；`application` 编排用例；`engine` 定义规则集无关契约；`rulesets.ap` 拥有 AP 规则和类型化状态。
- `content` 提供静态世界和规则内容；`application.port` 定义仓储与 provider 接口；外部适配器位于 `persistence` 和 `infrastructure`。
- 不用 `Map<String, Object>` 保存权威游戏状态，不建立通用规则 DSL。
- AI 不直接修改已确认的权威规则状态；游戏数值使用整数类型，并保留 Python 的截断和向下取整语义。

## 构建与运行

需要 JDK 21：

```text
mvn verify
mvn spring-boot:run
```

健康检查：`/api/health` 和 `/actuator/health`。

## 迁移参考

以下是本机外部参考项目，不属于本仓库，也不会随仓库克隆。路径用于后续迁移工作重新定位原始行为；若更换开发环境，需要更新为可访问路径或将经过审查的必要 fixture 纳入本仓库。

### 原始 AI 跑团 skill

路径：

```text
C:\Users\25781\AppData\Roaming\qianfan-desktop-app\qianfan_desk_xdg\50fe8cfaa67f4141b1885176046866ff\data\skills\user\isekai-rpg
```

重点参考：

- `SKILL.md`：游戏流程、开局问答、初始化调用参数、玩家可见行为及存档字段说明。
- `scripts/persistence.py`：`cmd_init` 是新局初始化的主要行为基线；同时包含世界状态、存档和每日结算等流程。
- `scripts/lore_loader.py`：世界模板读取、模板与存档副本关系、内容目录读写、随机生成和存档路径选择。
- `world/isekai_grey/world_lore.json`：当前世界模板。需区分静态模板内容与每局动态状态。
- `data/lore.json`：旧存档兼容用内容，不应未经审查当作新模板的唯一来源。
- `PROGRESS.md`：skill 的近期修改、已知限制和冒烟验证记录；其中 Windows 终端显示可能受编码影响。

参考代码时应重新检查当前文件，而非只依赖本说明中的摘要。保留玩家可见规则和数值；任何有意改变结果的修正都要记录在 `docs/MIGRATION_DECISIONS.md` 并带测试。

### DiceFrame 跑团后端

路径：

```text
D:\diceframe
```

这是规模较大的通用跑团后端，适合借鉴职责边界和成熟流程，不是 AIgame 的实现规范，也不应整体复制。世界初始化相关的参考位置包括：

- `src/engine/world_state.py`：新游戏的空世界事实状态及默认时钟。
- `src/engine/world/`：世界实体、关系等状态契约和校验。
- `src/content/worlds.py`、`src/webui/services/worlds.py`：世界定义内容投影和世界创建服务。
- `src/webui/services/game_creation_phases.py`、`src/webui/services/game_lifecycle.py`：新游戏创建阶段与启动生命周期编排。
- `src/adventures/materialization.py`、`src/webui/services/adventure_materialization.py`：将静态冒险种子物化为初始世界操作，以及幂等、原子物化方面的做法。
- `docs/ARCHITECTURE_CN.md` / `docs/ARCHITECTURE_EN.md`：World Definition、WorldState、绑定和初始化边界的背景设计。文档很长，应按相关章节检索，不将其完整架构照搬到本项目。

借鉴原则：静态定义与运行时事实分离；初始化编排保持在应用层；物化过程可验证、可重试且不重复应用；规则专属状态仍由规则集拥有。AIgame 首阶段保持单人模块化单体，不引入与目标无关的 DiceFrame 子系统。

## 贡献与验证

改动后运行 `mvn verify`。规则迁移需增加聚焦的 JUnit 5 测试，并在适用时用 Python golden fixtures 对照。包边界调整后保持 `ArchitectureTest` 通过。不要静默更改平衡数值。

## 后续 TODO

- [x] 实现当前世界状态的 JSON 存档和读档。
- [x] 增加存档格式版本、损坏存档错误处理和显式存档目录配置。
- [x] 增加当前世界状态的保存/读取应用服务与 API，并测试 round-trip 和非法存档。
- [x] 建立 `GameInstance` 存档外壳，包含游戏、世界、规则集版本和生命周期字段。
- [x] 收紧保存入口：公开保存 API 只接受 `expectedRevision`，服务端从已有实例创建 checkpoint。
- [x] 将 `GameInstance` 扩展为包含角色状态和 AP 规则集状态。
- [x] 实现类型化 AP 动作、每日结算、威胁推进和基础经验升级。
- [x] 迁移技能/法术学习、装备切换、药水使用和任务奖励。
- [ ] 迁移复杂物品效果、任务生命周期和战斗规则。
- [ ] 在存档读档稳定后，再评估是否需要规则计算复核或更完整的调试回放能力。

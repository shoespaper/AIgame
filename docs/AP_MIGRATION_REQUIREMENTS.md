# AP 规则集迁移设计

## 1. 目标与范围

将旧 `isekai-rpg` skill 中的行动力（AP）规则迁移到 `rulesets.ap`，并让它作为 `GameInstance.rulesetState` 的第一个具体规则状态。迁移目标是保留玩家可见行为、数值和边界语义；应用层只负责选择规则集、编排调用和保存结果，AP 代码负责权威计算。

本设计覆盖 AP 初始角色状态、属性/HP/SP/资源、装备/能力/物品/天赋、动作校验、结算事件、每日结算、成长、任务、NPC、物品和战斗的迁移顺序，以及 Python 对照测试策略。暂不实现完整 LLM provider、图片生成、多人、分布式锁或通用规则 DSL。

## 2. 行为基线与参考代码

原始 skill 路径：

```text
C:\Users\25781\AppData\Roaming\qianfan-desktop-app\qianfan_desk_xdg\50fe8cfaa67f4141b1885176046866ff\data\skills\user\isekai-rpg
```

迁移前必须重新核对当前源码和模板：

- `SKILL.md`：玩家可见规则、开局流程、状态字段、战斗和每日结算约束；
- `scripts/persistence.py`：`cmd_init`、`settle`、资源、商店、任务、成长和存档字段；
- `scripts/battle.py`：AP 战斗状态、行动消耗、伤害、减伤、护盾、技能/法术和战斗结束；
- `scripts/growth.py`：能力学习、升级和成长资源；
- `scripts/quest_gen.py`：任务、怪物、地城和奖励生成；
- `scripts/npc.py`：NPC 名册、关系、情绪和日程；
- `scripts/world_sim.py`：威胁、期限事件和世界推进；
- `world/isekai_grey/world_lore.json`：抽象模板、能力槽位、物品/怪物类型和难度预设；
- `PROGRESS.md`：已知限制和最近行为修正。

`D:\diceframe` 只用于借鉴模块边界、运行时状态所有权和内容物化方式，不作为 AP 数值或战斗规则来源。

## 3. 依赖与所有权

当前 `GameInstance` 外壳为：

```text
GameInstance
├── gameId
├── worldId / contentVersion
├── rulesetId / rulesetVersion
├── lifecycle
├── revision
├── WorldState
└── rulesetState
```

AP 迁移后，`rulesetState` 应为 `ApState`，而不是 `Map<String, Object>`。`rulesetId` 固定为 `ap`，`rulesetVersion` 使用明确版本，例如 `baseline-1`；改变玩家可见规则时升级版本并记录迁移决策。

- `engine`：规则集无关的状态、动作验证、结算结果和事件契约。
- `rulesets.ap`：拥有 `ApState`、AP 动作、数值计算、初始化和结算逻辑；不得依赖 Spring、HTTP、持久化或 LLM。
- `application`：选择 AP runtime，调用验证/结算，并保存返回的下一实例。
- `content`：提供只读世界模板、能力/物品/怪物定义和难度预设。
- `persistence`：序列化完整 `GameInstance`，不解释 AP 数值。
- `narrative`：只消费确认后的投影和事件；AI 提案必须重新进入 AP 验证流程。

公开保存接口只能创建当前服务端实例的 checkpoint。客户端不得提交 `WorldState`、`ApState` 或任意状态 patch；后续动作接口必须加载实例、校验 revision、调用规则集结算，再保存返回的新实例。

## 4. ApState 设计

`ApState` 是 AP 规则拥有的不可变类型化状态。字段以旧 Python 存档为基线，不能把多个权威概念塞进通用 map。

```text
ApState
├── character: name, age, gender, archetype, level, xp, xpToNext
├── progression: adventurerRank, adventurerTitle, statGrowthLog
├── attributes: STR, DEX, CON, INT, WIS, CHA
├── vitality: hpCurrent, hpMax, spCurrent, spMax, statusEffects
├── abilities: skills, spells
├── inventory
├── equipment
├── resources: copper, rations, potions
├── uniqueTalent
├── questRefs
├── titles
└── bonds
```

所有集合和 map 在构造时复制为不可变值。HP、SP、铜币、口粮、药水、经验、等级和 AP 等游戏量使用 `int` 或 `long`。

最大 HP、最大 SP、最终力量、减伤和行动力上限属于衍生值，应由规则根据属性、装备和内容定义计算。若兼容旧存档保存衍生值，加载时必须校验或重新投影，不能让两份数值分叉。

## 5. 初始状态迁移

AP 初始化器接受类型化输入：角色名、年龄、性别、职业/原型、难度 ID、世界定义和内容版本，以及已选的出身场景/天赋内容。应用校验必填字段和内容存在性；AP 校验职业、难度和规则范围，未知键不能静默使用默认值。

旧 `cmd_init` 已确认的计算路径：

```text
archetype -> STR/DEX/INT/CON/WIS/CHA
max_hp = 20 + CON
max_sp = 6 + INT // 2
initial weapon / armor -> inventory + equipment
difficulty preset -> copper / rations / threat interval
level = 1
xp = 0
xp_to_next = 100
adventurer_rank = F
unique_talent = one template talent, awakened = false
```

每个职业的完整属性、武器、护甲、伤害和减伤必须从当前 `persistence.py` 的 `ARCHETYPES` 与 `DEFAULT_ARCHETYPE` 逐项抄录并用 fixture 固定，不能凭摘要补值。

难度预设至少核对初始铜币、口粮、药水、威胁间隔、难度 ID、显示名和描述。敌人倍率属于生成/战斗阶段，不能在角色初始化时提前应用。

模板天赋可由 AP 初始化器选出类型并写入 `uniqueTalent`。AI 可以提出名称或描述候选，但候选在通过内容校验并被采纳前不是权威事实；`awakened` 初值为 `false`。

## 6. AP Runtime 契约

AP runtime 应提供类似能力：

```java
ApState initialize(ApInitializationRequest request, WorldDefinition world);
```

`ApAction` 必须是封闭的类型化动作集合，至少区分学习技能/法术、使用物品、休息恢复、商店交易、任务奖励、战斗开始/行动/结束和 AI 提案转化后的受限效果。

动作参数必须有边界校验。未知动作、未知能力、负数量、资源不足、非法目标和不属于当前战斗的角色都返回拒绝原因，不能部分应用。

结算返回唯一的下一份 `ApState` 和有序 `ApEvent` 列表。拒绝动作不改变状态 revision 或资源；接受动作一次性产生下一状态。事件只包含结构化确认事实，不包含未经确认的 AI 文本。

## 7. 数值与随机语义

- 保留 Python 整数截断和 floor division；负数 floor division 单独实现并测试。
- 明确零除、溢出、负资源、负伤害、HP/SP 上限和资源下限。
- 结算顺序必须与 Python 一致，尤其是减伤、护盾、HP 下限、资源扣除和奖励发放。
- 不用 `double` 保存生命、伤害、金币、经验或 AP 最终值；倍率计算必须明确取整位置。
- 规则随机应在调用边界显式提供随机源或直接保存最终选择；本阶段不建设整局回放。
- AI 叙事不是规则结果；AI 提案必须转换为 `ApAction`，经 `validate` 和 `resolve` 后才能更新 `ApState`。

## 8. 迁移顺序

### Phase 0：规则审计

- [ ] 盘点 AP 状态字段、默认值、所有写入点和玩家可见投影。
- [ ] 盘点 `round`、`//`、截断、clamp、零除和异常处理。
- [ ] 为职业、难度和天赋初始化生成规范化 Python fixture。
- **完成条件：** 每个初始字段和计算公式都有源码位置和测试样例。

### Phase 1：类型与初始化

- [x] 定义 `ApState`、角色属性、资源、物品、装备、能力和天赋记录类型。
- [x] 定义 `ApInitializationRequest` 和 `ApProjection`。
- [x] 实现 AP 初始化器并接入 `GameInstance.rulesetState`。
- **完成条件：** 四个职业和四种难度的初始化 fixture 逐字段通过。

### Phase 2：每日结算与资源

- [ ] 迁移每日结算、恢复、消耗、价格修正和威胁推进的 AP 相关部分。
- [x] 明确 WorldState 与 ApState 的写入边界。
- [x] 测试资源不足和每日推进的边界行为。
- **完成条件：** 每日结算作为完整垂直切片可验证并可保存。

### Phase 3：成长、物品和任务

- [ ] 迁移经验、升级、技能/法术学习、装备和物品使用。
- [ ] 迁移任务奖励和资源提案的规则校验。
- [ ] 为每个动作补充拒绝和成功测试。
- **完成条件：** 角色成长和资源变化不依赖 AI 文本即可完成。

### Phase 4：战斗

- [ ] 将 `battle.py` 的战斗快照、AP 消耗、行动顺序、伤害和结算事件迁移为类型化模型。
- [ ] 迁移怪物生成的数值边界和区域池约束。
- [ ] 保护角色、WorldState 和账本的原子更新。
- **完成条件：** 战斗 fixture 和关键边界用例通过，AI 只叙述确认事件。

## 9. 测试策略

- 每个公式和边界一个聚焦 JUnit 测试；不测试 AI 具体文案。
- 测试动作拒绝时状态完全不变、集合不可变、revision 递增和事件顺序。
- Python fixture 保存输入、基线标识、动作前状态、动作、下一状态、结构化事件和关键投影。
- 字段比较可忽略明确标记的时间戳、路径和展示文案；规则数值、资源、HP、AP、能力和装备不能忽略。
- 集成测试覆盖 `GameInstance` 初始化/保存/读取后 `ApState` 一致、runtime 选择正确、API 不能提交任意状态 patch，以及保存失败不返回已确认结果。
- `ArchitectureTest` 保持通过，ruleset 不依赖 Spring、API 或 persistence。

## 10. 完成标准

- `GameInstance.rulesetState` 是类型化 `ApState`；
- 四个职业和四种难度可以创建有效初始状态；
- 初始 HP、SP、属性、装备、资源和天赋与 Python 基线一致；
- 每日结算和至少一个资源动作通过规则集验证与结算；
- 保存/读取后状态不丢失；
- AI 只能生成叙事和受限提案，不能直接写状态；
- JUnit、Python fixture、架构测试和 `mvn verify` 通过；
- 玩家可见修正记录在 `docs/MIGRATION_DECISIONS.md`。

## 11. 明确不做

- 不把 AP 规则抽象成通用 DSL；
- 不把规则状态塞进 JSON map；
- 不让 AI 决定 HP、伤害、金币、经验或 AP 最终值；
- 不在 AP 迁移中引入多人、认证、Redis 或分布式事件总线；
- 不以重新生成 AI 文本作为规则复现或测试依据。

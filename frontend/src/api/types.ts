export type Lifecycle = 'INITIALIZED' | 'ACTIVE' | 'PAUSED' | 'COMPLETED'
export type InitializationValue =
  | { type: 'text'; value: string }
  | { type: 'integer'; value: number }
  | { type: 'decimal'; value: number }
  | { type: 'flag'; value: boolean }
export interface GameSelection { worldId: string; engineId: string | null }
export interface GameCatalog {
  worlds: { id: string; version: string; name: string; description: string; type: string; templateId: string; editable: boolean }[]
  engines: { id: string; version: string; displayName: string }[]
}
export interface WorldbookDraft {
  id?: string
  name: string
  type: string
  templateId: string
  tone: 'light' | 'balanced' | 'hardcore'
  descriptionHint: string
  factions: { type: string; desc_hint: string }[]
  originContexts: string[]
  worldTropes: string[]
  adventurerGuild?: Record<string, JsonValue>
}
export interface WorldbookResponse { worldbookId: string }
/** 世界书的完整叙事内容，用于管理页编辑。 */
export interface WorldbookDetail {
  id: string
  name: string
  type: string
  templateId: string
  tone: 'light' | 'balanced' | 'hardcore'
  descriptionHint: string
  factions: { type: string; desc_hint: string }[]
  originContexts: string[]
  worldTropes: string[]
}
export interface ConfirmedEvent { domain: string; type: string; subject: string; target: string | null; amount: number; outcome: string }
/** 直接动作接口返回的引擎确认事件。 */
export interface EngineConfirmedEvent { source: string; type: string; description: string; amount: number; subject: string; target: string }
/** 展示与叙事模型参考用的对话消息，不代表权威游戏状态。 */
export interface ConversationMessage { role: 'player' | 'gm'; text: string }
/** 叙事回合：权威实例、叙事文本、候选行动与本次结算确认事件。 */
export interface NarrativeResponse { game: GameInstanceResponse; narration: string; options: string[]; confirmedEvents: ConfirmedEvent[] }
/** 世界期限事件及其已确认的结果。 */
export interface TimedWorldEvent { id: string; kind: string; description: string; answer: string; consequence: string; registeredDay: number; deadlineDay: number; daysRemaining: number; status: 'ACTIVE' | 'RESOLVED' | 'EXPIRED'; materialId: string; outcome: string; resolvedDay: number }
/** 已发生的世界事件事实。 */
export interface WorldEvent { id: string; day: number; type: 'DEADLINE'; description: string; materialId: string }
/** 按分类应用的价格倍率。 */
export interface WorldPriceModifier { category: string; percent: number; source: string; expiresDay: number }
/** 引擎中立的权威世界状态。 */
export interface WorldState { worldId: string; contentVersion: string; revision: number; setting: string | null; settingDescription: string | null; location: string | null; visitedLocations: string[]; currentRegion: string | null; mainQuest: string | null; day: number; time: string | null; flags: Record<string, boolean>; originScene: string; deadlineEvents: TimedWorldEvent[]; worldEvents: WorldEvent[]; activePriceModifiers: Record<string, WorldPriceModifier> }
export type JsonValue = string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue }
export interface GameInstanceResponse { gameId: string; worldId: string; contentVersion: string; rulesetId: string | null; rulesetVersion: string | null; lifecycle: Lifecycle; revision: number; world: WorldState; rulesetState: { [key: string]: JsonValue } | null; confirmedEvents: EngineConfirmedEvent[] }
/** 后端错误响应的提示、稳定错误码和可选请求 ID。 */
export interface ApiErrorPayload { error?: string; code?: string; requestId?: string }
export interface NarrativeSettings { apiKey: string; enabled: boolean; baseUrl: string; model: string; maxTokens: number }
export interface SaveMetadata { gameId: string; worldId: string; engineId: string | null; day: number; revision: number; updatedAt: string }
export interface InitializationQuestion { fieldId: string; prompt: string; options: string[] }
export interface InitializationTurn { narration: string; options: string[]; collected: Record<string, string | number | boolean>; done: boolean }
export interface InitializationSession { id: string; turn: InitializationTurn }
export interface InitializationAnswerResult { done: boolean; turn: InitializationTurn }
export interface InitializationFinalizeResult { game: GameInstanceResponse; opening: NarrativeResponse }

/** 世界构建回合；配置中的列表保持结构化。 */
export interface WorldbuildTurn {
  narration: string
  options: string[]
  collected: Record<string, JsonValue>
  done: boolean
}
export interface WorldbuildSession { id: string; turn: WorldbuildTurn }

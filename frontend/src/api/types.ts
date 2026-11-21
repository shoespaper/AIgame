export type Lifecycle = 'INITIALIZED' | 'ACTIVE' | 'PAUSED' | 'COMPLETED'
export type InitializationValue =
  | { type: 'text'; value: string }
  | { type: 'integer'; value: number }
  | { type: 'decimal'; value: number }
  | { type: 'flag'; value: boolean }
export interface GameSelection { worldId: string; engineId: string | null }
export interface GameCatalog { worlds: { id: string; version: string; name: string; description: string }[]; engines: { id: string; version: string; displayName: string }[] }
export interface ActionArgument { name: string; value: InitializationValue }
export interface ActionIntent { type: string; target: string | null; arguments: ActionArgument[] }
export interface NarrativeOption { id: string; label: string; intent: ActionIntent | null }
export interface NarrativeProposalCandidate { type: string; fields: Record<string, string> }
export interface NarrativeProposalPreview { type: string; convertible: boolean; reason: string }
export interface NarrativeInitializationQuestion { fieldId: string; prompt: string; options: string[] }
export interface NarrativeParseCandidate { fieldId: string; value: InitializationValue; confidence: number }
export interface NarrativeMediaRequest { kind: string; prompt: string; assetId: string | null }
export interface ConfirmedEvent { domain: string; type: string; subject: string; target: string | null; amount: number; outcome: string }
export interface NarrativeResponse { gameId: string; revision: number; narration: string; mediaRequests: NarrativeMediaRequest[]; proposals: NarrativeProposalCandidate[]; proposalPreviews: NarrativeProposalPreview[]; options: NarrativeOption[]; initializationQuestions: NarrativeInitializationQuestion[]; parseCandidates: NarrativeParseCandidate[] }
export interface NarratedActionResponse extends Omit<NarrativeResponse, 'gameId' | 'revision'> { game: GameInstanceResponse; confirmedEvents: ConfirmedEvent[] }
export interface WorldState { worldId: string; contentVersion: string; revision: number; setting: string | null; settingDescription: string | null; location: string | null; currentRegion: string | null; mainQuest: string | null; day: number; time: string | null; season: string | null; weather: string | null; flags: Record<string, boolean>; threatLevel: string; threatCounter: number; threatInterval: number; threatHistory: string[]; timedEvents: string[]; incidentalEvents: string[]; lastTickDay: number; crisisTriggered: boolean; mainPlotStage: number; mainPlotStages: string[]; priceModifiers: Record<string, number>; originScene: string }
export type JsonValue = string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue }
export interface GameInstanceResponse { gameId: string; worldId: string; contentVersion: string; rulesetId: string | null; rulesetVersion: string | null; lifecycle: Lifecycle; revision: number; world: WorldState; rulesetState: { [key: string]: JsonValue } | null }
export interface ApiErrorPayload { error?: string }
export interface NarrativeSettings { apiKey: string; enabled: boolean; baseUrl: string; model: string }
export interface SaveMetadata { gameId: string; worldId: string; engineId: string | null; day: number; revision: number; updatedAt: string }
export interface InitializationQuestion { fieldId: string; prompt: string; options: string[] }
export interface InitializationParseCandidate { fieldId: string; value: InitializationValue; confidence: number }
export interface InitializationTurn { narration: string; question: InitializationQuestion | null; parseCandidates: InitializationParseCandidate[]; collected: Record<string, string | number | boolean>; done: boolean }
export interface InitializationSession { id: string; turn: InitializationTurn }
export interface InitializationAnswerResult { done: boolean; turn: InitializationTurn }

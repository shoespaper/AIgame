import type { ActionIntent, ApiErrorPayload, GameCatalog, GameInstanceResponse, GameSelection, NarratedActionResponse, NarrativeOption, NarrativeProposalCandidate, NarrativeResponse, WorldState, NarrativeSettings, SaveMetadata, InitializationSession, InitializationAnswerResult } from './types'

const apiRoot = (import.meta.env.VITE_API_BASE_URL || '/api').replace(/\/$/, '')

export class ApiError extends Error {
  constructor(message: string, public readonly status: number) {
    super(message)
    this.name = 'ApiError'
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  let response: Response
  try {
    response = await fetch(`${apiRoot}${path}`, { ...init, headers })
  } catch {
    throw new ApiError('无法连接到游戏服务器，请确认 Spring Boot 服务正在运行。', 0)
  }
  const text = await response.text()
  let data: unknown = null
  if (text) {
    try { data = JSON.parse(text) as unknown } catch { data = text }
  }
  if (!response.ok) {
    const message = typeof data === 'object' && data !== null && 'error' in data
      ? String((data as ApiErrorPayload).error || '请求失败')
      : typeof data === 'string' && data ? data : `请求失败（${response.status}）`
    throw new ApiError(message, response.status)
  }
  return data as T
}

function gamePath(gameId: string): string { return `/games/${encodeURIComponent(gameId)}` }
export function health(): Promise<{ status: string }> { return request<{ status: string }>('/health') }
export function loadGameCatalog(): Promise<GameCatalog> { return request<GameCatalog>('/games/initialization/catalog') }
export function loadGame(gameId: string): Promise<GameInstanceResponse> { return request<GameInstanceResponse>(gamePath(gameId)) }
export function saveGame(gameId: string, expectedRevision: number): Promise<void> {
  return request<void>(`${gamePath(gameId)}/save`, { method: 'POST', body: JSON.stringify({ expectedRevision }) })
}
/** 使用引擎中立的 ActionIntent 形状提交动作。 */
/** @param gameId 当前游戏标识 */
/** @param expectedRevision 本地持有的服务端修订号 */
/** @param intent 动作类型、目标和类型化参数 */
export function applyAction(gameId: string, expectedRevision: number, intent: Pick<ActionIntent, 'type' | 'target' | 'arguments'>): Promise<GameInstanceResponse> {
  return request<GameInstanceResponse>(`${gamePath(gameId)}/actions`, { method: 'POST', body: JSON.stringify({ expectedRevision, ...intent }) })
}
export function loadWorldProjection(gameId: string): Promise<WorldState> { return request<WorldState>(`${gamePath(gameId)}/save`) }
export function getNarrativeSettings(): Promise<NarrativeSettings> { return request<NarrativeSettings>('/settings/narrative') }
export function saveNarrativeSettings(settings: NarrativeSettings): Promise<NarrativeSettings> { return request<NarrativeSettings>('/settings/narrative', { method: 'PUT', body: JSON.stringify(settings) }) }
export function listGames(): Promise<SaveMetadata[]> { return request<SaveMetadata[]>('/games') }
export function createInitializationSession(selection: GameSelection): Promise<InitializationSession> { return request<InitializationSession>('/initialization/sessions', { method: 'POST', body: JSON.stringify(selection) }) }
export function answerInitialization(id: string, answer: string): Promise<InitializationAnswerResult> { return request<InitializationAnswerResult>(`/initialization/sessions/${encodeURIComponent(id)}/answer`, { method: 'POST', body: JSON.stringify({ answer }) }) }
export function finalizeInitialization(id: string): Promise<GameInstanceResponse> { return request<GameInstanceResponse>(`/initialization/sessions/${encodeURIComponent(id)}/finalize`, { method: 'POST' }) }
export function narrate(gameId: string, expectedRevision: number, playerInput: string): Promise<NarrativeResponse> {
  return request<NarrativeResponse>(`${gamePath(gameId)}/narration`, { method: 'POST', body: JSON.stringify({ expectedRevision, playerInput, locale: 'zh-CN' }) })
}
export function chooseNarrativeOption(gameId: string, expectedRevision: number, option: NarrativeOption, playerInput: string): Promise<NarratedActionResponse> {
  return request<NarratedActionResponse>(`${gamePath(gameId)}/narration/options`, { method: 'POST', body: JSON.stringify({ expectedRevision, option, playerInput, locale: 'zh-CN' }) })
}
/** 应用服务端预览过的结构化提案。 */
/** @param gameId 当前游戏标识 */
/** @param expectedRevision 当前存档修订号 */
/** @param proposal 用户选择应用的提案 */
/** @param proposalIndex 提案在本轮响应中的索引 */
export function applyNarrativeProposal(gameId: string, expectedRevision: number, proposal: NarrativeProposalCandidate, proposalIndex: number): Promise<GameInstanceResponse> {
  return request<GameInstanceResponse>(`${gamePath(gameId)}/narration/proposals`, { method: 'POST', body: JSON.stringify({ expectedRevision, proposalIndex, proposal }) })
}

import type { ApiErrorPayload, GameCatalog, GameInstanceResponse, GameSelection, NarrativeResponse, WorldState, NarrativeSettings, SaveMetadata, InitializationSession, InitializationAnswerResult, InitializationFinalizeResult, WorldbookDraft, WorldbookDetail, WorldbookResponse, WorldbuildSession, WorldbuildTurn, ConversationMessage } from './types'

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
/** 保存结构化自定义世界书并返回其稳定标识。 */
export function saveWorldbook(draft: WorldbookDraft): Promise<WorldbookResponse> {
  return request<WorldbookResponse>('/games/initialization/worldbooks', { method: 'POST', body: JSON.stringify(draft) })
}
/** 读取单个世界书的完整叙事内容供编辑。
 * @param id 世界书标识
 */
export function getWorldbook(id: string): Promise<WorldbookDetail> {
  return request<WorldbookDetail>(`/games/initialization/worldbooks/${encodeURIComponent(id)}`)
}
/** 覆盖保存一个自定义世界书。
 * @param id 世界书标识
 * @param draft 更新后的世界书叙事内容
 */
export function updateWorldbook(id: string, draft: WorldbookDraft): Promise<WorldbookResponse> {
  return request<WorldbookResponse>(`/games/initialization/worldbooks/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(draft) })
}
/** 删除一个自定义世界书。
 * @param id 世界书标识
 */
export function deleteWorldbook(id: string): Promise<void> {
  return request<void>(`/games/initialization/worldbooks/${encodeURIComponent(id)}`, { method: 'DELETE' })
}
export function loadGame(gameId: string): Promise<GameInstanceResponse> { return request<GameInstanceResponse>(gamePath(gameId)) }
export function saveGame(gameId: string, expectedRevision: number): Promise<void> {
  return request<void>(`${gamePath(gameId)}/save`, { method: 'POST', body: JSON.stringify({ expectedRevision }) })
}
export function loadWorldProjection(gameId: string): Promise<WorldState> { return request<WorldState>(`${gamePath(gameId)}/save`) }
export function getNarrativeSettings(): Promise<NarrativeSettings> { return request<NarrativeSettings>('/settings/narrative') }
export function saveNarrativeSettings(settings: NarrativeSettings): Promise<NarrativeSettings> { return request<NarrativeSettings>('/settings/narrative', { method: 'PUT', body: JSON.stringify(settings) }) }
export function listGames(): Promise<SaveMetadata[]> { return request<SaveMetadata[]>('/games') }
export function createInitializationSession(selection: GameSelection): Promise<InitializationSession> { return request<InitializationSession>('/initialization/sessions', { method: 'POST', body: JSON.stringify(selection) }) }
export function answerInitialization(id: string, answer: string): Promise<InitializationAnswerResult> { return request<InitializationAnswerResult>(`/initialization/sessions/${encodeURIComponent(id)}/answer`, { method: 'POST', body: JSON.stringify({ answer }) }) }
export function finalizeInitialization(id: string): Promise<InitializationFinalizeResult> { return request<InitializationFinalizeResult>(`/initialization/sessions/${encodeURIComponent(id)}/finalize`, { method: 'POST' }) }
/** 将当前输入与近期非权威对话上下文提交给 GM。 */
/** @param gameId 当前游戏标识 @param expectedRevision 客户端观察到的游戏修订号 @param playerInput 玩家本轮输入 */
/** @param history 仅用于叙事连贯性的近期玩家与 GM 消息 */
export function narrate(gameId: string, expectedRevision: number, playerInput: string, history: ConversationMessage[] = []): Promise<NarrativeResponse> {
  return request<NarrativeResponse>(`${gamePath(gameId)}/narration`, { method: 'POST', body: JSON.stringify({ expectedRevision, playerInput, locale: 'zh-CN', history: history.slice(-40) }) })
}

/** 创建世界构建 AI 会话。
 * @param templateId 可空的优先复用模板标识
 */
export function createWorldbuildSession(templateId?: string): Promise<WorldbuildSession> {
  return request('/initialization/worldbuild/sessions', { method: 'POST', body: JSON.stringify({ templateId }) })
}
/** 回答世界构建 GM 问题。
 * @param id 世界构建会话标识
 * @param answer 玩家自然语言回答
 */
export function answerWorldbuild(id: string, answer: string): Promise<{ done: boolean; turn: WorldbuildTurn }> {
  return request(`/initialization/worldbuild/sessions/${encodeURIComponent(id)}/answer`, { method: 'POST', body: JSON.stringify({ answer }) })
}

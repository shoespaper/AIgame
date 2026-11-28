import { ref } from 'vue'
import type { CreationProgress, NarrationStreamEvent, NarrationTurnRequest } from './types'
import type { ApiErrorPayload, GameCatalog, GameInstanceResponse, GameSelection, NarrativeResponse, WorldState, NarrativeSettings, SaveMetadata, InitializationSession, CheckedInitialization, InitializationFormValues, InitializationFinalizeResult, WorldbookDraft, WorldbookDetail, WorldbookResponse, WorldbuildSession, WorldbuildTurn } from './types'

const apiRoot = (import.meta.env.VITE_API_BASE_URL || '/api').replace(/\/$/, '')

export const lastFailedRequestId = ref<string | null>(null)

const apiErrorMessages: Readonly<Record<string, string>> = Object.freeze({
  ENGINE_REQUIRED: '当前游戏未选择引擎，无法执行该动作。',
  MODEL_CONFIGURATION_REQUIRED: '请在设置中启用并配置叙事模型。',
  PERSISTENCE_FAILED: '读取或写入游戏数据失败。',
  SAVE_NOT_FOUND: '这份存档已不存在。',
  WORLD_CONTENT_UNAVAILABLE: '服务器无法读取世界内容。',
  INTERNAL_ERROR: '服务器处理请求时出现错误。',
})

/** 保存 API 错误的 HTTP 状态与可用于排障的响应字段。 */
export class ApiError extends Error {
  /** @param message 界面显示的错误提示
   * @param status HTTP 状态码，未收到响应时为 0
   * @param code 后端返回的稳定错误码，可空
   * @param requestId 后端生成的请求 ID，可空
   */
  constructor(message: string, public readonly status: number,
              public readonly code?: string, public readonly requestId?: string) {
    super(message)
    this.name = 'ApiError'
  }
}

/** 发送 HTTP 请求，并将网络错误和失败响应统一转换为 ApiError。
 * @param path 相对 API 基址的请求路径
 * @param init 可选的请求方法、请求头和请求体
 */
async function fetchApi(path: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers)
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  let response: Response
  try {
    response = await fetch(`${apiRoot}${path}`, { ...init, headers })
  } catch {
    throw new ApiError('无法连接到游戏服务器，请确认 Spring Boot 服务正在运行。', 0)
  }
  if (response.ok) return response

  const data = await response.json().catch(() => null) as ApiErrorPayload | null
  const requestId = data?.requestId || response.headers.get('X-Request-Id') || undefined
  if (requestId) lastFailedRequestId.value = requestId
  if (!data?.error && !requestId && [500, 502, 503, 504].includes(response.status)) {
    throw new ApiError('无法连接到游戏服务器，请确认 Spring Boot 服务正在运行。', response.status)
  }
  const message = data?.code && Object.hasOwn(apiErrorMessages, data.code) ? apiErrorMessages[data.code] : data?.error
  throw new ApiError(message || `请求失败（${response.status}）`, response.status, data?.code, requestId)
}

/** 读取普通 API 的 JSON 或空响应。
 * @param path 相对 API 基址的请求路径
 * @param init 可选的请求方法、请求头和请求体
 */
async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetchApi(path, init)
  const text = await response.text()
  if (!text) return null as T
  try { return JSON.parse(text) as T } catch { return text as T }
}

/** 从日志导出接口下载后端生成的 .log 附件。 */
export async function downloadLogs(): Promise<void> {
  const response = await fetchApi('/settings/logs/export')
  const disposition = response.headers.get('Content-Disposition') || ''
  const fileName = disposition.match(/filename="?([a-zA-Z0-9.-]+\.log)"?(?:;|$)/i)?.[1]
  if (!response.headers.get('Content-Type')?.toLowerCase().startsWith('text/plain')) {
    const requestId = response.headers.get('X-Request-Id') || undefined
    if (requestId) lastFailedRequestId.value = requestId
    throw new ApiError('服务器返回的日志格式无效。', response.status, undefined, requestId)
  }
  const url = URL.createObjectURL(await response.blob())
  const link = document.createElement('a')
  link.href = url
  link.download = fileName || 'aigame-logs.log'
  document.body.append(link)
  try {
    link.click()
  } finally {
    link.remove()
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
  }
}

function gamePath(gameId: string): string { return `/games/${encodeURIComponent(gameId)}` }
export function health(): Promise<{ status: string }> { return request<{ status: string }>('/health') }
export function loadGameCatalog(): Promise<GameCatalog> { return request<GameCatalog>('/games/initialization/catalog') }
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
/** 永久删除指定游戏的服务端存档。
 * @param gameId 游戏存档标识
 */
export async function deleteGame(gameId: string): Promise<void> {
  await fetchApi(gamePath(gameId), { method: 'DELETE' })
}
export function loadWorldProjection(gameId: string): Promise<WorldState> { return request<WorldState>(`${gamePath(gameId)}/save`) }
export function getNarrativeSettings(): Promise<NarrativeSettings> { return request<NarrativeSettings>('/settings/narrative') }
export function saveNarrativeSettings(settings: NarrativeSettings): Promise<NarrativeSettings> { return request<NarrativeSettings>('/settings/narrative', { method: 'PUT', body: JSON.stringify(settings) }) }
export function listGames(): Promise<SaveMetadata[]> { return request<SaveMetadata[]>('/games') }
/** 加载所选世界和规则对应的角色表单。
 * @param selection 本局的世界和可选引擎
 */
export function createInitializationSession(selection: GameSelection): Promise<InitializationSession> {
  return request('/initialization/sessions', { method: 'POST', body: JSON.stringify(selection) })
}
/** 由服务端校验整份角色表单。
 * @param id 表单会话标识
 * @param profile 通用角色字段
 * @param engineConfig 引擎配置字段，纯叙事时为空
 */
export function validateInitialization(id: string, profile: InitializationFormValues, engineConfig: InitializationFormValues): Promise<CheckedInitialization> {
  return request(`/initialization/sessions/${encodeURIComponent(id)}/validate`, { method: 'POST', body: JSON.stringify({ profile, engineConfig }) })
}
/** 确认已通过校验的角色资料并生成开场。
 * @param id 表单会话标识
 * @param version 玩家核对过的校验版本
 * @param onProgress 接收模板尝试和开场阶段
 */
export function finalizeInitialization(id: string, version: number, onProgress: (progress: CreationProgress) => void): Promise<InitializationFinalizeResult> {
  return streamCreation(`/initialization/sessions/${encodeURIComponent(id)}/finalize/stream`, { version }, onProgress)
}
/** 发送或恢复流式叙事回合，按完整 SSE 事件读取 UTF-8 响应。
 * @param turn 本轮不变的请求数据
 * @param onProgress 消费一次失败提示或已结算回执
 */
export async function streamNarration(turn: NarrationTurnRequest, onProgress: (event: NarrationStreamEvent) => void): Promise<NarrativeResponse> {
  const response = await fetchApi(`${gamePath(turn.gameId)}/narration/turns/${encodeURIComponent(turn.id)}`, {
    method: 'POST',
    headers: { Accept: 'text/event-stream' },
    body: JSON.stringify({ expectedRevision: turn.expectedRevision, playerInput: turn.playerInput, choiceId: turn.choiceId, history: turn.history, locale: 'zh-CN' }),
  })
  return readStream<NarrativeResponse, NarrationStreamEvent>(response, onProgress, '回合连接已中断，正在等待确认结果。')
}

/** 发起创建流程并消费独立于最终业务响应的进度。
 * @param path 创建流程的 SSE 路径
 * @param payload 已校验的用户输入或确认版本
 * @param onProgress 处理生成进度和失败提示的回调
 */
async function streamCreation<T>(path: string, payload: object, onProgress: (progress: CreationProgress) => void): Promise<T> {
  const response = await fetchApi(path, {
    method: 'POST', headers: { Accept: 'text/event-stream' }, body: JSON.stringify(payload),
  })
  return readStream<T, { type: 'progress'; progress: CreationProgress }>(response, event => onProgress(event.progress),
    '生成连接已中断，请重试。')
}

/** 读取 SSE 的完整事件并返回成功结果。
 * @param response 已打开的 SSE 响应
 * @param onProgress 处理非终止事件的回调
 * @param disconnectMessage 响应未完成时的界面提示
 */
async function readStream<T, P extends { type: string }>(response: Response, onProgress: (event: P) => void,
  disconnectMessage: string): Promise<T> {
  const requestId = response.headers.get('X-Request-Id') ?? undefined
  const disconnected = () => new ApiError(disconnectMessage, 0, 'RESULT_UNCONFIRMED', requestId)
  if (!response.body || !response.headers.get('Content-Type')?.startsWith('text/event-stream')) throw disconnected()

  const reader = response.body.pipeThrough(new TextDecoderStream()).getReader()
  let buffer = ''
  try {
    for (;;) {
      const { value, done } = await reader.read()
      if (done) throw disconnected()
      buffer += value
      let separator = /\r?\n\r?\n/.exec(buffer)
      while (separator) {
        const data = buffer.slice(0, separator.index).split(/\r?\n/)
          .filter(line => line.startsWith('data:')).map(line => line.slice(5).replace(/^ /, '')).join('\n')
        buffer = buffer.slice(separator.index + separator[0].length)
        if (data) {
          const event = JSON.parse(data) as { type: string; result?: T; code?: string; message?: string }
          if (event.type === 'complete' && event.result !== undefined) return event.result
          if (event.type === 'failed') {
            throw new ApiError(event.message || '操作未完成。', 200, event.code, requestId)
          }
          onProgress(event as P)
        }
        separator = /\r?\n\r?\n/.exec(buffer)
      }
    }
  } catch (cause) {
    if (cause instanceof ApiError) throw cause
    throw disconnected()
  } finally {
    void reader.cancel().catch(() => {})
  }
}

/** 创建世界构建 AI 会话。 */
export function createWorldbuildSession(): Promise<WorldbuildSession> {
  return request('/initialization/worldbuild/sessions', { method: 'POST' })
}
/** 提交世界描述或修改意见。
 * @param id 世界构建会话标识
 * @param answer 玩家自然语言回答
 * @param onProgress 接收草案生成和 Judge 验收进度
 */
export function answerWorldbuild(id: string, answer: string, onProgress: (progress: CreationProgress) => void): Promise<{ done: boolean; turn: WorldbuildTurn }> {
  return streamCreation(`/initialization/worldbuild/sessions/${encodeURIComponent(id)}/answer/stream`, { answer }, onProgress)
}

/** 保存指定的已验收草案版本。
 * @param id 世界构建会话标识
 * @param version 玩家当前看到的草案版本
 */
export function saveWorldbuild(id: string, version: number): Promise<WorldbookResponse> {
  return request(`/initialization/worldbuild/sessions/${encodeURIComponent(id)}/save`, { method: 'POST', body: JSON.stringify({ version }) })
}

import type { ApiErrorPayload, GameInstanceResponse, InitializePayload, WorldState } from './types'

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
export function initializeGame(gameId: string, payload: InitializePayload): Promise<GameInstanceResponse> {
  return request<GameInstanceResponse>(`${gamePath(gameId)}/initialize`, { method: 'POST', body: JSON.stringify(payload) })
}
export function loadGame(gameId: string): Promise<GameInstanceResponse> { return request<GameInstanceResponse>(gamePath(gameId)) }
export function saveGame(gameId: string, expectedRevision: number): Promise<void> {
  return request<void>(`${gamePath(gameId)}/save`, { method: 'POST', body: JSON.stringify({ expectedRevision }) })
}
export type ActionType = 'rest' | 'consume_ration' | 'use_potion' | 'advance_day'
export function applyAction(gameId: string, expectedRevision: number, type: ActionType): Promise<GameInstanceResponse> {
  return request<GameInstanceResponse>(`${gamePath(gameId)}/actions`, { method: 'POST', body: JSON.stringify({ expectedRevision, type }) })
}
export function loadWorldProjection(gameId: string): Promise<WorldState> { return request<WorldState>(`${gamePath(gameId)}/save`) }

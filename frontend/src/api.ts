import type { CustomExtension, ErrorBody, ErrorCode, ExtensionView, FixedExtension, UploadResult } from './types'

/** 서버 에러 응답 {code, message, details}. message는 사람용이라 화면에 그대로 표시한다. */
export class ApiError extends Error {
  readonly code: ErrorCode
  readonly details?: Record<string, unknown>

  constructor(code: ErrorCode, message: string, details?: Record<string, unknown>) {
    super(message)
    this.code = code
    this.details = details
  }
}

const networkError = () =>
  new ApiError('NETWORK_ERROR', '서버에 연결할 수 없습니다. 네트워크를 확인하고 다시 시도하세요.')

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let res: Response
  try {
    res = await fetch(path, init)
  } catch {
    throw networkError() // 연결 실패, 크기 초과로 연결이 끊긴 경우 등
  }
  if (res.status === 204) {
    return undefined as T
  }
  let body: unknown
  try {
    body = await res.json()
  } catch {
    throw networkError() // JSON이 아닌 응답(프록시 에러 페이지 등)
  }
  if (!res.ok) {
    const error = body as ErrorBody
    throw new ApiError(error.code, error.message, error.details)
  }
  return body as T
}

const json = (method: string, body: unknown): RequestInit => ({
  method,
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
})

export const getExtensions = () => request<ExtensionView>('/api/extensions')

export const setFixedBlocked = (id: number, blocked: boolean) =>
  request<FixedExtension>(`/api/extensions/fixed/${id}`, json('PUT', { blocked }))

/** 입력값을 그대로 보낸다 — 정규화의 기준은 서버. */
export const addCustom = (name: string) => request<CustomExtension>('/api/extensions/custom', json('POST', { name }))

export const deleteCustom = (id: number) => request<void>(`/api/extensions/custom/${id}`, { method: 'DELETE' })

export function upload(file: File) {
  const form = new FormData()
  form.append('file', file)
  return request<UploadResult>('/api/uploads', { method: 'POST', body: form })
}

export const messageOf = (e: unknown) => (e instanceof ApiError ? e.message : '알 수 없는 오류가 발생했습니다.')

/** 서버 ErrorCode(§5-3) + 클라이언트 전용 NETWORK_ERROR. */
export type ErrorCode =
  | 'INVALID_REQUEST'
  | 'NOT_FOUND'
  | 'INVALID_EXTENSION'
  | 'EXTENSION_NOT_FOUND'
  | 'FIXED_EXTENSION_CONFLICT'
  | 'DUPLICATE_EXTENSION'
  | 'CUSTOM_LIMIT_EXCEEDED'
  | 'MISSING_FILE'
  | 'TOO_MANY_FILES'
  | 'INVALID_FILENAME'
  | 'FILE_TOO_LARGE'
  | 'BLOCKED_EXTENSION'
  | 'BLOCKED_CONTENT'
  | 'STORAGE_FAILED'
  | 'RATE_LIMITED'
  | 'INTERNAL_ERROR'
  | 'NETWORK_ERROR'

export interface ErrorBody {
  code: ErrorCode
  message: string
  details?: Record<string, unknown>
}

export interface FixedExtension {
  id: number
  name: string
  blocked: boolean
}

export interface CustomExtension {
  id: number
  name: string
  createdAt: string
}

export interface ExtensionView {
  fixed: FixedExtension[]
  custom: CustomExtension[]
  customLimit: number
  maxFileSize: number // 바이트
}

export interface UploadResult {
  key: string
  fileName: string
  size: number
}

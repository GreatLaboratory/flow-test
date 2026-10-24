/**
 * 클라이언트 검증 (UX 보조 전용) — 서버 ExtensionNormalizer와 같은 규칙의 사본.
 * 즉시 피드백만을 위한 것이고, 통과해도 서버가 다시 검증한다. 기준은 언제나 서버.
 */
const VALID = /^[a-z0-9]{1,20}$/

/** 정규화된 이름, 규칙 위반이면 null. */
export function normalizeExtension(raw: string): string | null {
  const name = raw.trim().replace(/^\.+/, '').toLowerCase()
  return VALID.test(name) ? name : null
}

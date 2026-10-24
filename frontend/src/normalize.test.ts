import { describe, expect, it } from 'vitest'
import { normalizeExtension } from './normalize'

// F1: 서버 ExtensionNormalizerTest(N1~N8)와 같은 입력 표. 기대값은 서버 스펙(DESIGN §6-2) 기준.
// null = 서버의 INVALID_EXTENSION
const cases: [string, string | null][] = [
  // N1 보정
  ['.SH ', 'sh'], ['sh', 'sh'], ['..sh', 'sh'], ['  .Sh', 'sh'], ['\tsh\n', 'sh'],
  // N2 비었음
  ['', null], ['.', null], ['...', null], ['   ', null],
  // N3 허용 밖 문자
  ['s h', null], ['s.h', null], ['tar.gz', null], ['ｅｘｅ', null], ['한글', null],
  ['ex-e', null], ['ex_e', null], ['exe;', null], ['../exe', null],
  // N5·N6 20자 경계(보정 후 길이)
  ['a'.repeat(20), 'a'.repeat(20)], ['a'.repeat(21), null],
  ['.' + 'a'.repeat(20), 'a'.repeat(20)], [' ' + 'a'.repeat(20) + ' ', 'a'.repeat(20)],
  // N7·N8 대문자, 켈빈 기호(Q3: 스펙대로 허용)
  ['INI', 'ini'], ['K', 'k'],
]

describe('normalizeExtension matches server rules', () => {
  it.each(cases)('%j -> %j', (raw, expected) => {
    expect(normalizeExtension(raw)).toBe(expected)
  })
})

// D1 (수용된 분기, VERIFICATION D1 / CONSIDERATIONS A-26): JS trim ≠ Java trim.
// 클라이언트는 원문을 보내고(A-18) 서버가 최종 판정하므로 보안 영향 없음. 분기가 바뀌면 알 수 있게 현재 동작을 고정한다.
describe('accepted client/server divergence (server is authoritative)', () => {
  it('\\u3000sh: client passes, server rejects with INVALID_EXTENSION', () => {
    expect(normalizeExtension('\u3000sh')).toBe('sh')
  })
  it('sh\\0: client rejects, server accepts as sh', () => {
    expect(normalizeExtension('sh\0')).toBeNull()
  })
})

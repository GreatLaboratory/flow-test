import { useState, type FormEvent } from 'react'
import { messageOf, upload } from '../api'

interface Props {
  maxFileSize: number // 서버 설정값(GET 응답) — 하드코딩 없음
}

const toMB = (bytes: number) => `${Math.round((bytes / 1024 / 1024) * 10) / 10}MB`

export default function UploadPanel({ maxFileSize }: Props) {
  const [file, setFile] = useState<File | null>(null)
  const [uploading, setUploading] = useState(false)
  const [result, setResult] = useState<{ ok: boolean; text: string } | null>(null)

  // 클라이언트 사전 검사는 크기만 (UX용). 크기 초과 시 서버가 연결을 끊어 413이 브라우저에 안 보일 수 있다.
  // 확장자 사전 판정은 의도적으로 하지 않는다 — 규칙 중복·오래된 정책 위험. 서버 차단 메시지가 곧 UX.
  const tooLarge = file !== null && file.size > maxFileSize

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (!file || tooLarge || uploading) return
    setUploading(true)
    setResult(null)
    try {
      const saved = await upload(file)
      setResult({ ok: true, text: `'${saved.fileName}' 업로드 성공 (${saved.size.toLocaleString()} bytes)` })
    } catch (err) {
      setResult({ ok: false, text: `'${file.name}' — ${messageOf(err)}` })
    } finally {
      setUploading(false)
    }
  }

  return (
    <section>
      <h2>파일 업로드</h2>
      <form className="row" onSubmit={submit}>
        <input
          type="file"
          aria-label="업로드할 파일"
          onChange={(e) => {
            setFile(e.target.files?.[0] ?? null)
            setResult(null)
          }}
        />
        <button type="submit" disabled={!file || tooLarge || uploading}>
          {uploading ? '업로드 중…' : '업로드'}
        </button>
      </form>
      <p className={tooLarge || (result && !result.ok) ? 'error' : 'ok'} aria-live="polite">
        {tooLarge ? `파일 크기는 최대 ${toMB(maxFileSize)}까지 가능합니다. (선택한 파일: ${toMB(file.size)})` : result?.text}
      </p>
    </section>
  )
}

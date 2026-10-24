import { useRef, useState, type FormEvent } from 'react'
import { addCustom, deleteCustom, messageOf } from '../api'
import { normalizeExtension } from '../normalize'
import type { CustomExtension } from '../types'

interface Props {
  items: CustomExtension[]
  limit: number
  onChanged: () => Promise<void>
}

export default function CustomExtensionPanel({ items, limit, onChanged }: Props) {
  const [input, setInput] = useState('')
  const [adding, setAdding] = useState(false)
  const [deleting, setDeleting] = useState<Set<number>>(new Set())
  const [message, setMessage] = useState<string | null>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const full = items.length >= limit

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (adding) return

    // 클라이언트 사전 검사 (UX용). 고정 확장자 충돌·동시 추가·상한은 서버가 최종 판정한다.
    const name = normalizeExtension(input)
    if (name === null) {
      setMessage('확장자는 영문 소문자와 숫자만, 1~20자로 입력하세요.')
      return
    }
    if (items.some((item) => item.name === name)) {
      setMessage(`'${name}'은(는) 이미 등록되어 있습니다.`)
      return
    }

    setAdding(true)
    setMessage(null)
    try {
      await addCustom(input)
      setInput('')
      await onChanged()
    } catch (err) {
      setMessage(messageOf(err))
    } finally {
      setAdding(false)
      inputRef.current?.focus()
    }
  }

  async function remove(item: CustomExtension) {
    setDeleting((prev) => new Set(prev).add(item.id))
    setMessage(null)
    try {
      await deleteCustom(item.id)
      await onChanged()
    } catch (err) {
      setMessage(messageOf(err))
    } finally {
      setDeleting((prev) => {
        const next = new Set(prev)
        next.delete(item.id)
        return next
      })
    }
  }

  return (
    <section>
      <h2>커스텀 확장자</h2>
      <form className="row" onSubmit={submit}>
        <input
          ref={inputRef}
          value={input}
          onChange={(e) => setInput(e.target.value)}
          maxLength={20}
          placeholder="확장자 입력 (예: sh)"
          aria-label="커스텀 확장자"
          disabled={full}
        />
        <button type="submit" disabled={full || adding}>
          +추가
        </button>
        <span className="count">
          {items.length}/{limit}
        </span>
      </form>
      <p className="error" aria-live="polite">
        {full ? `최대 ${limit}개까지 등록할 수 있습니다. 불필요한 항목을 삭제하세요.` : message}
      </p>
      <ul className="chips">
        {items.map((item) => (
          <li key={item.id}>
            {item.name}
            <button
              type="button"
              aria-label={`${item.name} 삭제`}
              disabled={deleting.has(item.id)}
              onClick={() => remove(item)}
            >
              ×
            </button>
          </li>
        ))}
      </ul>
    </section>
  )
}

import { useState } from 'react'
import { messageOf, setFixedBlocked } from '../api'
import type { FixedExtension } from '../types'

interface Props {
  items: FixedExtension[]
  onChanged: () => Promise<void>
}

export default function FixedExtensionPanel({ items, onChanged }: Props) {
  const [pending, setPending] = useState<Set<number>>(new Set())
  const [error, setError] = useState<string | null>(null)

  // 비관적 UI: 체크 상태는 서버 값(items)만 따른다. 요청 중엔 해당 체크박스를 잠가 응답 역전을 막는다.
  async function change(item: FixedExtension, blocked: boolean) {
    setPending((prev) => new Set(prev).add(item.id))
    setError(null)
    try {
      await setFixedBlocked(item.id, blocked)
      await onChanged()
    } catch (e) {
      setError(messageOf(e))
    } finally {
      setPending((prev) => {
        const next = new Set(prev)
        next.delete(item.id)
        return next
      })
    }
  }

  return (
    <section>
      <h2>고정 확장자</h2>
      <div className="row">
        {items.map((item) => (
          <label key={item.id} className="check">
            <input
              type="checkbox"
              checked={item.blocked}
              disabled={pending.has(item.id)}
              onChange={(e) => change(item, e.target.checked)}
            />
            {item.name}
          </label>
        ))}
      </div>
      <p className="error" aria-live="polite">
        {error}
      </p>
    </section>
  )
}

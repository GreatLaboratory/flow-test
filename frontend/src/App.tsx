import { useCallback, useEffect, useState } from 'react'
import { getExtensions, messageOf } from './api'
import type { ExtensionView } from './types'
import FixedExtensionPanel from './components/FixedExtensionPanel'
import CustomExtensionPanel from './components/CustomExtensionPanel'
import UploadPanel from './components/UploadPanel'

export default function App() {
  const [view, setView] = useState<ExtensionView | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)

  // 서버가 기준: 변경 성공 후·탭 복귀 시 전체 재조회
  const reload = useCallback(async () => {
    try {
      setView(await getExtensions())
      setLoadError(null)
    } catch (e) {
      setLoadError(messageOf(e))
    }
  }, [])

  useEffect(() => {
    reload()
    const onVisible = () => {
      if (document.visibilityState === 'visible') reload() // 다른 탭에서 바뀐 정책 반영
    }
    document.addEventListener('visibilitychange', onVisible)
    return () => document.removeEventListener('visibilitychange', onVisible)
  }, [reload])

  return (
    <main>
      <h1>파일 확장자 차단</h1>
      {loadError && (
        <p className="error" role="alert">
          {loadError} <button onClick={reload}>다시 시도</button>
        </p>
      )}
      {view ? (
        <>
          <FixedExtensionPanel items={view.fixed} onChanged={reload} />
          <CustomExtensionPanel items={view.custom} limit={view.customLimit} onChanged={reload} />
          <UploadPanel maxFileSize={view.maxFileSize} />
        </>
      ) : (
        !loadError && <p>불러오는 중…</p>
      )}
    </main>
  )
}

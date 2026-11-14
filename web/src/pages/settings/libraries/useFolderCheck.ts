import { useRef, useState } from 'react'
import { checkPath } from '../../../api/settings'
import type { components } from '../../../api/schema'

type PathCheck = components['schemas']['PathCheck']

/** A folder's check on the server: the last answer for the folder typed now,
 * or that the check itself failed. A newer check wins over an older one. */
export function useFolderCheck(initial: PathCheck | null = null, onResult?: (check: PathCheck | null) => void) {
  const [check, setCheck] = useState<PathCheck | null>(initial)
  const [checking, setChecking] = useState(false)
  const [failed, setFailed] = useState(false)
  const version = useRef(0)
  const run = async (path: string): Promise<PathCheck | null> => {
    const p = path.trim()
    if (!p) return null
    const v = ++version.current
    setFailed(false)
    setChecking(true)
    try {
      const result = await checkPath(p)
      if (v !== version.current) return null
      setCheck(result)
      onResult?.(result)
      return result
    } catch {
      if (v === version.current) {
        setCheck(null)
        setFailed(true)
      }
      return null
    } finally {
      if (v === version.current) setChecking(false)
    }
  }
  const clear = () => {
    version.current++
    setCheck(null)
    setFailed(false)
    setChecking(false)
  }
  return { check, checking, failed, run, clear }
}


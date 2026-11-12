import { useEffect, useRef, useState } from 'react'

const SCRIPT_URL = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit'

type TurnstileApi = {
  render: (el: HTMLElement, options: Record<string, unknown>) => string
  remove: (id: string) => void
}

declare global {
  interface Window {
    turnstile?: TurnstileApi
  }
}

let loading: Promise<TurnstileApi> | null = null

/**
 * Loads Cloudflare's script once. It fails when Cloudflare is unreachable or
 * blocked, or when this page was loaded before Turnstile was turned on (the
 * page's Content-Security-Policy allows Cloudflare only while it is on).
 */
function loadTurnstile(): Promise<TurnstileApi> {
  if (window.turnstile) return Promise.resolve(window.turnstile)
  loading ??= new Promise<TurnstileApi>((resolve, reject) => {
    const script = document.createElement('script')
    script.src = SCRIPT_URL
    script.async = true
    script.onload = () => (window.turnstile ? resolve(window.turnstile) : reject(new Error('Turnstile did not load')))
    script.onerror = () => {
      loading = null
      script.remove()
      reject(new Error('Turnstile did not load'))
    }
    document.head.appendChild(script)
  })
  return loading
}

/**
 * Cloudflare Turnstile, the check that the person signing in is not a bot.
 * onToken gets a token once the check passes, and null when it expires or
 * fails; onError also gets Cloudflare's error code. Tokens are single use:
 * change resetKey for a fresh one.
 */
export function Turnstile({
  siteKey,
  onToken,
  onError,
  resetKey = 0,
  action,
}: {
  siteKey: string
  onToken: (token: string | null) => void
  onError?: (code: string) => void
  resetKey?: number
  action?: string
}) {
  const box = useRef<HTMLDivElement>(null)
  const latest = useRef({ onToken, onError })
  const [blocked, setBlocked] = useState(false)

  useEffect(() => {
    latest.current = { onToken, onError }
  })

  useEffect(() => {
    let id: string | undefined
    let cancelled = false
    loadTurnstile()
      .then((ts) => {
        if (cancelled || !box.current) return
        setBlocked(false)
        id = ts.render(box.current, {
          sitekey: siteKey,
          action,
          theme: 'dark',
          'refresh-expired': 'auto',
          callback: (token: string) => latest.current.onToken(token),
          'expired-callback': () => latest.current.onToken(null),
          'error-callback': (code: string) => {
            latest.current.onToken(null)
            latest.current.onError?.(String(code))
          },
        })
      })
      .catch(() => {
        if (!cancelled) setBlocked(true)
      })
    return () => {
      cancelled = true
      if (id) window.turnstile?.remove(id)
    }
  }, [siteKey, action, resetKey])

  return (
    <div>
      <div ref={box} className="min-h-[65px]" />
      {blocked && (
        <p className="text-sm text-warn">
          Cloudflare&rsquo;s check that you&rsquo;re not a bot didn&rsquo;t load. Reload the page; if it still doesn&rsquo;t appear, Cloudflare may be unreachable or blocked by an extension.
        </p>
      )}
    </div>
  )
}

import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react'
import { api, ApiError, UNAUTHORIZED_EVENT, unwrap } from '../api/client'
import { ConfirmationCancelled, registerPasswordPrompt } from '../api/withConfirm'
import { primaryButton, secondaryButton, textInput } from './SettingsPanel'

/** One shared native modal, including above another action's native dialog. */
export function PasswordConfirmationHost({ name }: { name: string }) {
  const lastFocused = useRef<HTMLElement | null>(null)
  const [returnFocus, setReturnFocus] = useState<HTMLElement | null>(null)
  const [action, setAction] = useState<string | null>(null)
  const pending = useRef<{ promise: Promise<void>; resolve: () => void; reject: (error: Error) => void } | null>(null)
  useEffect(() => {
    const rememberFocus = (event: FocusEvent) => { if (!pending.current && event.target instanceof HTMLElement && event.target !== document.body) lastFocused.current = event.target }
    const rememberPointer = (event: PointerEvent) => {
      if (pending.current || !(event.target instanceof Element)) return
      const target = event.target.closest<HTMLElement>('button, a, input, select, textarea')
      if (target) lastFocused.current = target
    }
    document.addEventListener('focusin', rememberFocus)
    document.addEventListener('pointerdown', rememberPointer, true)
    const unregister = registerPasswordPrompt((purpose) => {
      if (pending.current) return pending.current.promise
      let resolve!: () => void, reject!: (error: Error) => void
      const promise = new Promise<void>((yes, no) => { resolve = yes; reject = no })
      pending.current = { promise, resolve, reject }
      const active = document.activeElement
      setReturnFocus(active instanceof HTMLElement && active !== document.body ? active : lastFocused.current)
      setAction(purpose)
      return promise
    })
    return () => { document.removeEventListener('focusin', rememberFocus); document.removeEventListener('pointerdown', rememberPointer, true); unregister(); pending.current?.reject(new ConfirmationCancelled()); pending.current = null }
  }, [])
  const finish = (confirmed: boolean) => {
    const request = pending.current
    pending.current = null
    setAction(null)
    if (confirmed) request?.resolve()
    else request?.reject(new ConfirmationCancelled())
  }
  return action === null ? null : <ConfirmPasswordDialog name={name} action={action} returnFocus={returnFocus} onFinish={finish} />
}

function ConfirmPasswordDialog({ name, action, returnFocus, onFinish }: { name: string; action: string; returnFocus: HTMLElement | null; onFinish: (confirmed: boolean) => void }) {
  const id = useId()
  const dialog = useRef<HTMLDialogElement>(null)
  const field = useRef<HTMLInputElement>(null)
  const alive = useRef(true)
  const [password, setPassword] = useState('')
  const [checking, setChecking] = useState(false)
  const [error, setError] = useState('')
  const [retryAt, setRetryAt] = useState(0)
  const [now, setNow] = useState(Date.now)
  const wait = Math.max(0, Math.ceil((retryAt - now) / 1000))
  useEffect(() => {
    alive.current = true
    const modal = dialog.current!
    const trigger = returnFocus
    modal.showModal()
    field.current?.focus()
    return () => {
      alive.current = false
      modal.close()
      requestAnimationFrame(() => {
        if (!trigger?.isConnected) return
        if (!trigger.matches(':disabled')) { trigger.focus(); return }
        const observer = new MutationObserver(() => {
          if (!trigger.isConnected || !trigger.matches(':disabled')) { observer.disconnect(); if (trigger.isConnected) trigger.focus() }
        })
        observer.observe(trigger, { attributes: true, attributeFilter: ['disabled'] })
        window.setTimeout(() => observer.disconnect(), 1000)
      })
    }
  }, [returnFocus])
  useEffect(() => {
    if (!retryAt) return
    const timer = window.setInterval(() => setNow(Date.now()), 250)
    return () => window.clearInterval(timer)
  }, [retryAt])
  const cancel = () => { if (!checking) onFinish(false) }
  return (
    <dialog ref={dialog} onKeyDown={trapDialogTab} aria-labelledby={`${id}-title`} aria-describedby={`${id}-description`}
      onCancel={(e) => { e.preventDefault(); cancel() }} onClick={(e) => { if (e.target === e.currentTarget) cancel() }}
      className="mx-auto my-6 max-h-[calc(100%-3rem)] w-[calc(100%-2rem)] max-w-md overflow-y-auto rounded-xl border border-line-2 bg-surface p-6 text-neutral-100 shadow-xl backdrop:bg-black/80 sm:my-10 sm:max-h-[calc(100%-5rem)]">
      <h2 id={`${id}-title`} className="text-xl font-semibold">Enter your password</h2>
      <p id={`${id}-description`} className="mt-2 text-sm text-neutral-400">You’re away from home. Enter your password to {action}.</p>
      <form className="mt-5 space-y-4" onSubmit={async (event) => {
        event.preventDefault()
        if (checking || wait) return
        setChecking(true); setError('')
        try {
          unwrap(await api.POST('/api/auth/confirm', { body: { password } }))
          if (alive.current) onFinish(true)
        } catch (err) {
          if (!alive.current) return
          if (err instanceof ApiError && err.status === 401 && err.message === 'sign in required') {
            window.dispatchEvent(new Event(UNAUTHORIZED_EVENT))
            return
          }
          if (err instanceof ApiError && err.status === 429) {
            setRetryAt(Date.now() + err.retryAfter * 1000); setNow(Date.now()); setError('Too many tries.')
          } else setError(err instanceof ApiError && err.status === 401 ? 'That password isn’t right. Try again.' : err instanceof Error ? err.message : 'Couldn’t check your password. Try again.')
        } finally { if (alive.current) { setChecking(false); field.current?.focus() } }
      }}>
        <label className="block" htmlFor={`${id}-password`}>
          <span className="mb-1.5 block text-sm text-neutral-300">Password for {name}</span>
          <input ref={field} id={`${id}-password`} type="password" autoComplete="current-password" required maxLength={256} value={password}
            onChange={(e) => setPassword(e.target.value)} readOnly={checking} aria-invalid={!!error} aria-describedby={error ? `${id}-error` : undefined} className={`${textInput} min-h-[44px] w-full`} />
        </label>
        {error && <p id={`${id}-error`} role="alert" className="text-sm text-warn">{error}{wait > 0 && ` Try again in ${wait} seconds.`}</p>}
        <p className="text-sm text-neutral-400">This browser won’t ask again for 10 minutes. This action is recorded in the server’s log.</p>
        <div className="flex justify-end gap-3 border-t border-line pt-4">
          <button type="button" className={`${secondaryButton} min-h-[44px] flex-1 sm:flex-none`} disabled={checking} onClick={cancel}>Cancel</button>
          <button type="submit" className={`${primaryButton} min-h-[44px] flex-1 sm:flex-none`} disabled={checking || wait > 0}>{checking ? 'Checking…' : 'Continue'}</button>
        </div>
      </form>
    </dialog>
  )
}

/** Native dialogs make the page inert; explicitly wrap Tab at their ends. */
function trapDialogTab(event: KeyboardEvent<HTMLDialogElement>) {
  if (event.key !== 'Tab') return
  const items = [...event.currentTarget.querySelectorAll<HTMLElement>('input:not([disabled]), button:not([disabled])')]
  const first = items[0], last = items.at(-1)
  if (event.shiftKey ? document.activeElement === first : document.activeElement === last) {
    event.preventDefault()
    ;(event.shiftKey ? last : first)?.focus()
  }
}

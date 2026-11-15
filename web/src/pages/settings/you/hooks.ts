import { useCallback, useEffect, useRef, useState } from 'react'
import type { Notice } from '../../../lib/youSettings'
import { useGo } from '../../../components/settings/hooks'

// Hooks the You tabs share (PLAN-ADMIN-SETTINGS.md §5.10).

// A one-step task ends with a banner on its page (§3.7). The task leaves the
// banner here and the page shows it once.
const flashes = new Map<string, Notice & { tone?: 'ok' | 'info' }>()

export function setFlash(page: string, notice: Notice & { tone?: 'ok' | 'info' }) {
  flashes.set(page, notice)
}

/** The banner a task left for this page, shown until the page is left. */
export function useFlash(page: string) {
  const [notice, setNotice] = useState(() => flashes.get(page) ?? null)
  useEffect(() => { flashes.delete(page) }, [page])
  return [notice, () => setNotice(null)] as const
}

export type RowSave<T> = { status: 'idle' | 'saving' | 'saved' | 'failed'; value?: T; message?: string }

/**
 * One row of choices that save at once (§3.6): only that row waits. While it
 * saves, the control shows the new value; a refusal puts it back and says why.
 */
export function useRowSave<T>() {
  const [rows, setRows] = useState<Record<string, RowSave<T>>>({})
  const alive = useRef(true)
  useEffect(() => () => { alive.current = false }, [])
  const run = useCallback(async (key: string, value: T, save: () => Promise<unknown>, saved: string, failed: (error: unknown) => string) => {
    setRows((r) => ({ ...r, [key]: { status: 'saving', value } }))
    try {
      await save()
      if (alive.current) setRows((r) => ({ ...r, [key]: { status: 'saved', message: saved } }))
    } catch (error) {
      if (alive.current) setRows((r) => ({ ...r, [key]: { status: 'failed', message: failed(error) } }))
    }
  }, [])
  return { rows, run }
}

export const selectInput = 'min-h-[44px] w-full rounded-md border border-line-2 bg-ground px-3 text-sm text-neutral-100 sm:w-56'

/**
 * Leaves a task once it has saved. The leave check reads the page as it was
 * last drawn, so the page draws once without its draft before it goes.
 */
export function useLeaveAfterSave() {
  const go = useGo()
  const [to, setTo] = useState<string | null>(null)
  useEffect(() => { if (to) go(to, { replace: true }) }, [to, go])
  return { leaving: to !== null, leave: setTo }
}

/** An object without one key, for clearing a row's message. */
export function without<T extends Record<string | number, unknown>>(obj: T, key: string | number): T {
  const out = { ...obj }
  delete out[key]
  return out
}

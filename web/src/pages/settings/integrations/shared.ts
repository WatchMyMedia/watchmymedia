import { useEffect, useState, type ComponentProps } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../../../api/client'
import { integrationsQuery, type Integration } from '../../../api/integrations'
import { visibleConfirmationError } from '../../../api/withConfirm'
import type { FlowFrame } from '../../../components/settings/Flow'
import { errorText } from '../../../components/settings/hooks'
import type { Problem } from '../../../lib/integrationsSettings'
import { settingsHref } from '../../../lib/settingsRoutes'

/** The frame a step is drawn in, without what the step itself says. */
export type StepFrame = Omit<ComponentProps<typeof FlowFrame>, 'title' | 'lede' | 'children' | 'next' | 'help' | 'finished'>

export const hubHref = settingsHref('integrations')
export const pageHref = (id: number, task?: string) => settingsHref('integrations', id, task)

/**
 * A refusal as words: the server's message through `word`, or, when the
 * server didn't answer or said too many tries, the frame's sentence.
 * Cancelling the password dialog is not a refusal.
 */
export function toProblem(error: unknown, word: (message: string, status: number | undefined) => Problem): Problem | null {
  if (!visibleConfirmationError(error)) return null
  if (error instanceof ApiError && error.status !== 429 && error.status !== 403) return word(error.message, error.status)
  return { title: 'Nothing was saved', body: errorText(error) }
}

/**
 * Leaves the step once its save has finished: the draft is clean first, so
 * the leave check lets the step go (the check re-registers in its own
 * effect, which runs before this one).
 */
export function useAfterSave() {
  const [then, setThen] = useState<(() => void) | null>(null)
  useEffect(() => {
    if (then) then()
  }, [then])
  return { saved: then !== null, done: (fn: () => void) => setThen(() => fn) }
}

// A banner for the page a task or a remove returns to (§3.7): what changed
// and what happens next, in a sentence. Kept until the page is left.
type Flash = { href: string; title: string; body?: string }
let flash: Flash | null = null

export function setFlash(f: Flash) {
  flash = f
}

export function useFlash(href: string) {
  const [shown] = useState(() => (flash?.href === href ? flash : null))
  useEffect(() => () => {
    if (flash?.href === href) flash = null
  }, [href])
  return shown
}

/**
 * Puts a saved connection into the list at once, so the next step finds it
 * before the list is read again.
 */
export function useUpsertConnection() {
  const qc = useQueryClient()
  return (c: Integration) =>
    qc.setQueryData(integrationsQuery.queryKey, (old) => old && { ...old, items: old.items.some((x) => x.id === c.id) ? old.items.map((x) => (x.id === c.id ? c : x)) : [...old.items, c] })
}

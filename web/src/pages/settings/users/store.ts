import { useSyncExternalStore } from 'react'
import type { AddDraft } from '../../../lib/usersSettings'

// Add a person's choices, kept in memory while its steps change address, and
// the hub's banner after a delete. Nothing here is written to storage: a
// reload starts Add a person again at Name and role.

const empty: AddDraft = { name: '', admin: false }
let draft: AddDraft = empty
let deleted: string | null = null
const listeners = new Set<() => void>()

function emit() {
  for (const l of listeners) l()
}

function subscribe(l: () => void) {
  listeners.add(l)
  return () => { listeners.delete(l) }
}

export function useAddDraft() {
  return useSyncExternalStore(subscribe, () => draft)
}

export function setAddDraft(change: Partial<AddDraft>) {
  draft = { ...draft, ...change }
  emit()
}

export function clearAddDraft() {
  draft = empty
  emit()
}

export function useDeletedNotice() {
  return useSyncExternalStore(subscribe, () => deleted)
}

export function noteDeleted(name: string | null) {
  deleted = name
  emit()
}

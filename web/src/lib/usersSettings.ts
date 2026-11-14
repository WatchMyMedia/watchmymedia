// The rules of Settings › Users (PLAN-ADMIN-SETTINGS.md §5.5): the steps of
// Add a person, what each person's row says, what a choice saved, and the
// words for every refusal the server can give.

import { ApiError } from '../api/client'
import { errorText } from '../components/settings/hooks'
import { resolveStep, type FlowStep } from './flowSteps'

export type AddDraft = {
  name: string
  admin: boolean
  /** The server refused the name (taken in the meantime): Name and role opens again with this under the field. */
  nameError?: string
  /** The person once they are added; the flow can then only show Added. */
  created?: { id: number; name: string; admin: boolean; recordHistory: boolean; historyFailed?: boolean }
}

/** Name and role → Password → Added. A new admin makes Password ask for the
 * password away from home; a member is added without it. */
export function addSteps(admin: boolean): FlowStep[] {
  return [
    { key: 'name', label: 'Name and role' },
    { key: 'password', label: 'Password', guarded: admin },
    { key: 'added', label: 'Added' },
  ]
}

/**
 * The step an Add a person address opens. Once the person is added the
 * earlier steps can't be reopened (the account is made), so every address
 * shows Added; before that, Password needs a name.
 */
export function resolveAddStep(requested: string | undefined, draft: AddDraft): FlowStep {
  const steps = addSteps(draft.admin)
  if (draft.created) return steps[2]
  return resolveStep(steps, requested, (key) => (key === 'name' ? draft.name.trim() !== '' && !draft.nameError : false))
}

/** Names are unique whatever their case, as the server compares them. */
export function nameTaken(name: string, existing: readonly string[]) {
  const want = name.trim().toLocaleLowerCase()
  return want !== '' && existing.some((n) => n.trim().toLocaleLowerCase() === want)
}

/** "Alex B", or the next free letter after it, for a name that is taken. */
export function freeNameFor(name: string, existing: readonly string[]) {
  const base = name.trim()
  for (const letter of 'BCDEFGHIJKLMNOPQRSTUVWXYZ') {
    const candidate = `${base} ${letter}`
    if (!nameTaken(candidate, existing)) return candidate
  }
  return `${base} 2`
}

export const MIN_PASSWORD = 8

export type FieldError = { field: 'name' | 'password'; text: string }
export type StepError = { title: string; text: string }
export type Refusal = { field?: FieldError; step?: StepError }

/** Why a name can't be used, before anything is sent. */
export function nameProblem(name: string, existing: readonly string[]): StepError | null {
  const t = name.trim()
  if (!t) return { title: 'Enter a name', text: 'It’s what they type to sign in.' }
  if (t.length > 64) return { title: 'That name is too long', text: 'Use 64 characters or fewer.' }
  if (nameTaken(t, existing)) return { title: `There’s already a person called ${t}`, text: `Names have to be different. Try ${freeNameFor(t, existing)}.` }
  return null
}

export function passwordProblem(password: string): string | null {
  return password.length < MIN_PASSWORD ? `Use ${MIN_PASSWORD} characters or more.` : null
}

/**
 * Adding a person was refused. A name that was taken in the meantime sends
 * the admin back to Name and role with the reason under the field; a short
 * password stays under the password; anything else is the step's message.
 */
export function addRefusal(error: unknown, name: string, existing: readonly string[]): Refusal {
  if (error instanceof ApiError && error.status === 422) {
    if (/already exists/i.test(error.message)) {
      const t = name.trim()
      return { field: { field: 'name', text: `There’s already a person called ${t}. Names have to be different. Try ${freeNameFor(t, [...existing, t])}.` } }
    }
    if (/password/i.test(error.message)) return { field: { field: 'password', text: `Use ${MIN_PASSWORD} characters or more.` } }
    if (/name/i.test(error.message)) return { field: { field: 'name', text: 'Use a name of 1 to 64 characters.' } }
  }
  return { step: { title: `${name.trim() || 'They'} wasn’t added`, text: errorText(error, 'Something went wrong, and no one was added. Try again.') } }
}

/** Resetting someone's password was refused. */
export function resetRefusal(error: unknown, name: string): string {
  if (error instanceof ApiError) {
    if (error.status === 422) return `Use ${MIN_PASSWORD} characters or more. The password wasn’t changed.`
    if (error.status === 404) return `${name} was deleted, so there’s no password to reset.`
    if (error.status === 409) return 'Change your own password under Account & devices.'
  }
  return errorText(error, 'Something went wrong, and the password wasn’t changed. Try again.')
}

/** Deleting a person was refused. */
export function deleteRefusal(error: unknown, name: string): string {
  if (error instanceof ApiError) {
    if (error.status === 404) return `${name} was already deleted.`
    if (error.status === 409) return 'You can’t delete your own account.'
  }
  return errorText(error, `Something went wrong, and ${name} wasn’t deleted. Try again.`)
}

/** Linking a Seerr user was refused: what to say, with the choice back as it was. */
export function seerrRefusal(error: unknown, seerrName: string | undefined, person: string): StepError {
  if (error instanceof ApiError) {
    if (error.status === 422 && /no user/i.test(error.message))
      return { title: `Seerr has no user ${seerrName ? `“${seerrName}”` : 'like that'} any more`, text: `The choice wasn’t saved. Pick another Seerr user, or None and ${person} can’t request.` }
    if (error.status === 503) return { title: 'Integrations are off on this server', text: 'The choice wasn’t saved. Seerr users can’t be linked while integrations are off.' }
    if (error.status === 502) return { title: 'Couldn’t reach Seerr', text: `The choice wasn’t saved. ${errorText(error)}` }
  }
  return { title: 'The Seerr user wasn’t saved', text: errorText(error) }
}

/** Possessive: "Alex’s", "Chris’". */
export function possessive(name: string) {
  return /s$/i.test(name) ? `${name}’` : `${name}’s`
}

/** "Alex", "Alex and Casey", "Alex, Casey and Sam". */
export function nameList(names: readonly string[]) {
  if (names.length <= 1) return names[0] ?? ''
  return `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`
}

export type SeerrState = 'linked' | 'none' | 'gone' | 'cant'

/**
 * The Seerr state of a person: linked to a Seerr user who can request, not
 * linked, linked to a user Seerr no longer has, or linked to one Seerr
 * doesn't let request. Undefined while Seerr's users aren't known.
 */
export function seerrState(seerrUserId: number | undefined, users: readonly { id: number; canRequest: boolean }[] | undefined): SeerrState | undefined {
  if (!users) return undefined
  if (!seerrUserId) return 'none'
  const u = users.find((s) => s.id === seerrUserId)
  if (!u) return 'gone'
  return u.canRequest ? 'linked' : 'cant'
}

/** "History recorded · linked to Plex · played on 3 devices", the line under a person on the hub. */
export function personLine(p: { recordHistory: boolean; plexLinked?: boolean; seerr?: SeerrState; devices?: number }) {
  const parts = [p.recordHistory ? 'History recorded' : 'History not recorded']
  if (p.plexLinked) parts.push('linked to Plex')
  if (p.seerr === 'none') parts.push('no Seerr user')
  if (p.seerr === 'gone') parts.push('their Seerr user is gone')
  if (p.devices) parts.push(`played on ${p.devices} ${p.devices === 1 ? 'device' : 'devices'}`)
  return parts.join(' · ')
}

/** Whether a person's row says Can't request: only while Seerr's users are known. */
export function cantRequest(state: SeerrState | undefined) {
  return state === 'none' || state === 'gone' || state === 'cant'
}

export type DeleteAccess = 'admins' | 'everyone'
export type DownloadAccess = 'none' | 'admins' | 'everyone'

export function deleteSavedNote(to: DeleteAccess) {
  return to === 'everyone' ? 'Everyone with an account can now delete movies and episodes.' : 'Only admins can delete movies and episodes now.'
}

const downloadRank: Record<DownloadAccess, number> = { none: 0, admins: 1, everyone: 2 }

/**
 * What changing who can download did. People who lose it have their
 * downloads removed the next time each app syncs, so they are named.
 */
export function downloadSavedNote(from: DownloadAccess | undefined, to: DownloadAccess, people: readonly { name: string; isAdmin: boolean }[]) {
  const losing = from === undefined || downloadRank[to] >= downloadRank[from]
    ? []
    : people.filter((p) => (to === 'none' ? true : !p.isAdmin) && (from === 'everyone' || p.isAdmin)).map((p) => p.name)
  if (losing.length > 0) {
    const who = losing.length > 3 ? `${losing.length} people’s` : losing.length === 1 ? possessive(losing[0]) : `${nameList(losing)}’s`
    return `${who} downloads are removed from their devices the next time each app syncs.`
  }
  if (to === 'none') return 'No one can download to watch offline now.'
  return to === 'everyone' ? 'Everyone with an account can now download to watch offline.' : 'Admins can download to watch offline.'
}

/** The person an address names, or 0 for none. */
export function personId(raw: string | undefined) {
  const n = Number(raw)
  return Number.isInteger(n) && n > 0 ? n : 0
}

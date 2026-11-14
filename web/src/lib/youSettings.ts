// The rules of the You group of Settings (PLAN-ADMIN-SETTINGS.md §5.10):
// Playback, Audio & subtitles, Account & devices, Link a TV, Your requests
// and About. What each page says about its state, and which message a
// refusal gets, live here so they can be tested; the pages only draw them.

import { ApiError } from '../api/client'
import type { components } from '../api/schema'
import { automaticAvailable, type LanguageDraft } from './languagePreferences'
import { languageName } from './format'

type MediaRequest = components['schemas']['MediaRequest']
type RequestOptions = components['schemas']['RequestOptions']
type Session = components['schemas']['Session']
type SubtitleStatus = components['schemas']['SubtitleStatus']

const status = (error: unknown) => (error instanceof ApiError ? error.status : undefined)
/** fetch rejects with a TypeError when the server can't be reached at all. */
const unreachable = (error: unknown) => error instanceof TypeError

/** A sentence's first letter in capitals. */
const capital = (s: string) => (s ? s[0].toUpperCase() + s.slice(1) : s)

/** "English", "English and Urdu", "English, Urdu and Spanish". */
export function andList(items: readonly string[]): string {
  if (items.length <= 1) return items[0] ?? ''
  return `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`
}

// ─── Playback ──────────────────────────────────────────────────────────────

export const SKIP_KINDS: readonly { kind: string; label: string; plural: string; hint: string; fallback: string }[] = [
  { kind: 'intro', label: 'Intro', plural: 'intros', hint: 'Opening titles and theme', fallback: 'ask' },
  { kind: 'recap', label: 'Recap', plural: 'recaps', hint: '“Previously on…”', fallback: 'ask' },
  { kind: 'credits', label: 'Credits', plural: 'credits', hint: 'End credits; the Next Episode card takes over on the last block', fallback: 'ask' },
  { kind: 'preview', label: 'Preview', plural: 'previews', hint: '“Next time on…”', fallback: 'none' },
  { kind: 'commercial', label: 'Ads', plural: 'ads', hint: 'Commercial breaks left in recordings', fallback: 'ask' },
]

export const SKIP_MODES: readonly { value: string; label: string; does: string }[] = [
  { value: 'none', label: 'Do nothing', does: 'play through' },
  { value: 'ask', label: 'Show a Skip button', does: 'show a Skip button' },
  { value: 'auto-delay', label: 'Skip after 3 seconds', does: 'skip after 3 seconds' },
  { value: 'auto', label: 'Skip right away', does: 'skip right away' },
]

export function skipModeLabel(mode: string | undefined): string {
  return SKIP_MODES.find((m) => m.value === mode)?.label ?? mode ?? 'Do nothing'
}

/** A part's saved mode; the server's defaults when it has none. */
export function skipModeOf(skip: Record<string, string> | undefined, kind: string): string {
  return skip?.[kind] ?? SKIP_KINDS.find((k) => k.kind === kind)?.fallback ?? 'none'
}

/**
 * The page's state in a line: "Intros, recaps, credits and ads show a Skip
 * button; previews play through. The next episode plays by itself."
 */
export function playbackLede(p: { skip: Record<string, string>; autoplayNext: boolean }): string {
  // Parts that share a mode go together, in the order the parts come.
  const modes = [...new Set(SKIP_KINDS.map((k) => skipModeOf(p.skip, k.kind)))]
  const groups = modes.map((mode) => {
    const parts = SKIP_KINDS.filter((k) => skipModeOf(p.skip, k.kind) === mode).map((k) => k.plural)
    return `${andList(parts)} ${SKIP_MODES.find((m) => m.value === mode)?.does ?? 'use their own setting'}`
  })
  const skipping = `${capital(groups.join('; '))}.`
  const next = p.autoplayNext ? 'The next episode plays by itself.' : 'The next episode waits for you.'
  return `${skipping} ${next}`
}

/** What a saved skipping choice did, after "✓ Saved.". */
export function skipSavedLine(kind: string, mode: string): string {
  const part = SKIP_KINDS.find((k) => k.kind === kind)
  const does = SKIP_MODES.find((m) => m.value === mode)?.does ?? 'change'
  return `${capital(part?.plural ?? kind)} now ${does}, on every device.`
}

/**
 * Why a choice that saves at once didn't, and that it's back where it was.
 * The server refuses a value it doesn't know with 422, which means this page
 * is older than the server, so the fix is to reload.
 */
export function choiceSaveError(error: unknown, savedLabel: string): string {
  if (status(error) === 422) return `The server didn’t accept this choice, so it’s still ${savedLabel}. This page may be older than the server: reload it and choose again.`
  if (status(error) === 429) return `Too many changes at once, so it’s still ${savedLabel}. Wait a moment and choose again.`
  return `Couldn’t save, so it’s still ${savedLabel}. Check your connection and choose again.`
}

// ─── Audio & subtitles, yours ──────────────────────────────────────────────

const SUBTITLE_STATE: Record<LanguageDraft['choice'], string> = {
  off: 'Subtitles start only when you choose one.',
  forced: 'Subtitles start only when the file marks them as forced.',
  foreign: 'Subtitles start when the audio isn’t in your languages.',
  on: 'Subtitles always start, in your first language that has one.',
}

/** The page's state in a line, from what is saved. */
export function languageLede(saved: LanguageDraft): string {
  if (!saved.audio.length && !saved.subtitles.length && saved.choice === 'forced') {
    return 'Before you choose: videos play the file’s default audio, and subtitles start only when the file marks them as forced.'
  }
  const names = saved.audio.map((t) => languageName(t))
  const audio = names.length ? `Videos start in ${names.join(', then ')}.` : 'Videos play the file’s default audio.'
  return `${audio} ${SUBTITLE_STATE[saved.choice]}`
}

/** Why Automatic download can't be ticked, said beside it; null when it can. */
export function automaticReason(d: LanguageDraft): string | null {
  if (automaticAvailable(d)) return null
  const starts = d.choice === 'on' || d.choice === 'foreign'
  if (!starts && !d.subtitles.length) return 'Off because subtitles don’t start by themselves. Choose On or When the audio isn’t in my languages, and add a subtitle language.'
  if (!starts) return 'Off because subtitles don’t start by themselves. Choose On or When the audio isn’t in my languages.'
  return 'Off because there’s no subtitle language to look for. Add a subtitle language.'
}

export type Notice = { title: string; body: string }

/** A failed save of the draft: the draft and the save bar stay. */
export function languageSaveError(error: unknown, words: string): Notice {
  if (unreachable(error)) return { title: 'Couldn’t save your choices', body: 'The server didn’t answer. Your changes are still here and nothing was saved. Check your connection and choose Save again.' }
  if (status(error) === 422) return { title: 'Couldn’t save your choices', body: `${words} Your changes are still here and nothing was saved.` }
  return { title: 'Couldn’t save your choices', body: `Your changes are still here and nothing was saved. ${words}` }
}

// ─── Audio & subtitles, the server subtitle service ─────────────────────────

export type ServiceState = 'unavailable' | 'signed-in' | 'failed' | 'signed-out'

export function serviceState(s: Pick<SubtitleStatus, 'available' | 'username' | 'account'>): ServiceState {
  if (!s.available) return 'unavailable'
  if (!s.username) return 'signed-out'
  return s.account ? 'signed-in' : 'failed'
}

function resetTime(iso?: string) {
  return iso ? new Date(iso).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' }) : ''
}

/** "14 of 20 downloads left today", or "20 a day" before the count is known. */
export function allowanceLine(a: NonNullable<SubtitleStatus['account']>): string {
  return a.remainingDownloads >= 0 ? `${a.remainingDownloads} of ${a.allowedDownloads} downloads left today` : `${a.allowedDownloads} downloads a day`
}

/** The block's state in words, under its heading. */
export function serviceLede(s: SubtitleStatus): string {
  const lead = 'For everyone on this server. Only admins see this.'
  switch (serviceState(s)) {
    case 'unavailable': return lead
    case 'signed-out': return `${lead} Online subtitles come from OpenSubtitles.com; without an account the server gets 5 downloads a day.`
    case 'failed': return `${lead} OpenSubtitles isn’t accepting the saved login, so the server gets 5 downloads a day.`
    case 'signed-in': return `${lead} Online subtitles come from OpenSubtitles.com; ${allowanceLine(s.account!).replace(' left', ' are left')}.`
  }
}

/** The account row's detail. */
export function accountLine(s: SubtitleStatus): string {
  const a = s.account
  if (!s.username) return 'Not signed in. The server gets 5 downloads a day.'
  if (!a) return `Saved login for ${s.username} isn’t working.`
  const level = `${a.level || 'Free'} account${a.vip ? ' · VIP' : ''}`
  const reset = a.resetAt ? `, resets at ${resetTime(a.resetAt)}` : ''
  return `Signed in as ${s.username} · ${level} · ${allowanceLine(a)}${reset}`
}

export function downloadsLine(downloads: 'everyone' | 'admins', saved = false): string {
  const now = saved ? ' now' : ''
  return downloads === 'admins'
    ? `Only admins can pick an online subtitle${now}. Searching is free either way.`
    : `Everyone on this server can pick an online subtitle${now}. Searching is free either way.`
}

/** Refusals of a login, which the server checks with OpenSubtitles before it keeps it. */
export function openSubtitlesError(error: unknown, words: string): Notice {
  const code = status(error)
  if (code === 422) return { title: 'OpenSubtitles didn’t accept this username and password', body: 'Nothing was saved; the server keeps the login it had. Check them by signing in at opensubtitles.com, then try again.' }
  if (code === 502) return { title: 'Couldn’t reach OpenSubtitles, so nothing was saved', body: 'Check the server’s internet connection, then try again.' }
  if (code === 503) return { title: 'This build has no OpenSubtitles key', body: 'Nothing was saved. Subtitles inside your files still work.' }
  if (unreachable(error)) return { title: 'Couldn’t reach the server, so nothing was saved', body: 'Check your connection, then try again.' }
  return { title: 'Nothing was saved', body: words }
}

/** A failed save of the fallback languages: the list stays as typed. */
export function fallbackSaveError(error: unknown, words: string): Notice {
  if (unreachable(error)) return { title: 'Couldn’t save, so the fallback languages are unchanged', body: 'The server didn’t answer. Your list is still here. Try again.' }
  return { title: 'Couldn’t save, so the fallback languages are unchanged', body: `${words} Your list is still here.` }
}

/**
 * OpenSubtitles searches by a two- or three-letter code with an optional
 * region, in lower case (the server drops anything else). Tags as the
 * language list holds them (pt-BR) go to the server as pt-br.
 */
export function fallbackCodes(tags: readonly string[]): string[] {
  return tags.map((t) => t.toLowerCase())
}

const fallbackRe = /^[a-z]{2,3}(-[a-z]{2})?$/

/** Null when OpenSubtitles can search for the tag, else why not. */
export function fallbackRefusal(tag: string): string | null {
  return fallbackRe.test(tag.toLowerCase()) ? null : 'OpenSubtitles can’t search for that one. Use a language from the list, or a code such as en, es or pt-BR.'
}

/** Saved fallback codes (pt-br) as the language list shows them (pt-BR). */
export function fallbackTags(codes: readonly string[]): string[] {
  return codes.map((c) => {
    try {
      return Intl.getCanonicalLocales(c)[0] ?? c
    } catch {
      return c
    }
  })
}

// ─── Account & devices ─────────────────────────────────────────────────────

/** "Signed in as Robin · admin. Signed in on 4 devices." */
export function accountLede(name: string, admin: boolean, count: number | undefined, everyone: boolean): string {
  const who = `Signed in as ${name}${admin ? ' · admin' : ''}.`
  if (count === undefined) return who
  if (everyone) return `${who} Everyone on this server is signed in on ${count === 1 ? '1 device' : `${count} devices`}.`
  return `${who} ${count <= 1 ? 'Signed in on this browser only.' : `Signed in on ${count} devices.`}`
}

/** A device row's detail line. With Everyone's, the person comes first. */
export function sessionLine(s: Session, everyone: boolean, ago: (iso: string) => string): string {
  const app = s.kind === 'device' ? [s.product, s.version].filter(Boolean).join(' ') : ''
  if (everyone) return [s.userName, app, `last used ${ago(s.lastSeenAt)}`, s.lastIp].filter(Boolean).join(' · ')
  return [app, `signed in ${ago(s.createdAt)}`, `last used ${ago(s.lastSeenAt)}`, s.lastIp].filter(Boolean).join(' · ')
}

export function deviceName(s: Pick<Session, 'name'>): string {
  return s.name || 'Unknown device'
}

/** "Sign out Living room TV?", or someone else's: "Sign out Alex's Bedroom TV?". */
export function signOutTitle(s: Session, myId: number | undefined): string {
  if (s.current) return 'Sign out of this browser?'
  return s.userId !== myId ? `Sign out ${s.userName}’s ${deviceName(s)}?` : `Sign out ${deviceName(s)}?`
}

/** A sign-out that failed. A device that was already gone (404) isn't an
 * error the person can act on: the list refreshes and says so. */
export function signOutError(error: unknown): { gone: true } | { gone: false; text: string } {
  if (status(error) === 404) return { gone: true }
  return { gone: false, text: 'Couldn’t sign it out, so it’s still signed in. Check your connection and try again.' }
}

/** The Change password button, with what it costs on it. */
export function changePasswordLabel(others: number): string {
  if (others === 0) return 'Change password'
  return `Change password and sign out ${others === 1 ? '1 device' : `${others} devices`}`
}

export type PasswordProblem = { field: 'current' | 'new'; text: string } | { field: null; title: string; body: string }

/** Mistakes the page can see before asking the server. */
export function passwordFieldErrors(current: string, next: string): { current?: string; new?: string } {
  const out: { current?: string; new?: string } = {}
  if (!current) out.current = 'Enter your current password.'
  if (next.length < 8) out.new = 'Use 8 or more characters.'
  else if (next.length > 256) out.new = 'Use 256 characters or fewer.'
  return out
}

/** The server's refusals. Each says nothing was changed. */
export function passwordChangeError(error: unknown, words: string): PasswordProblem {
  const code = status(error)
  if (code === 401) return { field: 'current', text: 'That isn’t your current password. Nothing was changed.' }
  if (code === 422 || code === 400) return { field: 'new', text: `${words} Nothing was changed.` }
  if (code === 429) return { field: null, title: 'Too many tries', body: 'Nothing was changed. Wait a few minutes, then try again. If you’ve forgotten your password, sign out and ask an admin to reset it.' }
  if (unreachable(error)) return { field: null, title: 'Couldn’t reach the server, so your password wasn’t changed', body: 'Your devices are still signed in. Check your connection and try again.' }
  return { field: null, title: 'Your password wasn’t changed', body: words }
}

/** The banner on Account & devices after a change (loose end 6). */
export function passwordChangedBanner(others: number | undefined): Notice {
  if (others === undefined) return { title: 'Password changed', body: 'Every other device was signed out and will ask for the new password. Link a TV signs a TV back in without typing.' }
  if (others === 0) return { title: 'Password changed', body: 'No other device was signed in. Use the new password next time you sign in.' }
  const devices = others === 1 ? '1 other device was' : `${others} other devices were`
  return { title: 'Password changed', body: `${devices} signed out and will ask for the new password. Link a TV signs a TV back in without typing.` }
}

// ─── Link a TV ─────────────────────────────────────────────────────────────

export const CODE_LENGTH = 6

/** Codes are shown in capitals and may be typed with spaces or dashes. */
export function normalizeCode(code: string): string {
  return code.toUpperCase().replace(/[\s-]/g, '')
}

export function isCode(code: string | undefined): code is string {
  return !!code && /^[A-Z0-9]{6}$/.test(code)
}

const NUMBERS = ['No', 'One', 'Two', 'Three', 'Four', 'Five', 'Six']

/** "Six characters. Two more to go." under the code field. */
export function codeHint(typed: string): string {
  const left = CODE_LENGTH - normalizeCode(typed).length
  if (left <= 0) return 'Six characters.'
  if (left === CODE_LENGTH) return 'Six characters.'
  return `Six characters. ${NUMBERS[left]} more to go.`
}

export type LinkStep = 'code' | 'check' | 'done'

/**
 * Which step a Link a TV address opens: /settings/account/link is Enter the
 * code, …/link/ABC234 Check it's yours, …/link/ABC234/done Linked. An
 * address with something that isn't a code goes back to the first step.
 */
export function linkStep(params: { step?: string; rest?: string }): { step: LinkStep; code?: string } {
  const code = params.step ? normalizeCode(params.step) : undefined
  if (!isCode(code)) return { step: 'code' }
  if (params.rest === 'done') return { step: 'done', code }
  if (params.rest) return { step: 'check', code }
  return { step: 'check', code }
}

/** The address a step of Link a TV lives at. */
export function linkHref(step: LinkStep, code?: string): string {
  if (step === 'code' || !isCode(code)) return '/settings/account/link'
  return step === 'done' ? `/settings/account/link/${code}/done` : `/settings/account/link/${code}`
}

/** Where /link, which a TV's QR code opens with ?code=, goes now: the second step. */
export function linkRedirect(code: string | undefined): string {
  const c = code ? normalizeCode(code) : ''
  return isCode(c) ? linkHref('check', c) : linkHref('code')
}

export type LinkProblem = Notice & { kind: 'missing' | 'expired' | 'approved' | 'other' }

/** Looking a code up failed. */
export function lookupError(error: unknown, code: string, words: string): LinkProblem {
  if (status(error) === 404) return { kind: 'missing', title: `No device is waiting with ${code}`, body: 'Check the code on the TV. Codes run out after a while: if it has, choose Sign in with a code on the TV again for a new one.' }
  if (status(error) === 429) return { kind: 'other', title: 'Too many wrong codes', body: `${words} Check the code on the TV.` }
  if (unreachable(error)) return { kind: 'other', title: 'Couldn’t look for the code', body: 'The server didn’t answer. Check your connection and try again.' }
  return { kind: 'other', title: 'Couldn’t look for the code', body: words }
}

/** Approving a code failed. */
export function approveError(error: unknown, code: string, words: string): LinkProblem {
  const s = status(error)
  if (s === 409) return { kind: 'approved', title: 'This device is already signed in', body: `Someone approved ${code} a moment ago. Carry on on the TV. It’s in your Signed-in devices.` }
  if (s === 404) return { kind: 'expired', title: 'The code ran out before it was approved', body: 'Nothing was signed in. Choose Sign in with a code on the TV again, then enter the new code.' }
  if (s === 429) return { kind: 'other', title: 'Couldn’t sign it in', body: `${words} The TV is still waiting.` }
  if (unreachable(error) || s === undefined || s >= 500) return { kind: 'other', title: 'Couldn’t sign it in', body: 'The server didn’t answer. The TV is still waiting. Try again.' }
  return { kind: 'other', title: 'Couldn’t sign it in', body: `${words} The TV is still waiting.` }
}

// ─── Your requests ─────────────────────────────────────────────────────────

/** "1 is waiting for approval and 1 is downloading." Empty when neither. */
export function requestsSummary(items: readonly Pick<MediaRequest, 'status' | 'download'>[]): string {
  const waiting = items.filter((r) => r.status === 'pending').length
  const downloading = items.filter((r) => r.status === 'approved' && r.download?.state === 'downloading').length
  const parts = [
    waiting && `${waiting} ${waiting === 1 ? 'is' : 'are'} waiting for approval`,
    downloading && `${downloading} ${downloading === 1 ? 'is' : 'are'} downloading`,
  ].filter(Boolean) as string[]
  return parts.length ? `${capital(andList(parts))}.` : ''
}

export type RequestsOff = { title: string; body: string; setUp: boolean }

/**
 * Why Your requests is off when it's opened by its address (loose end 10),
 * or null when requests are on. An admin is offered Set up requests.
 */
export function requestsOff(options: Pick<RequestOptions, 'backend' | 'movies' | 'shows'>, admin: boolean): RequestsOff | null {
  if (options.movies || options.shows) return null
  if (options.backend === 'seerr') {
    return {
      title: 'Your account isn’t linked to Seerr',
      body: admin
        ? 'Requests go to Seerr on this server, and you don’t have a Seerr user who can request. Link yourself in Integrations.'
        : 'Requests go to Seerr on this server, and you don’t have a Seerr user who can request. Ask an admin to link you in Integrations.',
      setUp: admin,
    }
  }
  return admin
    ? { title: 'Requests aren’t on', body: 'Requests need Seerr, or Radarr or Sonarr with a place for requests to go.', setUp: true }
    : { title: 'Requests aren’t on for this server', body: 'Nothing is set up to look for movies and shows people ask for. Ask the person who runs the server.', setUp: false }
}

/** What cancelling says it does: with Seerr, it's deleted there too. */
export function cancelLine(backend: string | undefined): string {
  return backend === 'seerr'
    ? 'It’s deleted in Seerr too, and leaves this list. You can ask for it again later.'
    : 'It leaves the admins’ Inbox and this list. You can ask for it again later.'
}

export function requestTitle(r: Pick<MediaRequest, 'title' | 'year'>): string {
  return `${r.title}${r.year ? ` (${r.year})` : ''}`
}

const STATUS_WORD: Record<MediaRequest['status'], string> = {
  pending: 'Waiting for approval',
  approved: 'Approved',
  declined: 'Declined',
  failed: 'Failed',
  available: 'In your library',
}

/**
 * A cancel that failed, said under its row: 404 (already gone), 422 (it
 * moved on and can't be cancelled), 502 (Seerr, Radarr or Sonarr didn't
 * answer), anything else. `now` is the request's state after the list
 * refreshed, when it is still there.
 */
export function cancelError(error: unknown, backend: string | undefined, now?: MediaRequest['status']): { gone: boolean; text: string } {
  const code = status(error)
  if (code === 404) return { gone: true, text: 'This request is already gone: it was cancelled or removed elsewhere. It’s left the list.' }
  if (code === 422) {
    return now && now !== 'pending'
      ? { gone: false, text: `It can’t be cancelled any more: it was ${STATUS_WORD[now].toLowerCase()} a moment ago. It’s shown as ${STATUS_WORD[now]} now.` }
      : { gone: false, text: 'It can’t be cancelled any more: it changed a moment ago. The list shows where it stands now.' }
  }
  if (code === 502) return { gone: false, text: `Couldn’t reach ${backend === 'seerr' ? 'Seerr' : 'Radarr or Sonarr'}, so the request is still there. Try again in a minute.` }
  return { gone: false, text: 'Couldn’t cancel it, so the request is still there. Check your connection and try again.' }
}

// ─── About ─────────────────────────────────────────────────────────────────

/** Whether the server was updated while the page was open. */
export function versionChanged(first: string | undefined, current: string | undefined): boolean {
  return !!first && !!current && first !== current
}

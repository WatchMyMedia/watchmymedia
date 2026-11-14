// The rules of Settings › Integrations (PLAN-ADMIN-SETTINGS.md §5.2): which
// step an address opens, each connection's checklist, the hub's banners and
// lede, the folder rows with their suggestions, and the words for every
// refusal the server gives.

import type { components } from '../api/schema'
import { firstOpenStep, resolveStep, type FlowStep } from './flowSteps'
import { cleanMappings, freshSuggestions, suggestSeerrUser, unmappedRoots } from './integrations'

type Integration = components['schemas']['Integration']
type IntegrationDetail = components['schemas']['IntegrationDetail']
type PathMapping = components['schemas']['PathMapping']
type RootFolder = components['schemas']['IntegrationRootFolder']
type RequestTarget = components['schemas']['RequestTarget']
type SeerrUser = components['schemas']['SeerrUser']
type Kind = Integration['kind']
type ArrKind = Exclude<Kind, 'seerr'>

export const APP: Record<Kind, string> = { radarr: 'Radarr', sonarr: 'Sonarr', seerr: 'Seerr' }

/** What an app's things are called, singular and plural. */
export function things(kind: Kind) {
  return kind === 'sonarr' ? { one: 'show', many: 'shows' } : { one: 'movie', many: 'movies' }
}

export function isArr(kind: string): kind is ArrKind {
  return kind === 'radarr' || kind === 'sonarr'
}

// ---- Steps -----------------------------------------------------------------

export const ARR_STEPS: readonly FlowStep[] = [
  { key: 'connect', label: 'Connect', guarded: true },
  { key: 'folders', label: 'Folders', guarded: true, optional: true },
  { key: 'requests', label: 'Requests', guarded: true, optional: true },
  { key: 'webhook', label: 'Webhook', guarded: true, optional: true },
  { key: 'connected', label: 'Connected' },
]

export const SEERR_STEPS: readonly FlowStep[] = [
  { key: 'connect', label: 'Connect', guarded: true },
  { key: 'people', label: 'Link people' },
  { key: 'connected', label: 'Connected' },
]

/**
 * The step of Add Radarr or Sonarr to show. Until the connection is saved
 * only Connect opens. After it, Folders, Requests and Webhook can each be
 * skipped, so any of them opens; with no step named, the first still to do
 * opens, else the finished screen.
 */
export function addArrStep(requested: string | undefined, connected: boolean, todo: readonly string[] = []): FlowStep {
  if (!connected) return resolveStep(ARR_STEPS, requested, () => false)
  if (!requested) return firstOpenStep(ARR_STEPS, (k) => k === 'connect' || !todo.includes(k))
  return resolveStep(ARR_STEPS, requested, () => true)
}

/** The step of Connect Seerr: Connect until Seerr is saved, then any step; with none named, Link people. */
export function seerrStep(requested: string | undefined, connected: boolean): FlowStep {
  if (!connected) return resolveStep(SEERR_STEPS, requested, () => false)
  if (!requested) return SEERR_STEPS[1]
  return resolveStep(SEERR_STEPS, requested, () => true)
}

/** The one-step tasks under a connection, and which apps have them. */
export const CONNECTION_TASKS = ['connection', 'folders', 'requests', 'webhook', 'people'] as const
export type ConnectionTask = (typeof CONNECTION_TASKS)[number]

export function taskFits(task: string, kind: Kind): task is ConnectionTask {
  if (task === 'connection') return true
  if (task === 'people') return kind === 'seerr'
  return (task === 'folders' || task === 'requests' || task === 'webhook') && kind !== 'seerr'
}

export const TASK_LABEL: Record<ConnectionTask, string> = { connection: 'Connection', folders: 'Folders', requests: 'Requests', webhook: 'Webhook', people: 'People' }

/** A name for a new connection that no other one has: Movies, TV, then Movies 2. */
export function defaultName(kind: Kind, taken: readonly string[]): string {
  const base = kind === 'radarr' ? 'Movies' : kind === 'sonarr' ? 'TV' : 'Seerr'
  if (!taken.includes(base)) return base
  for (let n = 2; ; n++) if (!taken.includes(`${base} ${n}`)) return `${base} ${n}`
}

// ---- Checklist and hub ------------------------------------------------------

export type CheckState = 'done' | 'todo' | 'unknown'
export type CheckItem = { key: 'folders' | 'requests' | 'webhook' | 'people'; label: string; state: CheckState; todo: string }

/**
 * A connection's checklist: Folders, Requests and Webhook for Radarr and
 * Sonarr; People for Seerr. Folders are done once mapped, or when every root
 * folder is inside a library; leaving one root unmapped is allowed. Events
 * for folders outside every library make Folders a to-do again.
 */
export function connectionChecklist(c: Integration, opts: { detail?: IntegrationDetail; people?: { linked: number; total: number } } = {}): CheckItem[] {
  if (c.kind === 'seerr') {
    const p = opts.people
    const state: CheckState = !p ? 'unknown' : p.linked >= p.total ? 'done' : 'todo'
    const left = p ? p.total - p.linked : 0
    return [{ key: 'people', label: p ? `People · ${p.linked} of ${p.total} linked` : 'People', state, todo: `${left === 1 ? '1 person' : `${left} people`} not linked` }]
  }
  const roots = opts.detail?.details?.rootFolders
  let folders: CheckState
  if (c.status.webhook.outside > 0) folders = 'todo'
  else if (c.pathMap.length > 0) folders = 'done'
  else if (roots) folders = unmappedRoots(roots).length === 0 ? 'done' : 'todo'
  else folders = 'unknown'
  const w = c.status.webhook
  return [
    { key: 'folders', label: 'Folders', state: folders, todo: c.status.webhook.outside > 0 ? 'Folders need a look' : 'Folders not mapped' },
    { key: 'requests', label: 'Requests', state: c.targets.length > 0 ? 'done' : 'todo', todo: 'No request target' },
    { key: 'webhook', label: 'Webhook', state: w.lastTestAt || w.lastEventAt ? 'done' : 'todo', todo: 'Webhook not connected' },
  ]
}

export function todoKeys(items: readonly CheckItem[]): string[] {
  return items.filter((i) => i.state === 'todo').map((i) => i.key)
}

/** "5.14.0" from "5.14.0.9383". */
export function shortVersion(v: string | undefined) {
  return v ? v.split('.').slice(0, 3).join('.') : ''
}

export function connectionPill(c: Integration, items: readonly CheckItem[]): { tone: 'ok' | 'warn' | 'err'; label: string } {
  const app = [APP[c.kind], shortVersion(c.status.version)].filter(Boolean).join(' ')
  if (!c.status.ok && c.status.error) return { tone: 'err', label: `${app} · Not answering` }
  const todo = todoKeys(items).length
  return todo ? { tone: 'warn', label: `${app} · ${todo} to do` } : { tone: 'ok', label: `${app} · Connected` }
}

export type Banner = { id: number; title: string; body: string; said?: string }

/** A banner for each connection that stopped answering, with what fails until it's back. */
export function hubBanners(items: readonly Integration[]): Banner[] {
  return items
    .filter((c) => !c.status.ok && c.status.error)
    .map((c) => {
      const app = APP[c.kind]
      const fails = c.kind === 'seerr' ? 'Requests' : `Requests for ${things(c.kind).many}`
      return {
        id: c.id,
        title: `${c.name} stopped answering`,
        body: `${fails} will fail until ${app} is back. Check ${app} is running. If its address or key changed, change the connection.`,
        said: c.status.error,
      }
    })
}

function and(names: string[]) {
  return names.length <= 1 ? names.join('') : `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`
}

/** The hub's state in a line. */
export function hubLede(items: readonly Integration[]): string {
  const arrs = [...new Set(items.filter((c) => c.kind !== 'seerr').map((c) => APP[c.kind]))]
  const seerr = items.some((c) => c.kind === 'seerr')
  if (!items.length) return 'Connect the apps that fetch movies and shows, so people can ask for things and new files show up at once.'
  if (seerr) return arrs.length ? `Requests go through Seerr. ${and(arrs)} keep${arrs.length === 1 ? 's' : ''} the library up to date.` : 'Requests go through Seerr.'
  const taking = [...new Set(items.filter((c) => c.kind !== 'seerr' && c.targets.length > 0).map((c) => APP[c.kind]))]
  const first = taking.length
    ? `${and(taking)} take${taking.length === 1 ? 's' : ''} requests.`
    : `${and(arrs)} ${arrs.length === 1 ? 'is' : 'are'} connected, without a request target yet.`
  return `${first} Seerr isn’t connected.`
}

// ---- Folders ----------------------------------------------------------------

export type FolderRow = PathMapping & { suggested?: boolean; root?: boolean }

function trimSlash(p: string) {
  const t = p.trim()
  return t.length > 1 ? t.replace(/[/\\]+$/, '') : t
}

function within(path: string, parent: string) {
  const a = trimSlash(path)
  const b = trimSlash(parent)
  return a === b || a.startsWith(`${b}/`) || a.startsWith(`${b}\\`)
}

/**
 * The rows of the Folders step: the saved mappings, then suggestions not
 * already there, then each root folder outside every library that no row
 * covers yet, with this server's side to fill in (or to leave).
 */
export function folderRows(saved: readonly PathMapping[], detail?: IntegrationDetail['details']): FolderRow[] {
  const rows: FolderRow[] = saved.map((m) => ({ from: m.from, to: m.to }))
  if (!detail) return rows.length ? rows : [{ from: '', to: '' }]
  for (const s of freshSuggestions(detail.suggestions, rows)) rows.push({ ...s, suggested: true })
  for (const r of unmappedRoots(detail.rootFolders)) {
    if (!rows.some((row) => row.from.trim() && within(r.path, row.from))) rows.push({ from: r.path, to: '', root: true })
  }
  return rows.length ? rows : [{ from: '', to: '' }]
}

/** The library a row's folder on this server is in (or holds), for its pill. */
export function rowLibrary<L extends { name: string; path: string }>(row: PathMapping, libraries: readonly L[]): L | undefined {
  const to = row.to.trim()
  if (!to) return undefined
  return libraries.find((l) => within(to, l.path) || within(l.path, to))
}

/** What saves: root rows left empty are left alone; everything else, trimmed. */
export function foldersToSave(rows: readonly FolderRow[]): PathMapping[] {
  return cleanMappings(rows.filter((r) => !(r.root && !r.to.trim())))
}

export type Problem = { title: string; body: string; field?: 'name' | 'url' | 'apiKey' | 'address'; detail?: string }

/** A mapping with one side typed, which the server would refuse. */
export function mappingProblem(rows: readonly FolderRow[]): Problem | null {
  const half = rows.find((r) => !(r.root && !r.to.trim()) && !r.from.trim() !== !r.to.trim())
  if (!half) return null
  const named = half.from.trim() || half.to.trim()
  return { title: 'A mapping is missing one side', body: `Fill in both folders for ${named}, or remove the row.` }
}

/** The root folders still outside every library once the rows are saved, for the warnings. */
export function rootsLeftOut<L extends { name: string; path: string }>(rows: readonly FolderRow[], roots: readonly RootFolder[], libraries: readonly L[]): string[] {
  return unmappedRoots([...roots])
    .filter((r) => {
      const row = rows.find((x) => x.from.trim() && within(r.path, x.from))
      return !row || !rowLibrary(row, libraries)
    })
    .map((r) => r.path)
}

// ---- Requests ---------------------------------------------------------------

/**
 * The one target that is used (loose end 4): the server sends every request
 * of a kind to the first target of the first connection with one. Saving
 * replaces the first target and keeps the library it was for, or the
 * library the folder is in, or the first library of the kind.
 */
export function targetToSave(
  saved: readonly RequestTarget[],
  choice: { rootFolder: string; qualityProfileId: number },
  roots: readonly RootFolder[],
  libraryIds: readonly number[],
): RequestTarget[] {
  if (!choice.rootFolder) return saved.slice(1)
  const libraryId = saved[0]?.libraryId ?? roots.find((r) => r.path === choice.rootFolder)?.libraryId ?? libraryIds[0] ?? 0
  return [{ libraryId, rootFolder: choice.rootFolder, qualityProfileId: choice.qualityProfileId }, ...saved.slice(1).filter((t) => t.libraryId !== libraryId)]
}

/** The first choice on the Requests step: the saved target, else the one root folder inside a library, else none. */
export function firstTargetChoice(saved: readonly RequestTarget[], roots: readonly RootFolder[], profiles: readonly { id: number }[]) {
  if (saved[0]) return { rootFolder: saved[0].rootFolder, qualityProfileId: saved[0].qualityProfileId }
  const inside = roots.filter((r) => r.libraryId)
  return { rootFolder: inside.length === 1 ? inside[0].path : roots.length === 1 ? roots[0].path : '', qualityProfileId: profiles.length === 1 ? profiles[0].id : 0 }
}

export function targetProblem(kind: Kind, choice: { rootFolder: string; qualityProfileId: number }, inFlow: boolean): Problem | null {
  if (!inFlow && !choice.rootFolder) return null
  if (choice.rootFolder && choice.qualityProfileId > 0) return null
  const off = `requests for ${things(kind).many} stay off`
  return {
    title: 'Choose both a folder and a quality',
    body: inFlow ? `A target needs both. Or choose Skip for now and ${off}.` : `A target needs both. Or choose Don’t take requests, and ${off}.`,
  }
}

// ---- Seerr people -----------------------------------------------------------

type Person = { id: number; name: string; seerrUserId?: number }

/**
 * The first choices on Link people: each linked person keeps their Seerr
 * user; each one not linked gets the Seerr user whose name matches, as the
 * Users tab suggests, never one already taken.
 */
export function peopleDraft(people: readonly Person[], users: readonly SeerrUser[]): Record<number, { seerrUserId: number; suggested: boolean }> {
  const taken = people.flatMap((p) => (p.seerrUserId ? [p.seerrUserId] : []))
  const out: Record<number, { seerrUserId: number; suggested: boolean }> = {}
  for (const p of people) {
    if (p.seerrUserId) {
      out[p.id] = { seerrUserId: p.seerrUserId, suggested: false }
      continue
    }
    const s = suggestSeerrUser(p.name, [...users], taken)
    if (s) taken.push(s.id)
    out[p.id] = { seerrUserId: s?.id ?? 0, suggested: !!s }
  }
  return out
}

/** The people whose choice differs from what is saved. */
export function linksToSave(people: readonly Person[], draft: Record<number, { seerrUserId: number }>): { id: number; seerrUserId: number }[] {
  return people.flatMap((p) => {
    const want = draft[p.id]?.seerrUserId ?? 0
    return want !== (p.seerrUserId ?? 0) ? [{ id: p.id, seerrUserId: want }] : []
  })
}

// ---- The words for refusals ---------------------------------------------------

function sentence(text: string) {
  const t = text.trim()
  if (!t) return t
  const s = t[0].toUpperCase() + t.slice(1)
  return /[.!?…]$/.test(s) ? s : `${s}.`
}

const unreachable = /could not reach|dial tcp|connection refused|no such host|timeout|deadline exceeded|network is unreachable|no route to host|EOF|tls:|x509/i

/** What Test said when it worked. */
export function testedWording(message: string): Problem {
  return { title: `${message.replace(/^(\S+) (\d+\.\d+\.\d+)\S*$/, '$1 $2')} answered`, body: 'The address and key work. Nothing is saved yet.' }
}

/**
 * The words for a refused Test or Save on the Connect step, from the
 * server's message: an old version (or Overseerr and Jellyseerr), another
 * app at the address, a refused key, an address that can't be reached or
 * isn't one, and a second Seerr.
 */
export function connectProblem(kind: Kind, message: string): Problem {
  const app = APP[kind]
  const m = message.replace(/^(the app said|Seerr said): /i, '')
  const old = /^(Radarr|Sonarr|Seerr) (\S+) is too old/i.exec(m)
  if (old) {
    const version = old[2]
    if (kind === 'seerr') {
      const major = Number(version.split('.')[0])
      const title = major === 1 ? 'That’s Overseerr, which isn’t supported' : major === 2 ? 'That’s Jellyseerr, which isn’t supported' : `Seerr ${version} is too old`
      return { title, body: 'This server works with Seerr 3 or later. Overseerr and Jellyseerr can be moved to Seerr; see Seerr’s migration guide.' }
    }
    return { title: `${app} ${version} is too old`, body: `This server needs ${kind === 'radarr' ? 'Radarr 5' : 'Sonarr 4.0.6'} or later. Update ${app}, then test again.` }
  }
  const other = /that address is (\w+), not (\w+)/i.exec(m)
  if (other) {
    const found = other[1][0].toUpperCase() + other[1].slice(1)
    const fix = isArr(found.toLowerCase()) ? `Check the port, or exit and choose Add ${found}.` : 'Check the port.'
    return { title: `That address is ${found}, not ${app}`, body: fix, field: 'url' }
  }
  if (/doesn.t answer as Seerr/i.test(m)) return { title: 'That address isn’t Seerr', body: 'Check the port. Seerr usually answers on 5055.', field: 'url' }
  if (/api key was refused/i.test(m)) {
    return { title: `${app} refused the API key`, body: kind === 'seerr' ? 'Copy the key again from Seerr › Settings › General.' : `Copy the key again from ${app} › Settings › General › Security.`, field: 'apiKey' }
  }
  if (/Seerr is connected already/i.test(m)) return { title: 'Seerr is already connected', body: 'Only one Seerr can be connected. Change it on its page, or remove it first.' }
  if (/must be http/i.test(m)) return { title: 'That isn’t an address', body: `The address must start with http:// or https:// and name a host, like ${{ radarr: 'http://nas:7878', sonarr: 'http://nas:8989', seerr: 'http://nas:5055' }[kind]}.`, field: 'url' }
  if (/API key in its own field/i.test(m)) return { title: 'The key is in the address', body: 'Put the API key in its own field, not in the address.', field: 'url' }
  if (/name, address and API key are needed/i.test(m)) return { title: 'Something is missing', body: 'A name, address and API key are needed.' }
  if (/unexpected answer/i.test(m)) return { title: `That address didn’t answer as ${app}`, body: `Check the port, and any URL base ${app} runs under.`, field: 'url', detail: m }
  if (unreachable.test(m)) {
    return {
      title: `Couldn’t reach ${app} at that address`,
      body: kind === 'seerr'
        ? 'Check Seerr is running and that this server can open the address.'
        : `Check ${app} is running and that this server can open the address. If ${app} runs in Docker, use its container name or the host’s address, not localhost.`,
      field: 'url',
      detail: m,
    }
  }
  return { title: `Couldn’t connect ${app}`, body: sentence(m) || 'Nothing was saved. Try again.' }
}

/** The words for a refused Save folders. */
export function foldersProblem(message: string): Problem {
  if (/needs both folders/i.test(message)) return { title: 'A mapping is missing one side', body: 'Fill in both folders, or remove the row.' }
  const full = /"(.+)" is not a full path/.exec(message)
  if (full) return { title: `${full[1]} isn’t a full path on this server`, body: 'Use the folder’s whole path, as this server sees it, like /media/movies.' }
  return { title: 'The folders weren’t saved', body: sentence(message) || 'Try again.' }
}

/** The words for a refused Connect webhook: the address the app couldn't open, or the app itself out of reach. */
export function webhookProblem(kind: ArrKind, address: string, status: number | undefined, message: string): Problem {
  const app = APP[kind]
  if (status === 422 || /must be http/i.test(message)) {
    return { title: 'That isn’t an address', body: 'The address must start with http:// or https:// and name a host, like http://192.0.2.5:27587.', field: 'address' }
  }
  if (/could not reach the app/i.test(message) || (!status && unreachable.test(message))) {
    return { title: `Couldn’t reach ${app}`, body: `This server couldn’t reach ${app} to set up the webhook, so nothing was saved. Check ${app} is running.`, detail: message.replace(/^could not reach the app: /i, '') }
  }
  if (status === 502 || /^the app said/i.test(message)) {
    return {
      title: `${app} couldn’t open ${address}`,
      body: `${app} tried a test event and got no answer, so nothing was saved. Use an address ${app} can open: the same network, the right port, and not localhost unless both run on one machine.`,
      field: 'address',
      detail: message.replace(/^the app said: /i, ''),
    }
  }
  return { title: 'The webhook isn’t connected', body: sentence(message) || 'Try again.' }
}

/** The words for a refused link to a Seerr user that is gone. */
export function linkProblem(message: string, person: string, users: readonly SeerrUser[]): Problem {
  const gone = /Seerr has no user (\d+)/.exec(message)
  if (gone) {
    const name = users.find((u) => u.id === Number(gone[1]))?.name
    return { title: name ? `Seerr has no user “${name}” any more` : 'That Seerr user is gone', body: `The list was refreshed. Choose again for ${person}.` }
  }
  return { title: `${person}’s link wasn’t saved`, body: sentence(message) || 'Try again.' }
}

// ---- Remove and finish ----------------------------------------------------------

/** The Remove dialog's words, per app (§3.8). */
export function removeWording(c: Pick<Integration, 'kind' | 'name'>) {
  const app = APP[c.kind]
  const title = c.name === app ? `Remove ${app}?` : `Remove ${c.name} (${app})?`
  if (c.kind === 'seerr') {
    return {
      title,
      stays: ['Seerr and everything in it, and requests already made.', 'People keep their Seerr links, in case you connect it again.'],
      stops: ['Requests through Seerr. Requests go back to Radarr and Sonarr, and approval comes back to this server.'],
      done: { title: `${c.name} is removed`, body: 'Requests go to Radarr and Sonarr again, and approval is back on this page. Seerr itself isn’t changed.' },
    }
  }
  const t = things(c.kind)
  const searches = c.kind === 'radarr' ? 'missing-movie searches' : 'missing-episode searches'
  return {
    title,
    stays: [`${app} and everything in it, your library, and requests already made.`],
    stops: [`${t.one[0].toUpperCase() + t.one.slice(1)} requests, ${searches} and instant scans.`, `${app}’s webhook will be refused, so remove it in ${app} › Settings › Connect.`],
    done: { title: `${c.name} is removed`, body: `${app} itself isn’t changed. Remove the webhook in ${app} › Settings › Connect.` },
  }
}

function host(url: string) {
  try { return new URL(url).host } catch { return url }
}

/** What changed and what happens next, on Add Radarr or Sonarr's finished screen (§3.7). */
export function arrOutcome(
  c: Integration,
  opts: { roots?: readonly RootFolder[]; rootsLeft?: readonly string[]; profileName?: string; approval?: 'admins' | 'everyone'; seerr?: boolean },
): { changed: string[]; next: string[] } {
  const app = APP[c.kind]
  const t = things(c.kind)
  const changed = [`Connected at ${host(c.url)}.`]
  const maps = c.pathMap.length
  if (maps) changed.push(`${maps === 1 ? '1 folder' : `${maps} folders`} mapped.${opts.rootsLeft?.length ? ` ${and([...opts.rootsLeft])} ${opts.rootsLeft.length === 1 ? 'is' : 'are'} left unmapped.` : ''}`)
  else if (opts.roots && unmappedRoots([...opts.roots]).length === 0) changed.push(`No folders to map: ${app} and this server see the same folders.`)
  else changed.push('Folders aren’t mapped yet. They stay to do on the connection’s page.')
  const target = c.targets[0]
  if (target) changed.push(`${t.one[0].toUpperCase() + t.one.slice(1)} requests go to ${target.rootFolder}${opts.profileName ? ` at ${opts.profileName}` : ''}.`)
  else changed.push(`There’s no request target yet, so people can’t request ${t.many}.`)
  const hooked = !!(c.status.webhook.lastTestAt || c.status.webhook.lastEventAt)
  changed.push(hooked ? 'The webhook is on.' : 'The webhook isn’t connected yet.')

  const next: string[] = []
  if (target) {
    if (opts.seerr) next.push(`Requests for ${t.many} go through Seerr, which sends them to ${app}.`)
    else if (opts.approval === 'everyone') next.push(`People can request ${t.many} from Search, and every request goes straight to ${app}.`)
    else next.push(`People can request ${t.many} from Search. Admins’ requests go straight to ${app}. Everyone else’s wait in the Inbox.`)
  }
  next.push(hooked ? 'Imports and renames show up at once.' : 'New files show up at the next scan, until the webhook is connected.')
  next.push('The connection is checked every 15 minutes, and the Integrations page says if it stops answering.')
  return { changed, next }
}

/** The To do card on a connection's page: its first unfinished task, what waits on it, and the button. */
export function todoCard(c: Pick<Integration, 'kind' | 'status'>, key: string): { title: string; body: string; action: string } | null {
  const app = APP[c.kind]
  const t = things(c.kind)
  switch (key) {
    case 'folders':
      return c.status.webhook.outside > 0
        ? { title: 'Check the folders', body: `${c.status.webhook.outside === 1 ? '1 event was' : `${c.status.webhook.outside} events were`} for folders outside your libraries, so those files didn’t show up.`, action: 'Check folders' }
        : { title: `Map ${app}’s folders`, body: `${t.many[0].toUpperCase() + t.many.slice(1)} ${app} puts in a folder outside every library won’t show up.`, action: 'Map folders' }
    case 'requests':
      return { title: `Choose where requested ${t.many} go`, body: `Until then, people can’t request ${t.many}.`, action: 'Choose a target' }
    case 'webhook':
      return { title: 'Connect the webhook', body: `Until then, new ${c.kind === 'sonarr' ? 'episodes' : 'movies'} show up at the next scan, up to 15 minutes later.`, action: 'Connect webhook' }
    case 'people':
      return { title: 'Link people to Seerr users', body: 'People without a Seerr user can’t request.', action: 'Link people' }
  }
  return null
}

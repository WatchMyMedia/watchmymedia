import type { components } from '../api/schema'
import { relativeTime } from './format'

type Integration = components['schemas']['Integration']
type PathMapping = components['schemas']['PathMapping']
type RootFolder = components['schemas']['IntegrationRootFolder']
type SeerrUser = components['schemas']['SeerrUser']

export type StatusLine = { tone: 'ok' | 'warn' | 'off'; text: string }

const apps: Record<string, string> = { radarr: 'Radarr', sonarr: 'Sonarr', seerr: 'Seerr' }

/** "10:41" in the viewer's clock. */
export function clock(iso: string): string {
  return new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

/** The line under a connection: the last error, else what the webhook has sent (for Seerr, which has none here, its users). */
export function connectionStatus(c: Integration): StatusLine {
  const app = apps[c.kind] ?? c.kind
  const s = c.status
  if (!s.ok && s.error) {
    return { tone: 'warn', text: `Couldn't reach ${app}: ${s.error}${s.checkedAt ? ` · ${clock(s.checkedAt)}` : ''}` }
  }
  if (c.kind === 'seerr') {
    return { tone: 'ok', text: `Takes requests · ${s.items === 1 ? '1 user' : `${s.items} users`}` }
  }
  if (s.webhook.lastEventAt) return { tone: 'ok', text: `Webhook · last event ${relativeTime(s.webhook.lastEventAt)}` }
  if (s.webhook.lastTestAt) return { tone: 'ok', text: `Webhook · test event received ${clock(s.webhook.lastTestAt)}` }
  return { tone: 'off', text: 'Webhook not connected yet' }
}

/** Root folders the mappings leave outside every library, for the warning lines. */
export function unmappedRoots(roots: RootFolder[]): RootFolder[] {
  return roots.filter((r) => !r.libraryId)
}

/** Suggested mappings not already in the list being edited. */
export function freshSuggestions(suggestions: PathMapping[], current: PathMapping[]): PathMapping[] {
  return suggestions.filter((s) => !current.some((c) => c.from.trim() === s.from && c.to.trim() === s.to))
}

/** The rows to save: trimmed, without empty ones. */
export function cleanMappings(rows: PathMapping[]): PathMapping[] {
  return rows.map((r) => ({ from: r.from.trim(), to: r.to.trim() })).filter((r) => r.from !== '' || r.to !== '')
}

/** What the app's items are called, for "(3 movies)". */
export function itemWord(kind: string, n: number): string {
  const one = kind === 'radarr' ? 'movie' : 'series'
  return `${n} ${n === 1 || one === 'series' ? one : `${one}s`}`
}

/** A placeholder address for a new connection: the app's usual port. */
export function defaultAddress(kind: string): string {
  return { radarr: 'http://localhost:7878', sonarr: 'http://localhost:8989', seerr: 'http://localhost:5055' }[kind] ?? ''
}

/** The letters on a connection's tile; Seerr's two so it isn't taken for Sonarr. */
export function tileLetters(kind: string): string {
  return { radarr: 'R', sonarr: 'S', seerr: 'Se' }[kind] ?? kind.charAt(0).toUpperCase()
}

function sameName(a: string): string {
  return a.normalize('NFC').trim().toLowerCase()
}

/**
 * The Seerr user to suggest for a person not linked yet: the one Seerr user whose name, username, Plex or Jellyfin
 * name, or email before the @, is the person's name. None when no user or several agree, or the one is linked to
 * someone else already.
 */
export function suggestSeerrUser(name: string, users: SeerrUser[], linkedIds: number[]): SeerrUser | undefined {
  const want = sameName(name)
  if (!want) return undefined
  const agree = users.filter((u) => [u.name, ...u.names].some((n) => sameName(n) === want || sameName(n.split('@')[0] ?? '') === want))
  return agree.length === 1 && !linkedIds.includes(agree[0].id) ? agree[0] : undefined
}

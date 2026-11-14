import type { components } from '../api/schema'

type PathMapping = components['schemas']['PathMapping']
type RootFolder = components['schemas']['IntegrationRootFolder']
type SeerrUser = components['schemas']['SeerrUser']

/** "10:41" in the viewer's clock. */
export function clock(iso: string): string {
  return new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
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

/** A placeholder address for a new connection: the app's usual port. */
export function defaultAddress(kind: string): string {
  return { radarr: 'http://localhost:7878', sonarr: 'http://localhost:8989', seerr: 'http://localhost:5055' }[kind] ?? ''
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

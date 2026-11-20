import { useEffect, useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { plexConnectionsQuery, plexLibrariesQuery, plexPeopleQuery, type PlexConnection } from '../../../api/plex'
import { librariesQuery, usersQuery } from '../../../api/queries'
import { settingsCrumbs, settingsHref } from '../../../lib/settingsRoutes'

// What the Plex tab's pages and flows share: the connection, the names of
// who and what is linked, and the bits of a flow kept in this tab while the
// admin is away from it (steps carry on and pick up where they were).

export const plexHref = (...segments: (string | number | undefined)[]) => settingsHref('plex', ...segments)

export const TASK_LABEL = {
  connect: 'Connect a Plex server',
  reconnect: 'Reconnect Plex',
  link: 'Link people and libraries',
  import: 'Check and import',
  undo: 'Undo an import',
  restore: 'Check a restored Plex server',
} as const

export const plexCrumbs = (task: keyof typeof TASK_LABEL) => settingsCrumbs('plex', { label: TASK_LABEL[task] })

/** The one Plex connection, if there is one. */
export function usePlexConnection() {
  const query = useQuery(plexConnectionsQuery)
  return { query, connection: query.data?.items[0] as PlexConnection | undefined }
}

/** Who and what is linked, by their names here, read only while connected. */
export function useLinkedNames(connection: PlexConnection | undefined) {
  const on = !!connection && connection.status === 'connected'
  const people = useQuery({ ...plexPeopleQuery(connection?.id ?? 0, connection?.generation ?? 0), enabled: on })
  const sections = useQuery({ ...plexLibrariesQuery(connection?.id ?? 0, connection?.generation ?? 0), enabled: on })
  const users = useQuery({ ...usersQuery, enabled: on })
  const libraries = useQuery({ ...librariesQuery, enabled: on })
  return useMemo(() => {
    const userName = new Map(users.data?.items.map((u) => [u.id, u.name]))
    const libraryName = new Map(libraries.data?.items.map((l) => [l.id, l.name]))
    const linked = people.data?.users.filter((p) => p.linkedUserId) ?? []
    const linkedSections = sections.data?.libraries.filter((s) => s.linkedLibraryId) ?? []
    const ready = !!people.data && !!sections.data && !!users.data && !!libraries.data
    return {
      ready,
      people: people.data?.users,
      peopleFound: people.data?.users.length,
      sectionsFound: sections.data?.libraries.length,
      /** Local account names, in Plex's order. */
      accounts: ready ? linked.map((p) => userName.get(p.linkedUserId!) ?? p.title) : [],
      libraries: ready ? linkedSections.map((s) => libraryName.get(s.linkedLibraryId!) ?? s.title) : [],
    }
  }, [people.data, sections.data, users.data, libraries.data])
}

function read<T>(key: string): T | undefined {
  try {
    const raw = window.sessionStorage.getItem(key)
    return raw ? (JSON.parse(raw) as T) : undefined
  } catch {
    return undefined
  }
}

function write(key: string, value: unknown) {
  try {
    if (value === undefined) window.sessionStorage.removeItem(key)
    else window.sessionStorage.setItem(key, JSON.stringify(value))
  } catch {
    // Private windows and blocked storage: the flow still works, it just
    // doesn't survive a reload.
  }
}

/** State kept in this tab's session storage, so a reload stays put. `fresh`
 * starts from `initial` and forgets what was kept. */
export function useStored<T>(key: string, initial: T, fresh = false) {
  const [value, setValue] = useState<T>(() => (fresh ? initial : (read<T>(key) ?? initial)))
  useEffect(() => write(key, value), [key, value])
  return [value, setValue] as const
}

// The keys of what each flow keeps.
export const signInKey = 'wmm.plex.sign-in'
export const linkKey = (id: number, generation: number) => `wmm.plex.link.${id}.${generation}`
export const importKey = (id: number) => `wmm.plex.import.${id}`

export type ImportKept = {
  previewId?: string
  confirmed: boolean
  /** Items left out on this check, which make it out of date. */
  excluded: number
  runId?: number
  /** What the check said would be marked, for "88 of 124 marked". */
  planned?: number
}

export const NO_IMPORT: ImportKept = { confirmed: false, excluded: 0 }

/** The check kept for the import flow, read by Link's review and the hub. */
export function keptImport(id: number) {
  return read<ImportKept>(importKey(id))
}

/** Saving links or starting fresh sets aside the last check. */
export function setAsideCheck(id: number) {
  const kept = read<ImportKept>(importKey(id))
  if (kept?.previewId) write(importKey(id), { ...kept, previewId: undefined, confirmed: false, excluded: 0 })
}

/** Forgets everything a connection's flows kept, after a disconnect. */
export function forgetConnection(id: number) {
  write(importKey(id), undefined)
  write(signInKey, undefined)
}

/** The time now, ticking. */
export function useClock(every = 1000) {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), every)
    return () => window.clearInterval(timer)
  }, [every])
  return now
}

import type { components } from '../api/schema'

export type PlexConnection = components['schemas']['PlexConnection']
export type PlexPerson = components['schemas']['PlexPerson']
export type PlexSection = components['schemas']['PlexSection']
export type PlexPreview = components['schemas']['PlexPreview']
export type PlexCounts = components['schemas']['PlexCounts']
export type PlexExample = components['schemas']['PlexExample']
export type PlexLinks = components['schemas']['PlexLinks']
type LocalUser = { id: number }
type LocalLibrary = { id: number; type: 'movie' | 'tv' }

export type PlexMappingDraft = { users: Record<number, number>; libraries: Record<number, number> }

/** Existing explicit IDs are the only source of defaults; equal names never link people. */
export function plexMappingDraft(people: PlexPerson[], libraries: PlexSection[]): PlexMappingDraft {
  return {
    users: Object.fromEntries(people.map((p) => [p.plexUserId, p.linkedUserId ?? 0])),
    libraries: Object.fromEntries(libraries.map((l) => [l.sectionId, l.linkedLibraryId ?? 0])),
  }
}

/** Validate locally for useful feedback; the server rechecks identities, types and generation. */
export function plexMappingError(
  draft: PlexMappingDraft,
  people: PlexPerson[],
  sections: PlexSection[],
  users: LocalUser[],
  libraries: LocalLibrary[],
  requireMappings = false,
): string | null {
  const selectedUsers = Object.entries(draft.users).filter(([, id]) => id > 0)
  const selectedLibraries = Object.entries(draft.libraries).filter(([, id]) => id > 0)
  if (requireMappings && (!selectedUsers.length || !selectedLibraries.length)) return 'Choose at least one person and one library before checking an import.'
  const seen = new Set<number>()
  for (const [source, local] of selectedUsers) {
    const person = people.find((p) => p.plexUserId === Number(source))
    if (!person || person.status !== 'ready' || !users.some((u) => u.id === local)) return 'Choose a ready Plex person and an existing WatchMyMedia account.'
    if (seen.has(local)) return 'Each WatchMyMedia account can be mapped to only one Plex person.'
    seen.add(local)
  }
  for (const [source, local] of selectedLibraries) {
    const section = sections.find((s) => s.sectionId === Number(source))
    const library = libraries.find((l) => l.id === local)
    if (!section || !library || (section.type === 'movie' ? library.type !== 'movie' : library.type !== 'tv'))
      return 'Choose a movie library for movies, and a TV library for TV shows.'
  }
  return null
}

export function plexLinks(generation: number, draft: PlexMappingDraft): PlexLinks {
  return {
    generation,
    users: Object.entries(draft.users)
      .filter(([, id]) => id > 0)
      .map(([id, userId]) => ({ plexUserId: Number(id), userId })),
    libraries: Object.entries(draft.libraries)
      .filter(([, id]) => id > 0)
      .map(([id, libraryId]) => ({ sectionId: Number(id), libraryId })),
  }
}

/** Browser authorization must stay on Plex's page even if a reply is malformed. */
export function plexAuthorizationURL(value?: string): string | null {
  if (!value) return null
  try {
    const url = new URL(value)
    return url.origin === 'https://app.plex.tv' && url.pathname === '/auth' && !url.username && !url.password ? url.href : null
  } catch {
    return null
  }
}

export const PLEX_COUNT_LABELS: [keyof PlexCounts, string][] = [
  ['willMark', 'Will mark watched'],
  ['alreadyWatched', 'Already watched here'],
  ['localKept', 'Your WatchMyMedia choices kept'],
  ['unmatched', 'Not found here'],
  ['ambiguous', 'Needs a closer match'],
  ['unavailable', 'Not available'],
]

export function plexPreviewCounts(preview: PlexPreview): PlexCounts {
  const counts: PlexCounts = { willMark: 0, alreadyWatched: 0, localKept: 0, unmatched: 0, ambiguous: 0, unavailable: 0, notWatched: 0, unknownState: 0 }
  for (const person of preview.users ?? []) for (const key of Object.keys(counts) as (keyof PlexCounts)[]) counts[key] += person.counts[key]
  return counts
}

/** A ready preview can still expire while its page is open. The server remains authoritative. */
export function canImportPlexPreview(connection: PlexConnection, preview: PlexPreview, now: number): boolean {
  return (
    connection.status === 'connected' &&
    preview.status === 'complete' &&
    preview.generation === connection.generation &&
    !!preview.users?.length &&
    preview.users.every((u) => u.status === 'complete' && !u.skippedSections) &&
    !!preview.expiresAt &&
    Number.isFinite(Date.parse(preview.expiresAt)) &&
    Date.parse(preview.expiresAt) > now
  )
}

export function plexKindLabel(kind?: string): string {
  return kind === 'owner' ? 'Plex server owner' : kind === 'managed' ? 'Plex Home profile' : kind === 'account' ? 'Plex account' : 'Unknown identity'
}

export function plexPersonStatus(status: string): string {
  return (
    (
      {
        ready: 'Ready',
        needs_profile_authorization: 'Can’t read this person yet',
        needs_pin: 'Needs PIN · can’t import yet',
        unavailable: 'Unavailable',
      } as Record<string, string>
    )[status] ?? 'Unavailable'
  )
}

const REASONS: Record<string, string> = {
  connection_not_found: 'This Plex connection was removed. Refresh the page or connect Plex again.',
  different_owner: 'Use the Plex account that owns this connected server. To change accounts or servers, disconnect the current one first.',
  identity_mismatch: 'Plex answered as a different server or person. Check the selected server and reconnect with its owner.',
  one_server_only: 'One Plex server can be connected at a time. Disconnect this one before choosing another.',
  origin_not_allowed: 'Enter the server’s full HTTP or HTTPS address, with its port and no extra path.',
  plex_user_not_ready: 'A chosen person can’t be read yet. Check their access in Plex and refresh, or leave them out.',
  restricted_account: 'Sign in with the Plex account that owns the server. A shared or restricted account can’t connect it.',
  server_credential_unavailable: 'Plex did not give working access to this server. Check it is running and reconnect with its owner.',
  server_identity_mismatch: 'This address answers as a different Plex server. Check the selected server and change the address above.',
  server_not_owned: 'This Plex account does not own the selected server. Cancel and sign in with its owner.',
  server_stopping: 'This WatchMyMedia server is stopping. Try again after it starts.',
  sign_in_busy: 'This sign-in is already being checked. Wait a moment, then try again.',
  too_many_sign_ins: 'Several Plex sign-ins are still open. Cancel earlier attempts or wait for them to expire before starting again.',
  undo_preview_limit: 'Several undo checks were made recently. Wait for an earlier check to expire (up to 15 minutes), then check again.',
  unknown_library: 'A chosen library was removed. Refresh the libraries and choose again.',
  unknown_section: 'Plex no longer lists a chosen library. Refresh the libraries and choose again.',
  unknown_user: 'A chosen WatchMyMedia account was removed. Refresh the people and choose another account or leave them out.',
  unknown_plex_user: 'Plex no longer lists a chosen person. Refresh the people and leave them out.',
  library_type_mismatch: 'Choose a movie library for movies and a TV library for TV shows.',
  item_not_in_mapping: 'This item or person is no longer in the chosen libraries. Refresh and check what will change again.',
  run_not_found: 'This import is no longer available. Refresh the connection and check what will change again.',
  transport: 'Couldn’t reach Plex. Check that its server is running, or try another address above.',
  response_read: 'Plex’s reply could not be read. Check the connection and try again.',
  invalid_response: 'Plex’s reply could not be used. Check that its server is up to date, then reconnect and try again.',
  pagination_drift: 'Plex’s library changed while it was being checked. Check what will change again.',
  scope_mismatch: 'Plex returned a different person or library than requested. Reconnect and check your chosen people and libraries.',
  excluded: 'You chose to leave this unwatched.',
  no_tmdb_or_imdb_id: 'Plex did not provide enough title details to match it for sure.',
  no_show_tmdb_id: 'Plex did not provide enough show details to match this episode for sure.',
  not_in_library: 'This title was not found in the chosen library here.',
  special: 'This special episode could not be matched for sure. It was left alone.',
  alternate_order: 'Plex uses different or unconfirmed episode numbers. This episode was left alone.',
  several_matches: 'More than one title matches. This item was left alone.',
  edition: 'This is a particular cut of a movie. It was left alone to avoid marking a different cut watched.',
  show_unreadable: 'The show’s details could not be read in Plex. Check Plex and try again.',
  network: 'Couldn’t reach Plex. Check that its server is running and try again.',
  unavailable: 'Plex is not available right now. Try again later.',
  rate_limit: 'Plex asked this server to wait. Try again later.',
  rate_limited: 'Plex asked this server to wait. Try again later.',
  scope_limit: 'This import is too large to check at once. Choose fewer people or libraries, then check again.',
  history_not_found: 'This viewing was already removed. Refresh your imported history.',
  history_policy_invalid: 'Your history settings could not be checked. Try again; your existing choices stay.',
  preview_not_ready: 'This preview is expired or already used. Check what will change again.',
  preview_session_changed: 'Check what will change again in this signed-in browser.',
  preview_incomplete: 'Some people or libraries could not be read. Check their access in Plex or leave them out, then check again.',
  stale_generation: 'The connection changed. Refresh and check what will change again.',
  run_active: 'An import is already running. Wait for it to finish or cancel it.',
  run_not_retryable: 'This import cannot be retried. Check what will change again.',
  run_scope_expired: 'This import’s saved scope expired. Check what will change again.',
  undo_preview_stale: 'The undo check expired or the connection changed. Choose Check undo again.',
  import_first: 'Check what will change and import once before keeping up to date.',
  source_identity_changed:
    'Plex now gives an old link to a different item. Updates are paused to protect your choices. Check Plex, then use Check a restored Plex server only if you restored or rebuilt it.',
  incomplete_snapshot: 'Plex could not return a complete snapshot. Completed changes are kept; retry when the server is reachable.',
  cancelled: 'Import cancelled. Completed changes are kept.',
  needs_reconnect: 'Sign in to Plex again before importing.',
  needs_authorization: 'An admin must reconnect this server before importing.',
  nothing_linked: 'Map at least one person and one library first.',
  preview_running: 'A preview is already running.',
  preview_not_found: 'This preview expired. Check what will change again.',
  preview_stale: 'Your people or libraries changed. Check what will change again.',
  stale_preview: 'Your people or libraries changed. Check what will change again.',
  generation_changed: 'The connection changed. Refresh people and libraries before saving.',
  not_on_server: 'This person is no longer on the Plex server.',
  conflicting_kind: 'Plex returned conflicting identity details.',
  needs_pin: 'PIN-protected profiles cannot be imported yet.',
  needs_profile_authorization: 'This profile needs authorization before it can be mapped.',
  authorization: 'Plex authorization was refused. Reconnect the account.',
  unreachable: 'Couldn’t reach Plex. Check the server address and try again.',
  sign_in_pending: 'Finish signing in on Plex’s page, then check again.',
  sign_in_not_found: 'This sign-in expired. Start a new sign-in.',
  duplicate_link: 'Each Plex person and WatchMyMedia account can be mapped only once.',
  invalid_origin: 'Enter the Plex server’s full HTTP or HTTPS address.',
  already_connected: 'Disconnect the current Plex server before connecting a different one.',
  history_unavailable: 'Detailed history is unavailable. Watched status can still import.',
  unmatched: 'This title could not be matched for sure here.',
  missing_id: 'Plex did not provide enough title details to match it for sure.',
  conflicting_ids: 'Plex and this library have different title details. This item was left alone.',
  ambiguous: 'This title could not be matched for sure. It was left alone.',
}

/** Reason codes are developer details, not readable recovery instructions. */
export function plexReason(reason?: string, fallback = 'This item could not be imported. Check the connection and try again.'): string {
  return reason ? (REASONS[reason] ?? fallback) : ''
}

export function plexExampleTitle(item: Pick<PlexExample, 'title' | 'show' | 'season' | 'episode'>): string {
  if (!item.show) return item.title
  const number =
    item.season !== undefined && item.episode !== undefined ? ` · S${String(item.season).padStart(2, '0')}E${String(item.episode).padStart(2, '0')}` : ''
  return `${item.show}${number} · ${item.title}`
}

export function plexRunActive(status: string): boolean {
  return status === 'queued' || status === 'running'
}

export function plexRunStatus(status: string): string {
  return (
    (
      { queued: 'Waiting to import', running: 'Importing', complete: 'Complete', partial: 'Incomplete', failed: 'Failed', cancelled: 'Cancelled' } as Record<
        string,
        string
      >
    )[status] ?? 'Unknown status'
  )
}

export function plexHistoryStatus(status: string): string {
  return (
    (
      {
        complete: 'Available history read',
        unavailable: 'Detailed history unavailable',
        partial: 'History incomplete',
        not_read: 'History not read yet',
        disabled: 'History recording is off',
      } as Record<string, string>
    )[status] ?? 'History coverage unknown'
  )
}

export type PlexExclusion = components['schemas']['PlexExclusion']

export function plexExclusionKey(item: PlexExclusion): string {
  return `${item.userId}:${item.itemType}:${item.itemId}`
}

/** A repeated click cannot send duplicate keys to the all-or-nothing replacement endpoint. */
export function addPlexExclusion(items: PlexExclusion[], item: PlexExclusion): PlexExclusion[] {
  return items.some((existing) => plexExclusionKey(existing) === plexExclusionKey(item)) ? items : [...items, item]
}

/** Removed Plex people/libraries can disappear from discovery while old links remain saved. */
export function plexMappingsChanged(
  connection: Pick<PlexConnection, 'linkedUsers' | 'linkedLibraries'>,
  initial: PlexMappingDraft,
  draft: PlexMappingDraft,
): boolean {
  return (
    JSON.stringify(initial) !== JSON.stringify(draft) ||
    Object.values(draft.users).filter((id) => id > 0).length !== connection.linkedUsers ||
    Object.values(draft.libraries).filter((id) => id > 0).length !== connection.linkedLibraries
  )
}


/** The connection owns approval eligibility; recent pages are only a fallback
 * while a reply from a previous server version is still cached. */
export function plexHasApprovedImport(
  connection: Pick<PlexConnection, 'generation'> & { hasApprovedImport?: boolean },
  runs: Pick<components['schemas']['PlexRun'], 'generation' | 'status'>[],
): boolean {
  return connection.hasApprovedImport ?? runs.some((run) => run.generation === connection.generation && (run.status === 'complete' || run.status === 'partial'))
}

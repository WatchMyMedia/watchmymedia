import { describe, expect, it } from 'vitest'
import {
  addPlexExclusion,
  canImportPlexPreview,
  plexAuthorizationURL,
  plexExampleTitle,
  plexLinks,
  plexMappingDraft,
  plexMappingError,
  plexMappingsChanged,
  plexPreviewCounts,
  plexRunActive,
  type PlexConnection,
  type PlexPerson,
  type PlexPreview,
  type PlexSection,
} from './plex'

const people: PlexPerson[] = [
  { plexUserId: 12, title: 'Alex', kind: 'managed', status: 'ready' },
  { plexUserId: 13, title: 'Alex', kind: 'account', status: 'ready' },
  { plexUserId: 14, title: 'Jordan', kind: 'managed', status: 'needs_pin' },
]
const sections: PlexSection[] = [
  { sectionId: 1, title: 'Films', type: 'movie' },
  { sectionId: 2, title: 'Series', type: 'show' },
]
const users = [{ id: 4 }, { id: 5 }]
const libraries: { id: number; type: 'movie' | 'tv' }[] = [
  { id: 7, type: 'movie' },
  { id: 8, type: 'tv' },
]
const counts = { willMark: 4, alreadyWatched: 2, localKept: 1, unmatched: 0, ambiguous: 0, unavailable: 0, notWatched: 6, unknownState: 1 }
const preview: PlexPreview = {
  id: 'preview',
  status: 'complete',
  generation: 2,
  startedAt: '2026-11-04T12:00:00Z',
  expiresAt: '2026-11-04T12:15:00Z',
  history: 'not_read',
  users: [{ plexUserId: 12, kind: 'managed', title: 'Alex', userId: 4, status: 'complete', counts, examples: [] }],
}
const connection: PlexConnection = {
  id: 1,
  machineIdentifier: 'fictional-server',
  name: 'Maple House',
  origin: 'http://127.0.0.1:32400',
  serverVersion: 'test',
  status: 'connected',
  historyCapability: 'unknown',
  syncEnabled: false,
  generation: 2,
  linkedUsers: 1,
  linkedLibraries: 1,
}

describe('Plex explicit mappings', () => {
  it('never infers identities from equal display names', () => {
    expect(plexMappingDraft(people, sections)).toEqual({ users: { 12: 0, 13: 0, 14: 0 }, libraries: { 1: 0, 2: 0 } })
    expect(plexMappingDraft([{ ...people[0], linkedUserId: 5 }], [{ ...sections[0], linkedLibraryId: 7 }])).toEqual({ users: { 12: 5 }, libraries: { 1: 7 } })
  })
  it('rejects assigning two source identities to the same local account', () => {
    expect(plexMappingError({ users: { 12: 4, 13: 4 }, libraries: { 1: 7 } }, people, sections, users, libraries)).toContain('only one')
  })
  it('rejects unsupported source identities, deleted users and incompatible library types', () => {
    expect(plexMappingError({ users: { 14: 4 }, libraries: { 1: 7 } }, people, sections, users, libraries)).toContain('ready')
    expect(plexMappingError({ users: { 12: 99 }, libraries: { 1: 7 } }, people, sections, users, libraries)).toContain('existing')
    expect(plexMappingError({ users: { 12: 4 }, libraries: { 1: 8 } }, people, sections, users, libraries)).toContain('movies')
  })
  it('allows clearing mappings, while requiring explicit scope for a preview', () => {
    const empty = { users: {}, libraries: {} }
    expect(plexMappingError(empty, people, sections, users, libraries)).toBeNull()
    expect(plexMappingError(empty, people, sections, users, libraries, true)).toContain('at least one')
  })
  it('omits explicitly unmapped sources and retains immutable IDs and generation', () => {
    const draft = { users: { 12: 4, 13: 0 }, libraries: { 1: 7, 2: 0 } }
    expect(plexMappingError(draft, people, sections, users, libraries)).toBeNull()
    expect(plexLinks(9, draft)).toEqual({ generation: 9, users: [{ plexUserId: 12, userId: 4 }], libraries: [{ sectionId: 1, libraryId: 7 }] })
  })
})

it('deduplicates exclusions by immutable user, type and item rather than a title', () => {
  const item = { userId: 4, itemType: 'movie' as const, itemId: 7 }
  const once = addPlexExclusion([], item)
  expect(addPlexExclusion(once, item)).toBe(once)
  expect(addPlexExclusion(once, { ...item, userId: 5 })).toHaveLength(2)
  expect(addPlexExclusion(once, { ...item, itemType: 'episode' })).toHaveLength(2)
})

describe('Plex authorization and preview safety', () => {
  it('accepts only Plex HTTPS authorization pages', () => {
    expect(plexAuthorizationURL('https://app.plex.tv/auth#?clientID=fictional')).toContain('https://app.plex.tv/auth')
    for (const url of [
      'javascript:alert(1)',
      'http://app.plex.tv/auth',
      'https://app.plex.tv.evil.test/auth',
      'https://app.plex.tv/other',
      'https://user:pass@app.plex.tv/auth',
      'invalid',
    ])
      expect(plexAuthorizationURL(url)).toBeNull()
  })
  it('blocks expired/stale previews and lost authorization even if the page remains open', () => {
    const now = Date.parse('2026-11-04T12:05:00Z')
    expect(canImportPlexPreview(connection, preview, now)).toBe(true)
    expect(canImportPlexPreview(connection, preview, Date.parse(preview.expiresAt!))).toBe(false)
    expect(canImportPlexPreview({ ...connection, generation: 3 }, preview, now)).toBe(false)
    expect(canImportPlexPreview({ ...connection, status: 'needs_reconnect' }, preview, now)).toBe(false)
    expect(canImportPlexPreview(connection, { ...preview, status: 'running' }, now)).toBe(false)
    expect(canImportPlexPreview(connection, { ...preview, expiresAt: undefined }, now)).toBe(false)
    expect(canImportPlexPreview(connection, { ...preview, users: [{ ...preview.users![0], status: 'incomplete' }] }, now)).toBe(false)
    expect(canImportPlexPreview(connection, { ...preview, users: [{ ...preview.users![0], skippedSections: 1 }] }, now)).toBe(false)
  })
  it('keeps protected, unresolved and unknown-state counts separate from eligible writes', () => {
    const result = plexPreviewCounts({
      ...preview,
      users: [...preview.users!, { ...preview.users![0], userId: 5, counts: { ...counts, willMark: 0, unmatched: 3 } }],
    })
    expect(result).toMatchObject({ willMark: 4, localKept: 2, unmatched: 3, notWatched: 12, unknownState: 2 })
  })
  it('does not treat an unknown future run state as active or completed', () => {
    expect(plexRunActive('queued')).toBe(true)
    expect(plexRunActive('running')).toBe(true)
    expect(plexRunActive('future-state')).toBe(false)
  })
  it('formats specials and episode zero without inventing missing details', () => {
    expect(plexExampleTitle({ title: 'Winter Special', show: 'Harbor Lights', season: 0, episode: 0 })).toBe('Harbor Lights · S00E00 · Winter Special')
    expect(plexExampleTitle({ title: 'The Last Orchard' })).toBe('The Last Orchard')
  })
})

it('saves a replacement when removed source people or libraries leave invisible old links', () => {
  const visible = { users: { 12: 4 }, libraries: { 1: 7 } }
  expect(plexMappingsChanged({ linkedUsers: 1, linkedLibraries: 1 }, visible, visible)).toBe(false)
  expect(plexMappingsChanged({ linkedUsers: 2, linkedLibraries: 1 }, visible, visible)).toBe(true)
  expect(plexMappingsChanged({ linkedUsers: 1, linkedLibraries: 2 }, visible, visible)).toBe(true)
})


it('uses durable approval eligibility after recent failures and rejects stale-generation guesses', async () => {
  const { plexHasApprovedImport, plexReason } = await import('./plex')
  const failed = Array.from({ length: 40 }, () => ({ generation: 4, status: 'failed' as const }))
  expect(plexHasApprovedImport({ generation: 4, hasApprovedImport: true }, failed)).toBe(true)
  expect(plexHasApprovedImport({ generation: 5, hasApprovedImport: false }, [{ generation: 4, status: 'complete' }])).toBe(false)
  expect(plexHasApprovedImport({ generation: 4 }, [{ generation: 4, status: 'complete' }])).toBe(true)
  expect(plexReason('rate_limit')).toContain('wait')
})

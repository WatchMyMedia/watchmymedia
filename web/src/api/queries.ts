import { infiniteQueryOptions, queryOptions, useMutation, useQuery, useQueryClient, type MutateOptions, type QueryClient } from '@tanstack/react-query'
import { api, unwrap } from './client'
import type { components } from './schema'
import { authScope } from '../lib/authScope'
import type { ItemFilter, ItemSort } from '../lib/browseOptions'

export const healthQuery = queryOptions({
  queryKey: ['health'],
  queryFn: async () => unwrap(await api.GET('/api/health')),
  refetchInterval: 30_000,
})

/** GET /api/server: its ID, name and API level. Fetched once a page load. */
export const serverQuery = queryOptions({
  queryKey: ['server'],
  queryFn: async () => unwrap(await api.GET('/api/server')),
  staleTime: Infinity,
})

export const librariesQuery = queryOptions({
  queryKey: ['libraries'],
  queryFn: async () => unwrap(await api.GET('/api/libraries')),
})

export const libraryQuery = (id: number) =>
  queryOptions({
    queryKey: ['libraries', id],
    queryFn: async () => unwrap(await api.GET('/api/libraries/{id}', { params: { path: { id } } })),
  })

/** The library a /tv or /movies page shows: the one named, else the first of the type. */
export function useLibraryByType(type: 'movie' | 'tv', id?: number) {
  const libs = useQuery(librariesQuery)
  const ofType = libs.data?.items.filter((l) => l.type === type)
  return { ...libs, library: ofType?.find((l) => l.id === id) ?? ofType?.[0] }
}

/** Home in the person's order (PLAN-ROWS.md §6.1): the hero, rows and totals. */
export const homeQuery = queryOptions({
  queryKey: ['home'],
  queryFn: async () => unwrap(await api.GET('/api/home', { params: { query: { arranged: true } } })),
})

/** A library's Overview rows, in the person's order. */
export const rowsQuery = (id: number) =>
  queryOptions({
    queryKey: ['rows', id],
    queryFn: async () => unwrap(await api.GET('/api/libraries/{id}/rows', { params: { path: { id }, query: { arranged: true } } })),
  })

export type { ItemFilter, ItemSort } from '../lib/browseOptions'

export const itemsQuery = (id: number, p: { sort: ItemSort; filter: ItemFilter; genre?: string; decade?: number; q?: string; limit: number; offset: number }) =>
  queryOptions({
    queryKey: ['items', id, p],
    queryFn: async () =>
      unwrap(
        await api.GET('/api/libraries/{id}/items', {
          params: {
            path: { id },
            query: { sort: p.sort, filter: p.filter || undefined, genre: p.genre || undefined, decade: p.decade || undefined, q: p.q || undefined, limit: p.limit, offset: p.offset },
          },
        }),
      ),
    placeholderData: (prev) => prev,
  })

export type FileStatus = 'present' | 'missing' | undefined
export type MatchStatus = 'pending' | 'matched' | 'unmatched' | 'failed' | undefined

export const filesQuery = (id: number, status: FileStatus, match: MatchStatus, limit: number, offset: number) =>
  queryOptions({
    queryKey: ['files', id, { status, match, limit, offset }],
    queryFn: async () =>
      unwrap(await api.GET('/api/libraries/{id}/files', { params: { path: { id }, query: { status, match, limit, offset } } })),
    placeholderData: (prev) => prev,
  })

export const movieQuery = (id: number) =>
  queryOptions({
    queryKey: ['movie', id],
    queryFn: async () => unwrap(await api.GET('/api/movies/{id}', { params: { path: { id } } })),
  })

export const showQuery = (id: number) =>
  queryOptions({
    queryKey: ['show', id],
    queryFn: async () => unwrap(await api.GET('/api/shows/{id}', { params: { path: { id } } })),
  })

export const seasonQuery = (id: number, number: number) =>
  queryOptions({
    queryKey: ['show', id, 'season', number],
    queryFn: async () => unwrap(await api.GET('/api/shows/{id}/seasons/{number}', { params: { path: { id, number } } })),
  })

/** One file with its probe: the episode page's audio layout and subtitles, which the season's files leave out. */
export const fileQuery = (id: number) =>
  queryOptions({
    queryKey: ['file', id],
    queryFn: async () => unwrap(await api.GET('/api/files/{id}', { params: { path: { id } } })),
    staleTime: 5 * 60_000,
  })

export const candidatesQuery = (fileId: number, q: string, year: number | undefined) =>
  queryOptions({
    queryKey: ['candidates', fileId, q, year],
    queryFn: async () =>
      unwrap(await api.GET('/api/files/{id}/candidates', { params: { path: { id: fileId }, query: { q: q || undefined, year } } })),
    staleTime: 60_000,
  })

export const searchQuery = (q: string) =>
  queryOptions({
    queryKey: ['search', q],
    queryFn: async () => unwrap(await api.GET('/api/search', { params: { query: { q } } })),
    enabled: q.length > 0,
  })

export const usersQuery = queryOptions({
  queryKey: ['users'],
  queryFn: async () => unwrap(await api.GET('/api/users')),
})

export function useScanLibrary() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (id: number) => unwrap(await api.POST('/api/libraries/{id}/scan', { params: { path: { id } } })),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['libraries'] }),
  })
}

export function useMatchLibrary() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async ({ id, rematch }: { id: number; rematch: boolean }) =>
      unwrap(await api.POST('/api/libraries/{id}/match', { params: { path: { id }, query: { rematch } } })),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['libraries'] }),
  })
}

export function useApplyMatch() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async ({ fileId, tmdbId, season, episodes }: { fileId: number; tmdbId: number; season?: number; episodes?: number[] }) =>
      unwrap(await api.POST('/api/files/{id}/match', { params: { path: { id: fileId } }, body: { tmdbId, season, episodes } })),
    onSuccess: () => void qc.invalidateQueries(),
  })
}

export const unmatchedQuery = (libraryId: number) =>
  queryOptions({
    // Under ['files', id] so scan, match and library events refresh it.
    queryKey: ['files', libraryId, 'unmatched'],
    queryFn: async () => unwrap(await api.GET('/api/libraries/{id}/unmatched', { params: { path: { id: libraryId } } })),
  })

export const lineUpQuery = (libraryId: number, fileIds: number[], tmdbId: number) =>
  queryOptions({
    queryKey: ['lineup', libraryId, tmdbId, fileIds.join(',')],
    queryFn: async () => unwrap(await api.POST('/api/libraries/{id}/lineup', { params: { path: { id: libraryId } }, body: { fileIds, tmdbId } })),
    staleTime: 10 * 60_000,
  })

export function useMatchFiles() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async ({ libraryId, fileIds, tmdbId, remember }: { libraryId: number; fileIds: number[]; tmdbId: number; remember: boolean }) =>
      unwrap(await api.POST('/api/libraries/{id}/match-files', { params: { path: { id: libraryId } }, body: { fileIds, tmdbId, remember } })),
    onSuccess: () => void qc.invalidateQueries(),
  })
}

type SeasonRule = components['schemas']['SeasonRule']

/** The seasons of a group that TMDB numbers differently, each with the server's suggestion. Admins only. */
export const seasonSuggestQuery = (libraryId: number, fileIds: number[], tmdbId: number) =>
  queryOptions({
    // Under ['files', id] so a match refreshes it: lining one season up suggests its neighbours.
    queryKey: ['files', libraryId, 'season-lineups', tmdbId, fileIds.join(',')],
    queryFn: async () =>
      unwrap(await api.POST('/api/libraries/{id}/season-lineups/suggest', { params: { path: { id: libraryId } }, body: { fileIds, tmdbId } })),
    retry: false,
  })

export const seasonPreviewQuery = (libraryId: number, fileIds: number[], rule: SeasonRule) =>
  queryOptions({
    queryKey: ['season-preview', libraryId, fileIds.join(','), rule.fromSeason, rule.fromEpisode, rule.tmdbId, rule.toSeason, rule.toEpisode],
    queryFn: async () =>
      unwrap(await api.POST('/api/libraries/{id}/season-lineups/preview', { params: { path: { id: libraryId } }, body: { fileIds, rule } })),
    staleTime: 10 * 60_000,
  })

export function useMatchSeasons() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async ({ libraryId, lineUps, remember }: { libraryId: number; lineUps: { fileIds: number[]; rule: SeasonRule }[]; remember: boolean }) =>
      unwrap(await api.POST('/api/libraries/{id}/match-seasons', { params: { path: { id: libraryId } }, body: { lineUps, remember } })),
    onSuccess: () => void qc.invalidateQueries(),
  })
}

export const libraryCandidatesQuery = (libraryId: number, q: string, year: number | undefined) =>
  queryOptions({
    queryKey: ['library-candidates', libraryId, q, year],
    queryFn: async () => unwrap(await api.GET('/api/libraries/{id}/candidates', { params: { path: { id: libraryId }, query: { q, year } } })),
    enabled: q.trim().length > 0,
    staleTime: 60_000,
  })

export type RematchKind = 'show' | 'movie'

export const rematchPreviewQuery = (kind: RematchKind, id: number, tmdbId: number) =>
  queryOptions({
    queryKey: ['rematch-preview', kind, id, tmdbId],
    queryFn: async () =>
      unwrap(
        kind === 'show'
          ? await api.GET('/api/shows/{id}/rematch', { params: { path: { id }, query: { tmdbId } } })
          : await api.GET('/api/movies/{id}/rematch', { params: { path: { id }, query: { tmdbId } } }),
      ),
    staleTime: 60_000,
  })

export function useRematch() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async ({ kind, id, tmdbId, remember }: { kind: RematchKind; id: number; tmdbId: number; remember: boolean }) =>
      unwrap(
        kind === 'show'
          ? await api.POST('/api/shows/{id}/rematch', { params: { path: { id } }, body: { tmdbId, remember } })
          : await api.POST('/api/movies/{id}/rematch', { params: { path: { id } }, body: { tmdbId, remember } }),
      ),
    onSuccess: () => void qc.invalidateQueries(),
  })
}

export function useIgnoreFiles() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async ({ libraryId, fileIds, ignored }: { libraryId: number; fileIds: number[]; ignored: boolean }) =>
      unwrap(await api.POST('/api/libraries/{id}/ignore-files', { params: { path: { id: libraryId } }, body: { fileIds, ignored } })),
    onSuccess: () => void qc.invalidateQueries(),
  })
}

export const preferencesQuery = queryOptions({
  queryKey: ['preferences'],
  queryFn: async () => unwrap(await api.GET('/api/me/preferences')),
  staleTime: 5 * 60_000,
})

type PreferencePatch = components['schemas']['UpdatePreferencesInputBody']
type PreferenceReply = components['schemas']['Preferences']
type ScopedPreferencePatch = { body: PreferencePatch; scope: number }
type PreferenceCallbacks = MutateOptions<PreferenceReply, Error, PreferencePatch>

/** Shared by every settings route: serialize full-reply writes and fence queued
 * payloads before dispatch, as well as replies after a sign-in change. */
export function preferenceMutationOptions(qc: QueryClient) {
  return {
    scope: { id: 'personal-preferences' },
    mutationFn: async ({ body, scope }: ScopedPreferencePatch) => {
      if (scope !== authScope()) throw new Error('Your sign-in changed. Open this page again before saving.')
      return unwrap(await api.PUT('/api/me/preferences', { body }))
    },
    onSuccess: (p: PreferenceReply, { body, scope }: ScopedPreferencePatch) => {
      // Signing out clears the cache. Do not restore a previous person's reply.
      if (scope !== authScope()) return
      qc.setQueryData(preferencesQuery.queryKey, p)
      // The reminders' reply carries the lead time.
      if (body.liveTVReminderMinutes !== undefined) void qc.invalidateQueries({ queryKey: ['live-tv', 'reminders'] })
    },
  }
}

function preferenceCallbacks(options?: PreferenceCallbacks): MutateOptions<PreferenceReply, Error, ScopedPreferencePatch> | undefined {
  if (!options) return
  return {
    onSuccess: (data, variables, result, context) => options.onSuccess?.(data, variables.body, result, context),
    onError: (error, variables, result, context) => options.onError?.(error, variables.body, result, context),
    onSettled: (data, error, variables, result, context) => options.onSettled?.(data, error, variables.body, result, context),
  }
}

export function useUpdatePreferences() {
  const qc = useQueryClient()
  const mutation = useMutation(preferenceMutationOptions(qc))
  return {
    ...mutation,
    variables: mutation.variables?.body,
    mutate: (body: PreferencePatch, options?: PreferenceCallbacks) => mutation.mutate({ body, scope: authScope() }, preferenceCallbacks(options)),
    mutateAsync: (body: PreferencePatch, options?: PreferenceCallbacks) => mutation.mutateAsync({ body, scope: authScope() }, preferenceCallbacks(options)),
  }
}

export const fileSegmentsQuery = (fileId: number) =>
  queryOptions({
    queryKey: ['segments', fileId],
    queryFn: async () => unwrap(await api.GET('/api/files/{id}/segments', { params: { path: { id: fileId } } })),
  })

export function useSaveSegments() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async ({ fileId, ...body }: { fileId: number; manual: components['schemas']['ManualSegment'][]; suppressed: number[]; restored?: number[] }) =>
      unwrap(await api.PUT('/api/files/{id}/segments', { params: { path: { id: fileId } }, body })),
    onSuccess: (out, v) => qc.setQueryData(fileSegmentsQuery(v.fileId).queryKey, out),
  })
}

// Live TV stays outside library/watch-state query keys. Configuration
// mutations invalidate this one family so setup, navigation and guides agree.
export const liveTVCapabilitiesQuery = queryOptions({
  queryKey: ['live-tv', 'capabilities'],
  queryFn: async () => unwrap(await api.GET('/api/live-tv/capabilities')),
  refetchInterval: 30_000,
})
export const liveTVSettingsQuery = queryOptions({
  queryKey: ['live-tv', 'settings'],
  queryFn: async () => unwrap(await api.GET('/api/settings/live-tv')),
})
export const liveTVChannelsQuery = queryOptions({
  queryKey: ['live-tv', 'channels'],
  queryFn: async () => unwrap(await api.GET('/api/live-tv/channels')),
  refetchInterval: 30_000,
})
/** Listings for every channel over FETCH_HOURS from a block boundary (lib/liveGuide); moving the window within a block reuses the reply. */
export const liveTVGuideQuery = (start: number, hours: number) =>
  queryOptions({
    queryKey: ['live-tv', 'guide', start, hours],
    queryFn: async () => unwrap(await api.GET('/api/live-tv/guide', { params: { query: { start: new Date(start).toISOString(), hours } } })),
    staleTime: 5 * 60_000,
  })
export const liveTVGuideSettingsQuery = queryOptions({
  queryKey: ['live-tv', 'guide-settings'],
  queryFn: async () => unwrap(await api.GET('/api/settings/live-tv/guide')),
})
/** Programmes on now and later whose titles have every word of q (API level 15); q needs 2 characters. */
export const liveTVSearchQuery = (q: string) =>
  queryOptions({
    queryKey: ['live-tv', 'search', q],
    queryFn: async () => unwrap(await api.GET('/api/live-tv/guide/search', { params: { query: { q } } })),
    staleTime: 60_000,
  })
/** This person's reminders that haven't ended, and their lead time (API level 15). */
export const liveTVRemindersQuery = queryOptions({
  queryKey: ['live-tv', 'reminders'],
  queryFn: async () => unwrap(await api.GET('/api/live-tv/reminders')),
  // The server reconciles reminders with each listings refresh and says so
  // on live.reminders.changed; the interval covers a dropped event stream.
  refetchInterval: 10 * 60_000,
})

/** Listings, search and reminders show favourites and bells: these refetch when either changes. */
const personalLiveKeys = [['live-tv', 'guide'], ['live-tv', 'channels'], ['live-tv', 'search'], ['live-tv', 'reminders']]
export function invalidatePersonalLiveTV(qc: QueryClient) {
  for (const queryKey of personalLiveKeys) void qc.invalidateQueries({ queryKey })
}

type GuideReply = components['schemas']['LiveTVGuide']
type ChannelsReply = components['schemas']['LiveTVChannelsOutputBody']

/** Stars or unstars a channel for the signed-in person, showing it at once. */
export function useLiveFavourite() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async ({ channelId, favourite }: { channelId: string; favourite: boolean }) => {
      const path = { params: { path: { id: channelId } } }
      unwrap(favourite ? await api.PUT('/api/live-tv/channels/{id}/favourite', path) : await api.DELETE('/api/live-tv/channels/{id}/favourite', path))
    },
    onMutate: ({ channelId, favourite }) => {
      const mark = <T extends { id: string; favourite?: boolean }>(c: T): T => (c.id === channelId ? { ...c, favourite } : c)
      qc.setQueriesData<GuideReply>({ queryKey: ['live-tv', 'guide'] }, (g) => (g?.channels ? { ...g, channels: g.channels.map(mark) } : g))
      qc.setQueriesData<ChannelsReply>({ queryKey: ['live-tv', 'channels'] }, (c) => (c?.items ? { ...c, items: c.items.map(mark) } : c))
    },
    onSettled: () => invalidatePersonalLiveTV(qc),
  })
}

/** Sets a reminder for a programme in the guide, or removes one. */
export function useLiveReminder() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (change: { programmeId: string } | { reminderId: string }) => {
      if ('programmeId' in change) return unwrap(await api.PUT('/api/live-tv/reminders', { body: { programmeId: change.programmeId } }))
      unwrap(await api.DELETE('/api/live-tv/reminders/{id}', { params: { path: { id: change.reminderId } } }))
      return undefined
    },
    onSettled: () => invalidatePersonalLiveTV(qc),
  })
}

export const liveTVActivityQuery = queryOptions({
  queryKey: ['live-tv', 'activity'],
  queryFn: async () => unwrap(await api.GET('/api/live-tv/activity')),
  refetchInterval: 5_000,
})


/** DVR pages are cursor-paged and refreshed by recording events, never polled. */
export const liveTVRecordingsQuery = (section: import('../lib/liveRecordings').RecordingSection) => infiniteQueryOptions({
  queryKey: ['live-tv', 'recordings', section],
  initialPageParam: '',
  queryFn: async ({ pageParam, signal }) => unwrap(await api.GET('/api/live-tv/recordings', { params: { query: { section, cursor: pageParam || undefined } }, signal })),
  getNextPageParam: (last) => last.nextCursor || undefined,
  staleTime: 60_000,
})
export const liveTVRecordingSettingsQuery = queryOptions({
  queryKey: ['live-tv', 'recording-settings'],
  queryFn: async () => unwrap(await api.GET('/api/settings/live-tv/recordings')),
})
export function invalidateRecordings(qc: QueryClient) {
  invalidatePersonalLiveTV(qc)
  void qc.invalidateQueries({ queryKey: ['live-tv', 'recordings'] })
  void qc.invalidateQueries({ queryKey: ['live-tv', 'recording'] })
  void qc.invalidateQueries({ queryKey: ['live-tv', 'activity'] })
}

export const liveTVRecordingQuery = (id: string) => queryOptions({
  queryKey: ['live-tv', 'recording', id],
  queryFn: async ({ signal }) => unwrap(await api.GET('/api/live-tv/recordings/{id}', { params: { path: { id } }, signal })),
  staleTime: 60_000,
})

// Rows and lists (PLAN-ROWS.md §6.2, §6.3). Keys: ['row-layout', 'me' |
// 'default', screen] for the editor; ['lists'] for every list query (the
// lists, one list, its entries); ['list-sources'] for the builder's options;
// ['list-preview', …] for its preview, kept apart so a list changing
// doesn't refetch a preview being built.

type RowLayoutReply = components['schemas']['RowLayout']
type RowPlacement = components['schemas']['RowPlacement']
type MediaListBody = components['schemas']['MediaListBody']
type MediaListPatch = components['schemas']['UpdateMediaListInputBody']
type PreviewBody = components['schemas']['PreviewMediaListInputBody']
export type RowLayoutKind = 'me' | 'default'

/** The rows a person can place on a screen, in their order (me), or the household's default order (admins). */
export const rowLayoutQuery = (kind: RowLayoutKind, screen: string) =>
  queryOptions({
    queryKey: ['row-layout', kind, screen],
    queryFn: async () =>
      unwrap(
        kind === 'me'
          ? await api.GET('/api/me/rows/{screen}', { params: { path: { screen } } })
          : await api.GET('/api/rows/defaults/{screen}', { params: { path: { screen } } }),
      ),
  })

/** What shows rows or lists: refetched when either changes, here or on another device. */
export function invalidateRowsAndLists(qc: QueryClient) {
  for (const queryKey of [['home'], ['rows'], ['row-layout'], ['lists'], ['list-sources']]) void qc.invalidateQueries({ queryKey })
}

/** Saves a screen's order (Done in Edit rows, Save in Default rows), or puts it back (Reset). */
export function useSaveRowLayout(kind: RowLayoutKind, screen: string) {
  const qc = useQueryClient()
  const done = (layout: RowLayoutReply) => {
    qc.setQueryData(rowLayoutQuery(kind, screen).queryKey, layout)
    void qc.invalidateQueries({ queryKey: ['home'] })
    void qc.invalidateQueries({ queryKey: ['rows'] })
    // The household's default is what people who never saved follow.
    if (kind === 'default') void qc.invalidateQueries({ queryKey: ['row-layout', 'me', screen] })
  }
  const path = { params: { path: { screen } } }
  const save = useMutation({
    mutationFn: async (rows: RowPlacement[]) =>
      unwrap(kind === 'me' ? await api.PUT('/api/me/rows/{screen}', { ...path, body: { rows } }) : await api.PUT('/api/rows/defaults/{screen}', { ...path, body: { rows } })),
    onSuccess: done,
  })
  const reset = useMutation({
    mutationFn: async () => unwrap(kind === 'me' ? await api.DELETE('/api/me/rows/{screen}', path) : await api.DELETE('/api/rows/defaults/{screen}', path)),
    onSuccess: done,
  })
  return { save, reset }
}

/** The person's own lists, then the household's. */
export const mediaListsQuery = queryOptions({
  queryKey: ['lists'],
  queryFn: async () => unwrap(await api.GET('/api/lists')),
})

export const mediaListQuery = (id: number) =>
  queryOptions({
    queryKey: ['lists', id],
    queryFn: async () => unwrap(await api.GET('/api/lists/{id}', { params: { path: { id } } })),
    // A fetch the page started: look again until it is done.
    refetchInterval: (q) => (q.state.data?.fetching ? 3_000 : false),
  })

/** A page of a list's titles in rank order, with the library's item where it has one. */
export const mediaListEntriesQuery = (id: number, offset: number, limit: number) =>
  queryOptions({
    queryKey: ['lists', id, 'entries', { offset, limit }],
    queryFn: async () => unwrap(await api.GET('/api/lists/{id}/entries', { params: { path: { id }, query: { offset, limit } } })),
    placeholderData: (prev) => prev,
  })

/** Which sources lists can be made from now, TMDB's genres, and how many lists the person has. */
export const mediaListSourcesQuery = queryOptions({
  queryKey: ['list-sources'],
  queryFn: async () => unwrap(await api.GET('/api/list-sources')),
  staleTime: 60_000,
})

/** What a list would hold, without saving it. key is previewKey(body) from lib/mediaLists, so an import's text isn't the key. */
export const mediaListPreviewQuery = (key: string, body: PreviewBody) =>
  queryOptions({
    queryKey: ['list-preview', key],
    queryFn: async ({ signal }) => unwrap(await api.POST('/api/lists/preview', { body, signal })),
    placeholderData: (prev) => prev,
    staleTime: 5 * 60_000,
    retry: false,
  })

export function useCreateMediaList() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (body: MediaListBody) => unwrap(await api.POST('/api/lists', { body })),
    onSuccess: () => invalidateRowsAndLists(qc),
  })
}

export function useUpdateMediaList() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async ({ id, body }: { id: number; body: MediaListPatch }) => unwrap(await api.PATCH('/api/lists/{id}', { params: { path: { id } }, body })),
    onSuccess: (list) => {
      qc.setQueryData(mediaListQuery(list.id).queryKey, list)
      invalidateRowsAndLists(qc)
    },
  })
}

export function useDeleteMediaList() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (id: number) => unwrap(await api.DELETE('/api/lists/{id}', { params: { path: { id } } })),
    onSuccess: (_, id) => {
      qc.removeQueries({ queryKey: ['lists', id] })
      invalidateRowsAndLists(qc)
    },
  })
}

/** Fetches a list again now; the server answers 409 while a fetch runs. */
export function useRefreshMediaList() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (id: number) => unwrap(await api.POST('/api/lists/{id}/refresh', { params: { path: { id } } })),
    onSuccess: (list) => {
      qc.setQueryData(mediaListQuery(list.id).queryKey, list)
      void qc.invalidateQueries({ queryKey: ['lists', list.id, 'entries'] })
    },
  })
}

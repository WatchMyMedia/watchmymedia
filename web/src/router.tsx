import { createRootRouteWithContext, createRoute, createRouter, lazyRouteComponent, redirect, type RouteComponent } from '@tanstack/react-router'
import type { QueryClient } from '@tanstack/react-query'
import { homeQuery, mediaListQuery, movieQuery, seasonQuery, showQuery } from './api/queries'
import { Layout } from './Layout'
import { Home } from './pages/Home'
import { LibraryBrowse } from './pages/LibraryBrowse'
import { Movie } from './pages/Movie'
import { Search } from './pages/Search'
import { ShowPage } from './pages/ShowPage'
import { validateHistorySearch, validateStatsSearch } from './pages/activity/shared'
import { episodeRedirect, validateBrowseSearch, validateEditSearch, validateGuideSearch, validateNewListSearch, validatePlaySearch, validateSeasonSearch, type BrowseSearch } from './lib/routeSearch'
import { validateSetupSearch } from './lib/setupRules'

// Pages most visits never open load on first use, keeping the player's
// hls.js, settings and activity out of the first download. The router
// fetches a page's code when a link to it is hovered or followed.
const LiveTV = lazyRouteComponent(() => import('./pages/LiveTV'), 'LiveTV')
const LiveRecordings = lazyRouteComponent(() => import('./pages/LiveRecordings'), 'LiveRecordings')
const LivePlayer = lazyRouteComponent(() => import('./pages/LivePlayer'), 'LivePlayer')
const LiveTVSettings = lazyRouteComponent(() => import('./pages/settings/LiveTV'), 'LiveTVSettings')
const Player = lazyRouteComponent(() => import('./pages/Player'), 'Player')
const TrailerPage = lazyRouteComponent(() => import('./pages/Trailer'), 'TrailerPage')
const SettingsLayout = lazyRouteComponent(() => import('./pages/settings/SettingsLayout'), 'SettingsLayout')
const PlaybackSettings = lazyRouteComponent(() => import('./pages/settings/Playback'), 'PlaybackSettings')
const AccountSettings = lazyRouteComponent(() => import('./pages/settings/Account'), 'AccountSettings')
const GeneralSettings = lazyRouteComponent(() => import('./pages/settings/General'), 'GeneralSettings')
const LibrariesSettings = lazyRouteComponent(() => import('./pages/settings/Libraries'), 'LibrariesSettings')
const UsersSettings = lazyRouteComponent(() => import('./pages/settings/Users'), 'UsersSettings')
const HistorySettings = lazyRouteComponent(() => import('./pages/settings/History'), 'HistorySettings')
const TranscodingSettings = lazyRouteComponent(() => import('./pages/settings/Transcoding'), 'TranscodingSettings')
const AudioSubtitlesSettings = lazyRouteComponent(() => import('./pages/settings/AudioSubtitles'), 'AudioSubtitlesSettings')
const SecuritySettings = lazyRouteComponent(() => import('./pages/settings/Security'), 'SecuritySettings')
const BackupsSettings = lazyRouteComponent(() => import('./pages/settings/Backups'), 'BackupsSettings')
const PlexSettings = lazyRouteComponent(() => import('./pages/settings/Plex'), 'PlexSettings')
const PlexHistory = lazyRouteComponent(() => import('./pages/activity/PlexHistory'), 'PlexHistory')
const IntegrationsSettings = lazyRouteComponent(() => import('./pages/settings/Integrations'), 'IntegrationsSettings')
const MyRequestsSettings = lazyRouteComponent(() => import('./pages/settings/MyRequests'), 'MyRequestsSettings')
const InboxSettings = lazyRouteComponent(() => import('./pages/settings/Inbox'), 'InboxSettings')
const ListsSettings = lazyRouteComponent(() => import('./pages/settings/Lists'), 'ListsSettings')
const AboutSettings = lazyRouteComponent(() => import('./pages/settings/About'), 'AboutSettings')
const ListPage = lazyRouteComponent(() => import('./pages/ListPage'), 'ListPage')
const MakeListPage = lazyRouteComponent(() => import('./pages/MakeList'), 'MakeListPage')
const EditListPage = lazyRouteComponent(() => import('./pages/MakeList'), 'EditListPage')
const Setup = lazyRouteComponent(() => import('./pages/Setup'), 'Setup')
const LibraryFiles = lazyRouteComponent(() => import('./pages/LibraryFiles'), 'LibraryFiles')
const LinkDevice = lazyRouteComponent(() => import('./pages/Link'), 'LinkDevice')
const ActivityLayout = lazyRouteComponent(() => import('./pages/activity/ActivityLayout'), 'ActivityLayout')
const NowPlaying = lazyRouteComponent(() => import('./pages/activity/NowPlaying'), 'NowPlaying')
const History = lazyRouteComponent(() => import('./pages/activity/History'), 'History')
const Stats = lazyRouteComponent(() => import('./pages/activity/Stats'), 'Stats')
const Users = lazyRouteComponent(() => import('./pages/activity/Users'), 'Users')
const UserDetail = lazyRouteComponent(() => import('./pages/activity/Users'), 'UserDetail')
const Downloads = lazyRouteComponent(() => import('./pages/activity/Downloads'), 'Downloads')

/** Lets the router preload the lazy page a route's component renders. */
function rendering(component: RouteComponent, page: { preload?: () => Promise<void> | undefined }): RouteComponent {
  return Object.assign(component, { preload: () => page.preload?.() })
}

type RouterContext = { queryClient: QueryClient }

const rootRoute = createRootRouteWithContext<RouterContext>()({ component: Layout })

// Loaders start a page's data with the navigation (or the hover before it)
// rather than once the page renders. They prefetch without waiting, so the
// page appears at once and shows any error itself.
const indexRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/',
  validateSearch: validateEditSearch,
  loader: ({ context }) => void context.queryClient.prefetchQuery(homeQuery),
  component: function HomeRoute() {
    return <Home search={indexRoute.useSearch()} />
  },
})

// A new view, library or See all's grid options start the page afresh; a new search term doesn't.
const browseKey = (s: BrowseSearch) => JSON.stringify({ ...s, q: undefined, edit: undefined, add: undefined })

const tvRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/tv',
  validateSearch: validateBrowseSearch,
  component: function TVRoute() {
    const search = tvRoute.useSearch()
    return <LibraryBrowse key={browseKey(search)} type="tv" search={search} />
  },
})

const moviesRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/movies',
  validateSearch: validateBrowseSearch,
  component: function MoviesRoute() {
    const search = moviesRoute.useSearch()
    return <LibraryBrowse key={browseKey(search)} type="movie" search={search} />
  },
})

// Settings: each tab is its own route. Wide screens show Playback on
// /settings itself; phones list the tabs there. /admin was its old address.
const adminRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/admin',
  beforeLoad: () => {
    throw redirect({ to: '/settings', replace: true })
  },
})
const settingsRoute = createRoute({ getParentRoute: () => rootRoute, path: '/settings', component: SettingsLayout })
const settingsIndexRoute = createRoute({ getParentRoute: () => settingsRoute, path: '/', component: PlaybackSettings })
const settingsPlaybackRoute = createRoute({ getParentRoute: () => settingsRoute, path: '/playback', component: PlaybackSettings })
const settingsAccountRoute = createRoute({ getParentRoute: () => settingsRoute, path: '/account', component: AccountSettings })
const settingsGeneralRoute = createRoute({ getParentRoute: () => settingsRoute, path: '/general', component: GeneralSettings })
const settingsLiveTVRoute = createRoute({ getParentRoute: () => settingsRoute, path: '/live-tv', component: LiveTVSettings })
const settingsLibrariesRoute = createRoute({ getParentRoute: () => settingsRoute, path: '/libraries', component: LibrariesSettings })
const settingsUsersRoute = createRoute({ getParentRoute: () => settingsRoute, path: '/users', component: UsersSettings })
const settingsHistoryRoute = createRoute({ getParentRoute: () => settingsRoute, path: '/history', component: HistorySettings })
const settingsTranscodingRoute = createRoute({ getParentRoute: () => settingsRoute, path: '/transcoding', component: TranscodingSettings })
const settingsSubtitlesRoute = createRoute({ getParentRoute: () => settingsRoute, path: '/subtitles', component: AudioSubtitlesSettings })
const settingsSignInRoute = createRoute({ getParentRoute: () => settingsRoute, path: '/sign-in', beforeLoad: () => { throw redirect({ to: '/settings/security', replace: true }) } })
const settingsSecurityRoute = createRoute({ getParentRoute: () => settingsRoute, path: '/security', component: SecuritySettings })
const settingsBackupsRoute = createRoute({ getParentRoute: () => settingsRoute, path: '/backups', component: BackupsSettings })
const settingsIntegrationsRoute = createRoute({ getParentRoute: () => settingsRoute, path: '/integrations', component: IntegrationsSettings })
const settingsPlexRoute = createRoute({ getParentRoute: () => settingsRoute, path: '/plex', component: PlexSettings })
const settingsRequestsRoute = createRoute({ getParentRoute: () => settingsRoute, path: '/requests', component: MyRequestsSettings })
const settingsInboxRoute = createRoute({ getParentRoute: () => settingsRoute, path: '/inbox', component: InboxSettings })
const settingsListsRoute = createRoute({ getParentRoute: () => settingsRoute, path: '/lists', component: ListsSettings })
const settingsAboutRoute = createRoute({ getParentRoute: () => settingsRoute, path: '/about', component: AboutSettings })
const setupRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/setup',
  validateSearch: validateSetupSearch,
  component: rendering(function SetupRoute() {
    return <Setup step={setupRoute.useSearch().step} />
  }, Setup),
})

const searchRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/search',
  validateSearch: (s: Record<string, unknown>): { q?: string } => ({ q: typeof s.q === 'string' && s.q ? s.q : undefined }),
  component: function SearchRoute() {
    const { q } = searchRoute.useSearch()
    return <Search q={q ?? ''} />
  },
})

// A list's See all page: every title in order, the missing ones dimmed (PLAN-ROWS.md §5).
const listRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/lists/$listId',
  loader: ({ context, params }) => void context.queryClient.prefetchQuery(mediaListQuery(Number(params.listId))),
  component: rendering(function ListRoute() {
    const { listId } = listRoute.useParams()
    return <ListPage key={listId} listId={Number(listId)} />
  }, ListPage),
})

// Make a list (PLAN-ROWS.md §5): from Add a row, or Settings › Lists for the household.
const newListRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/lists/new',
  validateSearch: validateNewListSearch,
  component: rendering(function NewListRoute() {
    const { screen, back } = newListRoute.useSearch()
    return <MakeListPage key={screen} screen={screen} back={back} />
  }, MakeListPage),
})
const editListRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/lists/$listId/edit',
  loader: ({ context, params }) => void context.queryClient.prefetchQuery(mediaListQuery(Number(params.listId))),
  component: rendering(function EditListRoute() {
    const { listId } = editListRoute.useParams()
    return <EditListPage key={listId} listId={Number(listId)} />
  }, EditListPage),
})

const libraryFilesRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/libraries/$libraryId/files',
  component: rendering(function LibraryFilesRoute() {
    const { libraryId } = libraryFilesRoute.useParams()
    return <LibraryFiles libraryId={Number(libraryId)} />
  }, LibraryFiles),
})

const movieRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/movies/$movieId',
  loader: ({ context, params }) => void context.queryClient.prefetchQuery(movieQuery(Number(params.movieId))),
  component: function MovieRoute() {
    const { movieId } = movieRoute.useParams()
    return <Movie movieId={Number(movieId)} />
  },
})

// A movie's YouTube trailer, full screen like the player (PLAN-TRAILERS.md §6.1).
// A trailer file plays in the player instead.
const trailerRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/movies/$movieId/trailer',
  loader: ({ context, params }) => void context.queryClient.prefetchQuery(movieQuery(Number(params.movieId))),
  component: rendering(function TrailerRoute() {
    const { movieId } = trailerRoute.useParams()
    return <TrailerPage key={movieId} movieId={Number(movieId)} />
  }, TrailerPage),
})

// The one show page (PLAN-SHOW-PAGE.md): the show's, a season's and an
// episode's addresses draw the same page, and only the episode on top
// differs. Keyed by show, so another show starts afresh; within a show the
// page keeps its place as the address moves between episodes.
const showRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/shows/$showId',
  loader: ({ context, params }) => void context.queryClient.prefetchQuery(showQuery(Number(params.showId))),
  component: function ShowRoute() {
    const { showId } = showRoute.useParams()
    return <ShowPage key={showId} showId={Number(showId)} />
  },
})

// ?episode= (an episode opened in place, before episodes had pages) and
// ?at= (the episode a Home card outlined) go to the episode's address.
const seasonRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/shows/$showId/seasons/$number',
  validateSearch: validateSeasonSearch,
  beforeLoad: ({ params, search }) => {
    const to = episodeRedirect(params, search)
    if (to) throw redirect({ ...to, replace: true })
  },
  loader: ({ context, params }) => {
    void context.queryClient.prefetchQuery(showQuery(Number(params.showId)))
    void context.queryClient.prefetchQuery(seasonQuery(Number(params.showId), Number(params.number)))
  },
  component: function SeasonRoute() {
    const { showId, number } = seasonRoute.useParams()
    return <ShowPage key={showId} showId={Number(showId)} season={Number(number)} />
  },
})

// The show page with this episode on top.
const episodeRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/shows/$showId/seasons/$number/episodes/$episodeId',
  loader: ({ context, params }) => {
    void context.queryClient.prefetchQuery(showQuery(Number(params.showId)))
    void context.queryClient.prefetchQuery(seasonQuery(Number(params.showId), Number(params.number)))
  },
  component: function EpisodeRoute() {
    const { showId, number, episodeId } = episodeRoute.useParams()
    return <ShowPage key={showId} showId={Number(showId)} season={Number(number)} episodeId={Number(episodeId)} />
  },
})

const liveTVRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/live-tv',
  validateSearch: validateGuideSearch,
  component: function LiveTVRoute() {
    return <LiveTV open={liveTVRoute.useSearch()} />
  },
})
const liveRecordingsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/live-tv/recordings',
  component: LiveRecordings,
})
const recordingPlayerRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/live-tv/recordings/$recordingId/watch',
  validateSearch: validatePlaySearch,
  component: rendering(function RecordingPlayerRoute() {
    const { recordingId } = recordingPlayerRoute.useParams()
    // Whoever was taken over may not be able to see Recordings: each link says where Back goes.
    const { start, title, back } = recordingPlayerRoute.useSearch()
    return <Player key={recordingId} fileId={0} recordingId={recordingId} start={start} title={title} back={back ?? '/live-tv'} />
  }, Player),
})
const livePlayerRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/live-tv/watch/$channelId',
  component: rendering(function LivePlayerRoute() {
    // Preserve the controller across channel changes so release precedes retune.
    return <LivePlayer channelId={livePlayerRoute.useParams().channelId} />
  }, LivePlayer),
})

const playRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/play/$fileId',
  validateSearch: validatePlaySearch,
  component: rendering(function PlayRoute() {
    const { fileId } = playRoute.useParams()
    const { start, title, back, trailer } = playRoute.useSearch()
    // Keyed by file so moving to the next episode starts with fresh player state.
    return <Player key={fileId} fileId={Number(fileId)} start={start} title={title} back={back} trailer={trailer} />
  }, Player),
})

// Approving a pairing code; a TV's QR code and the phone apps open /link?code=...
const linkRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/link',
  validateSearch: (s: Record<string, unknown>): { code?: string } => ({ code: typeof s.code === 'string' && s.code ? s.code : undefined }),
  component: rendering(function LinkRoute() {
    const { code } = linkRoute.useSearch()
    return <LinkDevice key={code ?? ''} code={code} />
  }, LinkDevice),
})

// Activity: each tab is its own route, with its filters in the URL.
const activityRoute = createRoute({ getParentRoute: () => rootRoute, path: '/activity', component: ActivityLayout })
const nowPlayingRoute = createRoute({ getParentRoute: () => activityRoute, path: '/', component: NowPlaying })
const historyRoute = createRoute({
  getParentRoute: () => activityRoute,
  path: '/history',
  validateSearch: validateHistorySearch,
  component: rendering(function HistoryRoute() {
    return <History search={historyRoute.useSearch()} />
  }, History),
})
const plexHistoryRoute = createRoute({ getParentRoute: () => activityRoute, path: '/plex-history', component: PlexHistory })
const statsRoute = createRoute({
  getParentRoute: () => activityRoute,
  path: '/stats',
  validateSearch: validateStatsSearch,
  component: rendering(function StatsRoute() {
    return <Stats search={statsRoute.useSearch()} />
  }, Stats),
})
const activityUsersRoute = createRoute({ getParentRoute: () => activityRoute, path: '/users', component: Users })
const activityDownloadsRoute = createRoute({ getParentRoute: () => activityRoute, path: '/downloads', component: Downloads })
const activityUserRoute = createRoute({
  getParentRoute: () => activityRoute,
  path: '/users/$userId',
  component: rendering(function ActivityUserRoute() {
    const { userId } = activityUserRoute.useParams()
    return <UserDetail key={userId} userId={Number(userId)} />
  }, UserDetail),
})

const routeTree = rootRoute.addChildren([
  indexRoute,
  tvRoute,
  moviesRoute,
  adminRoute,
  settingsRoute.addChildren([
    settingsIndexRoute,
    settingsPlaybackRoute,
    settingsAccountRoute,
    settingsGeneralRoute,
    settingsLibrariesRoute,
    settingsLiveTVRoute,
    settingsUsersRoute,
    settingsIntegrationsRoute,
    settingsPlexRoute,
    settingsRequestsRoute,
    settingsInboxRoute,
    settingsListsRoute,
    settingsAboutRoute,
    settingsHistoryRoute,
    settingsTranscodingRoute,
    settingsSubtitlesRoute,
    settingsSignInRoute,
    settingsSecurityRoute,
    settingsBackupsRoute,
  ]),
  setupRoute,
  searchRoute,
  listRoute,
  newListRoute,
  editListRoute,
  libraryFilesRoute,
  movieRoute,
  trailerRoute,
  showRoute,
  seasonRoute,
  episodeRoute,
  playRoute,
  liveTVRoute,
  livePlayerRoute,
  liveRecordingsRoute,
  recordingPlayerRoute,
  linkRoute,
  activityRoute.addChildren([nowPlayingRoute, historyRoute, plexHistoryRoute, statsRoute, activityUsersRoute, activityUserRoute, activityDownloadsRoute]),
])

// The query cache decides what is fresh, so the router runs loaders on
// every preload rather than keeping its own copy (TanStack's advice when
// the two are used together). main.tsx provides the query client.
export const router = createRouter({
  routeTree,
  context: { queryClient: undefined! },
  defaultPreload: 'intent',
  defaultPreloadStaleTime: 0,
})

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
  /** Carried to the page a navigation lands on: the show page says which episode of the season was just deleted, and which one it shows instead. */
  interface HistoryState {
    deleted?: { title: string; season: number; which?: 'next' | 'previous' }
  }
}

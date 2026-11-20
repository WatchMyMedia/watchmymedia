import type { ComponentType } from 'react'
import { HomeScreen } from '../screens/browse/HomeScreen'
import { LibraryScreen } from '../screens/browse/LibraryScreen'
import { ListScreen } from '../screens/browse/ListScreen'
import { MovieScreen } from '../screens/details/MovieScreen'
import { ShowScreen } from '../screens/details/ShowScreen'
import { PlayerScreen } from '../screens/player/PlayerScreen'
import { LiveScreen } from '../screens/live/LiveScreen'
import { SearchScreen } from '../screens/search/SearchScreen'
import { SettingsScreen } from '../screens/settings/SettingsScreen'
import { RequestsScreen } from '../screens/settings/RequestsScreen'
import { LiveGuideScreen } from '../screens/live/LiveGuideScreen'
import { RecordingsScreen } from '../screens/live/RecordingsScreen'
import { RemindersScreen } from '../screens/live/RemindersScreen'

// Every screen of the signed-in app: the one union of routes, and the
// registry that draws each. A feature adds its route here (a variant in
// `Route`, an entry in `ROUTES`) and its screen in its own folder.

/** What the player opens. The player engine (`player/`) plays each kind. */
export type PlayRequest =
  | { kind: 'file'; fileId: number; startMs?: number; playId?: string; title?: string; subtitle?: string; backdropPath?: string }
  | { kind: 'live'; channelId: string }
  | { kind: 'recording'; id: string; startMs?: number; title?: string }
  | { kind: 'trailer'; movieId: number; title?: string; youtubeKey?: string; fileId?: number }

/** Settings' groups (SettingsGroups on the canvas), in the order the left column lists them. */
export type SettingsGroup = 'playback' | 'languages' | 'homeRows' | 'notifications' | 'account' | 'requests' | 'about' | 'server'

export type Route =
  // Sections: the rail opens these, on top of Home.
  | { name: 'home' }
  | { name: 'search' }
  | { name: 'library'; type: 'movie' | 'tv' }
  | { name: 'live' }
  /** Settings, opened at a group (Home's Edit rows opens `homeRows` at that screen). */
  | { name: 'settings'; group?: SettingsGroup; rowsScreen?: 'home' | 'movie' | 'tv' }
  // Pages: each opens on top of what opened it.
  /** A row's See all: its library sorted the row's way (PLAN-SEE-ALL.md). */
  | { name: 'seeAll'; libraryId: number; title: string; sort?: string; filter?: string; genre?: string }
  | { name: 'list'; id: number }
  | { name: 'movie'; id: number }
  /** The show page (PLAN-SHOW-PAGE.md): up next on top, or a season's start, or an episode. */
  | { name: 'show'; id: number; season?: number; episodeId?: number }
  | { name: 'requests'; inbox?: boolean }
  /** The guide, at a channel or a time (ms since the epoch). */
  | { name: 'liveGuide'; channelId?: string; at?: number }
  | { name: 'recordings' }
  | { name: 'reminders' }
  | { name: 'player'; play: PlayRequest }

/** The rail's sections, top to bottom. */
export type Section = 'search' | 'home' | 'shows' | 'movies' | 'live' | 'settings'

/** A screen gets its route; everything else comes from hooks (`app/hooks.ts`). */
export type ScreenProps<N extends Route['name'] = Route['name']> = { route: Extract<Route, { name: N }> }

type RouteDef<N extends Route['name']> = {
  screen: ComponentType<ScreenProps<N>>
  /** The rail section this route is the root of; pages have none and show the section below them. */
  section?: (route: Extract<Route, { name: N }>) => Section
  /** 'full' draws without the rail (the player). */
  chrome?: 'rail' | 'full'
}

export const ROUTES: { [N in Route['name']]: RouteDef<N> } = {
  home: { screen: HomeScreen, section: () => 'home' },
  search: { screen: SearchScreen, section: () => 'search' },
  library: { screen: LibraryScreen, section: (r) => r.type === 'tv' ? 'shows' : 'movies' },
  live: { screen: LiveScreen, section: () => 'live' },
  settings: { screen: SettingsScreen, section: () => 'settings' },
  seeAll: { screen: ListScreen },
  list: { screen: ListScreen },
  movie: { screen: MovieScreen },
  show: { screen: ShowScreen },
  requests: { screen: RequestsScreen },
  liveGuide: { screen: LiveGuideScreen },
  recordings: { screen: RecordingsScreen },
  reminders: { screen: RemindersScreen },
  player: { screen: PlayerScreen, chrome: 'full' },
}

/** Each section's route, for the rail. */
export const SECTION_ROUTES: Record<Section, Route> = {
  search: { name: 'search' },
  home: { name: 'home' },
  shows: { name: 'library', type: 'tv' },
  movies: { name: 'library', type: 'movie' },
  live: { name: 'live' },
  settings: { name: 'settings' },
}

function def<N extends Route['name']>(route: Extract<Route, { name: N }>): RouteDef<N> {
  return ROUTES[route.name as N] as RouteDef<N>
}

/** The section a route is the root of, if it is one. */
export function sectionOf(route: Route): Section | undefined {
  return def(route).section?.(route as never)
}

export function isSection(route: Route): boolean { return !!def(route).section }

export function chromeOf(route: Route): 'rail' | 'full' { return def(route).chrome ?? 'rail' }

/** The same screen: same name and parameters, in any key order. */
export function sameRoute(a: Route, b: Route): boolean {
  return routeKey(a) === routeKey(b)
}

export function routeKey(route: Route): string {
  const sorted = (v: unknown): unknown => v && typeof v === 'object' && !Array.isArray(v)
    ? Object.fromEntries(Object.keys(v).sort().map((k) => [k, sorted((v as Record<string, unknown>)[k])]))
    : v
  return JSON.stringify(sorted(route))
}

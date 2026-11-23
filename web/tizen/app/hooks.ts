import { createContext, useContext, useEffect, useEffectEvent, useSyncExternalStore, type RefObject } from 'react'
import type { Input } from './input'
import { HOLD_EVENT } from './input'
import type { Layer } from './router'
import type { KeyInput } from './keys'
import type { Entry } from './nav'
import type { PlayRequest, Route } from './routes'
import type { ActiveSession } from '../session/active'
import type { AppSession, StartState } from '../session/session'
import type { Player } from '../player'

// The contexts the app provides and the hooks screens use. Screens never
// reach for the DOM's keys or the stack themselves.

export const InputContext = createContext<Input | null>(null)
export const AppContext = createContext<AppSession | null>(null)
export const SessionContext = createContext<ActiveSession | null>(null)
/** The app's one player (player/README.md); none where the TV's AVPlay is missing, as in a desktop browser opening the TV build. */
export const PlayerContext = createContext<Player | undefined>(undefined)
export const NavContext = createContext<Nav | null>(null)
export const EntryContext = createContext<Entry<Route> | null>(null)
/** Which layer a component's keys belong to: Dialog and SidePanel set 'modal' for what they hold. */
export const LayerContext = createContext<Layer>('screen')

/** Moving between screens. */
export type Nav = {
  /** Opens a page on top of this screen (a movie, a list, Requests). */
  push(route: Route): void
  /** Swaps this screen for another, keeping its place in the stack. */
  replace(route: Route): void
  /** A rail section: Home, TV Shows, Movies, Live TV, Search, Settings. */
  openSection(route: Route): void
  /** As Return does: the screen below, or the leave dialog at Home. */
  back(): void
  /** Starts playback: opens the player route (the player engine draws it). */
  play(request: PlayRequest): void
  /** Opens the Servers panel, as the menu's account row does (Choose another server). */
  servers(): void
}

function need<T>(value: T | null, name: string): T {
  if (!value) throw new Error(`${name} is used outside its provider`)
  return value
}

export function useInput(): Input { return need(useContext(InputContext), 'useInput') }
export function useFocus() { return useInput().focus }
export function useApp(): AppSession { return need(useContext(AppContext), 'useApp') }
export function useNav(): Nav { return need(useContext(NavContext), 'useNav') }

/** The app session as it changes: the screen it is on, a switch under way. Screens that list `app.store`'s servers read it to redraw with them. */
export function useAppState(): StartState {
  const app = useApp()
  return useSyncExternalStore(app.subscribe, app.snapshot)
}

/** The app's one Player, which FilePlayback and LivePlayback share; undefined where nothing can play. */
export function usePlayer(): Player | undefined { return useContext(PlayerContext) }

/** The signed-in session: its server, account, query client and media addresses. */
export function useSession(): ActiveSession { return need(useContext(SessionContext), 'useSession') }

/** The screen's own entry in the stack: its key and the FocusMemory it keeps while under others. */
export function useEntry(): Entry<Route> | null { return useContext(EntryContext) }

/** The child a focus group remembers on this screen (a row's card), for drawing around it on the way back. */
export function useGroupMemory(group: string): string | undefined {
  return useEntry()?.memory.groups[group]
}

/**
 * Image and media addresses for the signed-in server, redrawn when a media
 * token arrives: `imageUrl('/abc.jpg', 'w342')`, `mediaUrl('/api/live-tv/art/7')`.
 */
export function useMediaUrls() {
  const session = useSession()
  useSyncExternalStore(session.tokens.subscribe, session.tokens.snapshot)
  return session.media
}

/**
 * Takes remote keys while mounted (and `enabled`): return true to keep a
 * key from going further. Keys go modal → overlay → screen → the app
 * (Return walks back the stack) → moving focus; the layer comes from
 * where the component is (inside a Dialog it is 'modal').
 */
export function useKeys(handler: (input: KeyInput) => boolean | void, enabled = true, layer?: Layer) {
  const input = useInput()
  const contextLayer = useContext(LayerContext)
  const onKey = useEffectEvent(handler)
  const at = layer ?? contextLayer
  useEffect(() => {
    if (!enabled) return
    return input.router.add(at, (i) => onKey(i))
  }, [input, at, enabled])
}

/** Takes Return while mounted: return false to let it go on (to the stack below). */
export function useBack(handler: () => boolean | void, enabled = true, layer?: Layer) {
  useKeys((i) => i.action === 'back' ? handler() !== false : false, enabled, layer)
}

/** Calls `handler` when OK is held on `ref`'s element (or anything inside it). */
export function useHold(ref: RefObject<HTMLElement | null>, handler: (() => void) | undefined) {
  const onHold = useEffectEvent(() => handler?.())
  const has = !!handler
  useEffect(() => {
    const el = ref.current
    if (!el || !has) return
    const listener = (e: Event) => { e.stopPropagation(); onHold() }
    el.addEventListener(HOLD_EVENT, listener)
    return () => el.removeEventListener(HOLD_EVENT, listener)
  }, [ref, has])
}

import type { KeyInput } from './keys'

/**
 * Who sees a key first. A modal (a dialog or a side panel) takes every key
 * while it is open; an overlay (a banner that takes no focus, the open
 * rail) sees keys before the screen; the screen before the app's own
 * handling (moving focus, OK, and Return's walk back through the stack).
 */
export type Layer = 'screen' | 'overlay' | 'modal'
const RANK: Record<Layer, number> = { screen: 1, overlay: 2, modal: 3 }

/** Returns true when it took the key. */
export type KeyHandler = (input: KeyInput) => boolean | void

const TRAPPED = new Set(['up', 'down', 'left', 'right', 'ok', 'hold'])

type Entry = { layer: Layer; handler: KeyHandler; order: number }

/**
 * The key handlers, newest first within a layer. Return goes through the
 * same order: a modal closes first, then an overlay, then the screen's own
 * Return (a search going back to its keyboard), and only then the app's
 * fallback, which pops the stack or asks before leaving at Home.
 */
export class KeyRouter {
  private entries: Entry[] = []
  private order = 0

  add(layer: Layer, handler: KeyHandler): () => void {
    const entry = { layer, handler, order: ++this.order }
    this.entries.push(entry)
    return () => { this.entries = this.entries.filter((e) => e !== entry) }
  }

  /** Whether a modal is open: screens and overlays get no keys then. */
  get modal() { return this.entries.some((e) => e.layer === 'modal') }

  /** Offers the key to each handler in turn; true once one took it. `fallback` runs when none did. */
  dispatch(input: KeyInput, fallback?: KeyHandler): boolean {
    const top = this.modal ? RANK.modal : 0
    const ordered = this.entries.filter((e) => RANK[e.layer] >= top)
      .sort((a, b) => RANK[b.layer] - RANK[a.layer] || b.order - a.order)
    for (const e of ordered) if (e.handler(input) === true) return true
    // Under a modal only moving and choosing reach the fallback (focus stays trapped in it); the rest stop here.
    if (top === RANK.modal && !TRAPPED.has(input.action)) return true
    return fallback?.(input) === true
  }
}

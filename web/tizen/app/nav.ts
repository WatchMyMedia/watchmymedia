import { emptyMemory, type FocusMemory } from './focusRules'

export type Entry<R> = { key: number; route: R; memory: FocusMemory }

export type NavRules<R> = {
  /** Whether a route is a section the rail opens (Home, Movies, Live TV…), rather than a page stacked on one. */
  isSection(route: R): boolean
  /** Whether two routes are the same screen (same name and parameters). */
  same(a: R, b: R): boolean
}

/**
 * The screens open, Home at the bottom, as an immutable list per change so
 * React can draw from it. Sections sit on Home: opening one from the rail
 * makes the stack [Home, section] (Home keeps its memory), and Return from
 * a section goes Home. Pages (a movie, a list, the player) stack on what
 * opened them. Each entry keeps the FocusMemory its screen left behind.
 */
export class NavStack<R> {
  private entries: Entry<R>[]
  private next = 1
  private listeners = new Set<() => void>()

  constructor(private home: R, private rules: NavRules<R>) {
    this.entries = [this.entry(home)]
  }

  private entry(route: R): Entry<R> { return { key: this.next++, route, memory: emptyMemory() } }

  readonly snapshot = () => this.entries
  readonly subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }

  private set(entries: Entry<R>[]) {
    this.entries = entries
    for (const l of this.listeners) l()
  }

  get top(): Entry<R> { return this.entries[this.entries.length - 1] }

  /** Opens a page on top of the current screen. */
  push(route: R) { this.set([...this.entries, this.entry(route)]) }

  /** Swaps the top screen for another (a See all page changing its sort, a show page moving to an episode), keeping its memory. */
  replace(route: R) {
    const top = this.top
    this.set([...this.entries.slice(0, -1), { ...top, route }])
  }

  /**
   * A section from the rail. Home pops back to Home; another section goes
   * back to its entry when it is still in the stack (with what it
   * remembered), else opens fresh on Home. False when it is already the
   * screen on top, which then just takes focus.
   */
  openSection(route: R): boolean {
    if (this.rules.same(this.top.route, route)) return false
    const at = this.entries.findIndex((e) => this.rules.same(e.route, route))
    if (at >= 0) { this.set(this.entries.slice(0, at + 1)); return true }
    if (this.rules.same(route, this.home)) { this.set(this.entries.slice(0, 1)); return true }
    this.set([this.entries[0], this.entry(route)])
    return true
  }

  /** Return: the screen below. False at Home, where there is nothing below (the app asks before leaving). */
  back(): boolean {
    if (this.entries.length <= 1) return false
    this.set(this.entries.slice(0, -1))
    return true
  }

  /** The section the top screen belongs to: the nearest section at or below it. */
  section(): R {
    for (let i = this.entries.length - 1; i >= 0; i--) if (this.rules.isSection(this.entries[i].route)) return this.entries[i].route
    return this.home
  }
}

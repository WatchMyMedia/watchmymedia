import { nearest, type Direction } from './geometry'
import { LeftRun, remember, type FocusMemory } from './focusRules'

// The focus engine: which element the remote's arrows move to. Everything
// focusable carries `data-focus="<id>"` (an id stable across redraws, which
// is how a screen finds its place again). Three scopes split the page:
//   data-scope="rail"    the section rail; only ◀ from the content opens it
//   data-scope="content" the screen
//   data-scope="modal"   a dialog or side panel; focus stays inside it
// Inside them:
//   data-group="<id>"    remembers its last focused child: focus coming in
//                        from outside goes back to it (rows remember their card)
//   data-row             a horizontal row: ◀ ▶ go card by card in order, and
//                        a run of ◀ stops at the first card
//   data-autofocus       where a screen's focus starts when it has no memory
//   data-clip            a box that clips what it holds sideways (a row's
//                        viewport): only items mostly inside it are targets

const FOCUSABLE = '[data-focus]'

function usable(el: HTMLElement) {
  return !el.hasAttribute('disabled') && el.getAttribute('aria-disabled') !== 'true' && !el.closest('[inert]') && el.getClientRects().length > 0
}

function all(scope: ParentNode) {
  return [...scope.querySelectorAll<HTMLElement>(FOCUSABLE)].filter(usable)
}

/** Whether most of an element shows across the box that clips it sideways (a row's viewport). */
function inView(el: HTMLElement) {
  const clip = el.parentElement?.closest<HTMLElement>('[data-clip]')
  if (!clip) return true
  const r = el.getBoundingClientRect(), c = clip.getBoundingClientRect()
  return Math.min(r.right, c.right) - Math.max(r.left, c.left) >= r.width / 2
}

/** The scope an element is in: rail, content or modal. */
export function scopeName(el: Element | null | undefined): string | undefined {
  return el?.closest<HTMLElement>('[data-scope]')?.dataset.scope
}

function groupsOf(el: HTMLElement): string[] {
  const out: string[] = []
  for (let g = el.closest<HTMLElement>('[data-group]'); g; g = g.parentElement?.closest<HTMLElement>('[data-group]') ?? null) {
    if (g.dataset.group) out.push(g.dataset.group)
  }
  return out
}

function escape(id: string) { return id.replace(/["\\]/g, '\\$&') }

export type FocusHooks = {
  /** A ◀ with nothing to its left in the content: open the rail. */
  openRail?(): boolean
  /** A run of ◀ stopped at a row's first card. */
  rowStopped?(row: HTMLElement): void
}

/**
 * Moves focus for the remote and keeps the current screen's FocusMemory.
 * One engine serves the whole app; the shell points it at the memory of
 * the screen on top (`setMemory`) as the stack changes.
 */
export class FocusEngine {
  readonly run = new LeftRun()
  private memory?: FocusMemory
  private restoring?: () => void

  constructor(private root: HTMLElement, private hooks: FocusHooks = {}) {
    root.addEventListener('focusin', this.onFocusIn)
  }

  dispose() {
    this.root.removeEventListener('focusin', this.onFocusIn)
    this.restoring?.()
  }

  setHooks(hooks: FocusHooks) { this.hooks = hooks }

  /** The memory focus changes are written to: the top screen's. */
  setMemory(memory: FocusMemory | undefined) { this.memory = memory }

  private onFocusIn = (event: FocusEvent) => {
    const el = event.target as HTMLElement
    const id = el.dataset?.focus
    if (!id || !this.memory || scopeName(el) !== 'content') return
    remember(this.memory, id, groupsOf(el))
  }

  scope(name: 'rail' | 'content' | 'modal'): HTMLElement | null {
    const found = this.root.querySelectorAll<HTMLElement>(`[data-scope="${name}"]`)
    return found[found.length - 1] ?? null
  }

  /** Where keys go: the newest modal, else the focused element's scope, else the content. */
  private activeScope(): HTMLElement | null {
    const modal = this.scope('modal')
    if (modal) return modal
    const active = document.activeElement as HTMLElement | null
    return active?.closest<HTMLElement>('[data-scope]') ?? this.scope('content')
  }

  /** Focuses an element without the browser's own scrolling; rows and lists scroll themselves. */
  focus(el: HTMLElement) {
    this.restoring?.()
    el.focus({ preventScroll: true })
    if (!el.closest('[data-row], [data-vscroll]')) el.scrollIntoView({ block: 'nearest', inline: 'nearest' })
  }

  /** The element with this data-focus id in a scope, if it can take focus. */
  find(id: string, scope: ParentNode | null = this.root): HTMLElement | undefined {
    const el = scope?.querySelector<HTMLElement>(`[data-focus="${escape(id)}"]`)
    return el && usable(el) ? el : undefined
  }

  focusId(id: string, scope?: ParentNode | null) {
    const el = this.find(id, scope)
    if (el) this.focus(el)
    return !!el
  }

  /** The first focusable element of a scope: its data-autofocus one, else the first in the page's order. */
  focusFirst(scope: ParentNode | null): boolean {
    if (!scope) return false
    const auto = [...scope.querySelectorAll<HTMLElement>('[data-autofocus]')].find(usable)
    const el = auto?.matches(FOCUSABLE) ? auto : auto?.querySelector<HTMLElement>(FOCUSABLE) ?? all(scope)[0]
    if (el) this.focus(el)
    return !!el
  }

  /** Back to the content from the rail: the item it last had, else its first. */
  focusContent(): boolean {
    const content = this.scope('content')
    return (this.memory?.last !== undefined && this.focusId(this.memory.last, content)) || this.focusFirst(content)
  }

  /** Into the rail: its current section. */
  focusRail(): boolean {
    const rail = this.scope('rail')
    const current = rail?.querySelector<HTMLElement>('[aria-current="page"]')
    if (current && usable(current)) { this.focus(current); return true }
    return this.focusFirst(rail)
  }

  /**
   * Puts focus back where a screen had it (or on its first item) once the
   * screen has drawn it: tries now, then as the page changes, for up to
   * `waitMs`. A remembered item that never comes gives way to the first
   * after `graceMs`. Stops as soon as anything else takes focus.
   */
  restore(ids: (string | undefined)[], scopeName: 'content' | 'modal' = 'content', waitMs = 4000, graceMs = 600) {
    this.restoring?.()
    const started = Date.now()
    const wanted = ids.filter((id): id is string => !!id)
    const attempt = () => {
      const scope = this.scope(scopeName)
      if (!scope) return false
      for (const id of wanted) if (this.focusId(id, scope)) return true
      if (wanted.length && Date.now() - started < graceMs) return false
      return this.focusFirst(scope)
    }
    if (attempt()) return
    const observer = new MutationObserver(() => { if (attempt()) stop() })
    const timer = setTimeout(() => { attempt(); stop() }, waitMs)
    const grace = setTimeout(() => { if (attempt()) stop() }, graceMs)
    const onFocus = () => stop()
    const stop = () => {
      observer.disconnect(); clearTimeout(timer); clearTimeout(grace)
      this.root.removeEventListener('focusin', onFocus, true)
      if (this.restoring === stop) this.restoring = undefined
    }
    observer.observe(this.root, { childList: true, subtree: true, attributes: true, attributeFilter: ['disabled', 'data-focus'] })
    // Focus taken by anything else (a press, a click) ends the wait.
    queueMicrotask(() => this.root.addEventListener('focusin', onFocus, true))
    this.restoring = stop
  }

  /** The remote's arrow: true when it was handled (moving or staying put). */
  move(direction: Direction): boolean {
    const scope = this.activeScope()
    if (!scope) return false
    const active = document.activeElement as HTMLElement | null
    if (!active || !scope.contains(active) || !active.matches(FOCUSABLE)) {
      if (scope.dataset.scope === 'content' && this.memory?.last && this.focusId(this.memory.last, scope)) return true
      return this.focusFirst(scope) || true
    }
    const horizontal = direction === 'left' || direction === 'right'
    const row = active.closest<HTMLElement>('[data-row]')
    if (row && horizontal && scope.contains(row)) {
      const cards = all(row)
      const i = cards.indexOf(active)
      const next = cards[i + (direction === 'left' ? -1 : 1)]
      if (next) { this.focus(next); return true }
      if (direction === 'right') return true
      // From the first card: a run of ◀ stops here; a single ◀ opens the rail.
      if (this.run.inRun) {
        this.hooks.rowStopped?.(row)
        return true
      }
      if (scope.dataset.scope === 'content') return this.hooks.openRail?.() ?? true
    }
    // Only what shows: a row's cards scrolled out of its view are not targets.
    const candidates = all(scope).filter((el) => el !== active && inView(el))
    const index = nearest(active.getBoundingClientRect(), candidates.map((el) => el.getBoundingClientRect()), direction)
    if (index < 0) {
      if (direction === 'left' && scope.dataset.scope === 'content') return this.hooks.openRail?.() ?? true
      if (direction === 'right' && scope.dataset.scope === 'rail') return this.focusContent() || true
      return true
    }
    const target = candidates[index]
    this.focus(this.rememberedIn(target, active) ?? target)
    return true
  }

  /** Entering a group from outside: the child it remembers, when it is still there. */
  private rememberedIn(target: HTMLElement, from: HTMLElement): HTMLElement | undefined {
    const group = target.closest<HTMLElement>('[data-group]')
    const id = group?.dataset.group
    if (!group || !id || group.contains(from)) return undefined
    const last = this.memory?.groups[id]
    return last ? this.find(last, group) : undefined
  }
}

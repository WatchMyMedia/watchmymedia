import { useEffect, useRef } from 'react'
import type { Input } from './input'

// The remote's pointer (LG's Magic Remote), on a TV whose platform says it
// points. Focus stays the remote's: pointing at something focuses it, so the
// arrows carry on from where the pointer left; a click presses it, as the
// browser's own click on a button does; the wheel steps as the arrows would,
// a card along a row or a row down anything else. Nothing needs the pointer.

/** On <html> while the pointer shows: pointer-only controls draw (`.tv-pointer-only`), and rows and grids hold still under it. */
export const POINTER_CLASS = 'tv-pointer'
/** The least time between two wheel steps. */
export const WHEEL_MS = 150

let shown = false
const movers = new Set<() => void>()

/** Whether the pointer shows now. */
export function pointerShown() { return shown }

function show(on: boolean) {
  if (on === shown) return
  shown = on
  document.documentElement.classList.toggle(POINTER_CLASS, on)
}

/** Calls `fn` whenever the pointer moves: a player brings up its controls, as for a key. */
export function usePointerMove(fn: () => void) {
  const latest = useRef(fn)
  useEffect(() => { latest.current = fn })
  useEffect(() => {
    const call = () => latest.current()
    movers.add(call)
    return () => { movers.delete(call) }
  }, [])
}

const ARROWS = new Set(['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'])

export class PointerInput {
  private x = NaN
  private y = NaN
  private wheelAt = -Infinity

  constructor(private input: Input, private doc: Document = document) {
    doc.addEventListener('cursorStateChange', this.onCursor)
    doc.addEventListener('mousemove', this.onMove, true)
    doc.addEventListener('mousedown', this.onDown, true)
    doc.addEventListener('wheel', this.onWheel, { passive: false })
    doc.addEventListener('keydown', this.onKey, true)
  }

  dispose() {
    this.doc.removeEventListener('cursorStateChange', this.onCursor)
    this.doc.removeEventListener('mousemove', this.onMove, true)
    this.doc.removeEventListener('mousedown', this.onDown, true)
    this.doc.removeEventListener('wheel', this.onWheel)
    this.doc.removeEventListener('keydown', this.onKey, true)
    show(false)
  }

  /** webOS says when its cursor appears and goes (an arrow press hides it; a shake brings it back). */
  private onCursor = (event: Event) => {
    const visible = (event as CustomEvent<{ visibility?: unknown }>).detail?.visibility
    if (typeof visible === 'boolean') show(visible)
  }

  private onKey = (event: KeyboardEvent) => {
    if (ARROWS.has(event.key) || (event.keyCode >= 37 && event.keyCode <= 40)) show(false)
  }

  /**
   * Only a pointer that moved counts: a row that slides under a still
   * pointer focuses nothing, so focusing a card can't set off another.
   */
  private onMove = (event: MouseEvent) => {
    if (event.clientX === this.x && event.clientY === this.y) return
    this.x = event.clientX
    this.y = event.clientY
    show(true)
    for (const mover of movers) mover()
    const target = (event.target as Element | null)?.closest?.<HTMLElement>('[data-focus]')
    if (target && target !== document.activeElement) this.input.focus.pointAt(target)
  }

  /** A click on nothing that takes focus leaves focus where it was, so the arrows carry on from there. */
  private onDown = (event: MouseEvent) => {
    const target = event.target as Element | null
    if (!target?.closest?.('[data-focus], input, textarea')) event.preventDefault()
  }

  private onWheel = (event: WheelEvent) => {
    const delta = Math.abs(event.deltaY) >= Math.abs(event.deltaX) ? event.deltaY : event.deltaX
    if (!delta) return
    event.preventDefault()
    if (event.timeStamp - this.wheelAt < WHEEL_MS) return
    this.wheelAt = event.timeStamp
    const inRow = !!(document.activeElement as HTMLElement | null)?.closest?.('[data-row]')
    const action = inRow ? (delta > 0 ? 'right' : 'left') : (delta > 0 ? 'down' : 'up')
    this.input.dispatch({ action, repeat: false })
  }
}

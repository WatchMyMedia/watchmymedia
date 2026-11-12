import { useEffect, useRef, useState, type CSSProperties, type PointerEvent } from 'react'
import { dragShift, dragStride, dropIndex, type Span } from '../../lib/rowLayout'

type Drag = { from: number; to: number; dy: number; stride: number }

/**
 * Dragging a row by its handle (PLAN-ROWS.md Decision 12): pointer events
 * on the handle, no library. The dragged row follows the pointer, the rows
 * it passes move aside, and letting go calls onDrop(from, to) with to
 * counted in the list without the row, as moveRowTo takes it. Escape or a
 * cancelled pointer puts it back. Move up and Move down do the same from
 * the keyboard.
 */
export function useRowDrag(count: number, onDrop: (from: number, to: number) => void) {
  const items = useRef<(HTMLElement | null)[]>([])
  const start = useRef<{ y: number; spans: Span[]; from: number; to: number } | null>(null)
  const [drag, setDrag] = useState<Drag | null>(null)
  // One Escape handler for the hook's life, so the one added is the one removed.
  const [onKey] = useState(() => {
    const cancel = (e: KeyboardEvent) => {
      if (e.key !== 'Escape' || !start.current) return
      e.preventDefault()
      e.stopPropagation()
      start.current = null
      setDrag(null)
      window.removeEventListener('keydown', cancel, true)
    }
    return cancel
  })
  useEffect(() => () => window.removeEventListener('keydown', onKey, true), [onKey])

  const end = (drop: boolean) => {
    const s = start.current
    start.current = null
    setDrag(null)
    window.removeEventListener('keydown', onKey, true)
    if (drop && s && s.to !== s.from) onDrop(s.from, s.to)
  }

  /** Put on each row's element, so the drag knows where the rows are. */
  const item = (index: number) => (el: HTMLElement | null) => {
    items.current[index] = el
  }

  /** Put on each row's handle. */
  const handle = (index: number) => ({
    onPointerDown: (e: PointerEvent<HTMLElement>) => {
      if (e.button !== 0 || start.current) return
      const spans = items.current.slice(0, count).map((el) => {
        const r = el?.getBoundingClientRect()
        return { top: r?.top ?? 0, height: r?.height ?? 0 }
      })
      e.preventDefault()
      e.currentTarget.setPointerCapture(e.pointerId)
      start.current = { y: e.clientY, spans, from: index, to: index }
      setDrag({ from: index, to: index, dy: 0, stride: dragStride(spans, index) })
      window.addEventListener('keydown', onKey, true)
    },
    onPointerMove: (e: PointerEvent<HTMLElement>) => {
      const s = start.current
      if (!s) return
      const dy = e.clientY - s.y
      const span = s.spans[s.from]
      s.to = dropIndex(span.top + span.height / 2 + dy, s.spans, s.from)
      setDrag((d) => d && { ...d, dy, to: s.to })
    },
    onPointerUp: () => end(true),
    onPointerCancel: () => end(false),
  })

  /** Each row's offset while one is dragged. */
  const style = (index: number): CSSProperties | undefined => {
    if (!drag) return undefined
    if (index === drag.from) return { transform: `translateY(${drag.dy}px)`, position: 'relative', zIndex: 10 }
    return { transform: `translateY(${dragShift(index, drag.from, drag.to, drag.stride)}px)`, transition: 'transform 150ms ease-out' }
  }

  return { item, handle, style, dragging: drag?.from, target: drag?.to }
}

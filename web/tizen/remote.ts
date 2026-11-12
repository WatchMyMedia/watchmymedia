export type Direction = 'left' | 'right' | 'up' | 'down'
type Box = { left: number; top: number; width: number; height: number }

export function nearest(origin: Box, boxes: Box[], direction: Direction): number {
  const horizontal = direction === 'left' || direction === 'right'
  const sign = direction === 'left' || direction === 'up' ? -1 : 1
  const center = (r: Box) => [r.left + r.width / 2, r.top + r.height / 2]
  const [x, y] = center(origin)
  let best = -1, score = Infinity
  boxes.forEach((box, index) => {
    const [cx, cy] = center(box)
    const primary = (horizontal ? cx - x : cy - y) * sign
    const secondary = Math.abs(horizontal ? cy - y : cx - x)
    if (primary < 1) return
    // Prefer controls in the same row/column before diagonal travel.
    const overlap = horizontal ? box.top < origin.top + origin.height && box.top + box.height > origin.top
      : box.left < origin.left + origin.width && box.left + box.width > origin.left
    const cost = primary + secondary * 2 + (overlap ? 0 : 10_000)
    if (cost < score) { best = index; score = cost }
  })
  return best
}

export function focusControl(id: string) {
  const node = [...document.querySelectorAll<HTMLElement>('[data-focus]')].find((el) => el.dataset.focus === id && !el.hasAttribute('disabled'))
  node?.focus({ preventScroll: true }); node?.scrollIntoView({ block: 'nearest', inline: 'nearest' })
}

export function installRemote(actions: { back(): void; toggle(): void; play(): void; pause(): void; stop(): void; seek(ms: number): void }) {
  const handler = (event: KeyboardEvent) => {
    const active = document.activeElement as HTMLElement | null
    const input = active instanceof HTMLInputElement
    const code = event.keyCode
    if (event.key === 'Escape' || code === 10009) {
      event.preventDefault()
      if (input) { active.blur(); focusControl('connect') } else actions.back()
      return
    }
    const dialog = document.querySelector<HTMLElement>('[role="dialog"]')
    const scope = dialog ?? document
    const nodes = [...scope.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled), a[href], [tabindex="0"]')]
      .filter((el) => el.getClientRects().length && getComputedStyle(el).visibility !== 'hidden')
    if (event.key === 'Tab' && dialog) {
      event.preventDefault()
      const current = nodes.indexOf(active!)
      nodes[(current + (event.shiftKey ? -1 : 1) + nodes.length) % nodes.length]?.focus()
      return
    }
    const direction: Direction | undefined = ({ ArrowLeft: 'left', ArrowRight: 'right', ArrowUp: 'up', ArrowDown: 'down' } as const)[event.key as 'ArrowLeft']
      ?? ({ 37: 'left', 39: 'right', 38: 'up', 40: 'down' } as const)[code as 37]
    if (direction) {
      if (input && (direction === 'left' || direction === 'right')) return
      event.preventDefault()
      if (!active || !nodes.includes(active)) { nodes[0]?.focus(); return }
      const next = nearest(active.getBoundingClientRect(), nodes.map((el) => el.getBoundingClientRect()), direction)
      if (next >= 0) { nodes[next].focus(); nodes[next].scrollIntoView({ block: 'nearest', inline: 'nearest' }) }
      return
    }
    if (event.key === 'Enter' || code === 13) {
      event.preventDefault()
      if (input) active.closest('form')?.requestSubmit()
      else active?.click()
      return
    }
    if (input || dialog) return
    const media = ({ 10252: actions.toggle, 415: actions.play, 19: actions.pause, 413: actions.stop,
      412: () => actions.seek(-10_000), 417: () => actions.seek(10_000) } as Record<number, (() => void) | undefined>)[code]
    if (media) { event.preventDefault(); media() }
  }
  document.addEventListener('keydown', handler)
  return () => document.removeEventListener('keydown', handler)
}

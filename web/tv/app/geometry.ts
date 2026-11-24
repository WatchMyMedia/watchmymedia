export type Direction = 'left' | 'right' | 'up' | 'down'
export type Box = { left: number; top: number; width: number; height: number }

/**
 * The box `direction` of `origin` that focus moves to: the index in `boxes`,
 * or -1 when there is none that way (focus never wraps). Boxes in the same
 * row or column come before closer diagonal ones.
 */
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

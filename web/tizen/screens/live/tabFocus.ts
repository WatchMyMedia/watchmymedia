import { useLayoutEffect, useRef } from 'react'
import { useFocus, useKeys } from '../../app/hooks'
import type { LiveTab } from './sectionModel'

// Focus between Live TV's tabs and what each holds.

/**
 * Where a tab's focus starts (its data-autofocus) once it has drawn it:
 * while it loads only the tabs can take focus, so focus that went there by
 * itself moves on. A tab the person just chose keeps focus.
 */
export function useStartFocus(ready: boolean, chosen: boolean | undefined) {
  const focus = useFocus()
  const done = useRef(false)
  useLayoutEffect(() => {
    if (done.current || !ready) return
    done.current = true
    if (chosen) return
    const active = document.activeElement as HTMLElement | null
    if (active && active !== document.body && !active.closest('.live-tabs')) return
    const content = focus.scope('content')
    const start = content?.querySelector<HTMLElement>('.live-body [data-autofocus]')
    const target = start?.matches('[data-focus]') ? start : start?.querySelector<HTMLElement>('[data-focus]')
    if (target) focus.focus(target)
  })
}

/** What can take focus in a tab's body, in the page's order. */
function bodyItems(): HTMLElement[] {
  return [...document.querySelectorAll<HTMLElement>('.live-body [data-focus]')].filter((el) => el.getClientRects().length > 0 && !el.closest('[inert]'))
}

/**
 * ▲ ▼ between Live TV's tabs, a tab's own tabs (`sub`, Recordings' Ready
 * to watch, Scheduled and Series) and what the tab holds: ▼ from a tab goes
 * to where its body starts, ▲ from the top of the body goes back to the
 * tab that is chosen, never to whichever tab is nearest.
 */
export function useTabKeys(tab: LiveTab, sub?: string) {
  const focus = useFocus()
  useKeys((i) => {
    if (i.action !== 'up' && i.action !== 'down') return false
    const active = document.activeElement as HTMLElement | null
    if (!active) return false
    const id = active.dataset.focus ?? ''
    const onTab = id.startsWith('live-tab/'), onSub = !!sub && id.startsWith('rec-tab/')
    if (i.action === 'down' && (onTab || onSub)) {
      if (onTab && sub) return focus.focusId(sub) || false
      const items = bodyItems()
      const start = document.querySelector<HTMLElement>('.live-body [data-autofocus]')
      const target = start?.matches('[data-focus]') ? start : start?.querySelector<HTMLElement>('[data-focus]') ?? items[0]
      if (target) { focus.focus(target); return true }
      return false
    }
    if (i.action === 'up' && onSub) return focus.focusId(`live-tab/${tab}`) || false
    if (i.action === 'up' && active.closest('.live-body')) {
      // The nearest thing above in the body; at its top, the chosen tab.
      const r = active.getBoundingClientRect()
      const above = bodyItems().filter((el) => el !== active && el.getBoundingClientRect().bottom <= r.top + 1)
      if (!above.length) return focus.focusId(sub ?? `live-tab/${tab}`) || false
      const centre = (b: DOMRect) => b.left + b.width / 2
      const best = above.reduce((a, b) => {
        const ra = a.getBoundingClientRect(), rb = b.getBoundingClientRect()
        if (Math.abs(rb.bottom - ra.bottom) > 8) return rb.bottom > ra.bottom ? b : a
        return Math.abs(centre(rb) - centre(r)) < Math.abs(centre(ra) - centre(r)) ? b : a
      })
      focus.focus(best)
      return true
    }
    return false
  })
}

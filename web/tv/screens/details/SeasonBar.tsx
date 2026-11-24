import { useLayoutEffect, useRef, type FocusEvent } from 'react'
import type { components } from '../../../src/api/schema'
import { Button } from '../../ui/Button'
import { Icon } from '../../ui/Icon'
import { seasonName } from './showLabels'
import { tabWords } from './titleWords'

type Season = components['schemas']['Season']

export type SeasonBarProps = {
  tabs: Season[]
  /** The chosen tab: it changes at once as focus reaches a tab. */
  chosen: number
  showMissing: boolean
  /** "Next episode Thu, Nov 18", or with Show missing what is missing. */
  line?: string
  mark?: { label: string; watched: boolean }
  marking: boolean
  /** With Show missing on: the season's own actions. */
  actions?: { request: boolean; requested: boolean; search: boolean; monitor: boolean }
  searching: boolean
  /** Whether to start the screen's focus here: the episode on top has nothing to press. */
  start: boolean
  onTab(n: number): void
  onMark(watched: boolean): void
  onRequest(): void
  onSearch(): void
  onMonitor(): void
  onShowMissing(on: boolean): void
}

/**
 * The season tabs (Specials last): the chosen one underlined in gold and
 * saying how much of it is watched, a tick on a season watched to the end,
 * the episodes on disk on the rest. Then, at the right, when the next
 * episode airs and Mark season watched, and with Show missing on the
 * season's Request, Search now and Monitor and the switch that turns it
 * off. ◀ ▶ go along the bar in order and stop at its ends; focus on a tab
 * changes the season at once. A one-season show has a plain heading in
 * place of the tabs.
 */
export function SeasonBar(p: SeasonBarProps) {
  const track = useRef<HTMLDivElement>(null)
  const view = useRef<HTMLDivElement>(null)
  const shift = useRef(0)
  // Keeps a tab in view when there are more than fit beside the season's buttons: the
  // focused one, and the chosen one as it changes (Show missing adds tabs and buttons).
  const keepInView = (tab: HTMLElement | null | undefined) => {
    const t = track.current, v = view.current
    if (!tab || !t || !v) return
    const left = tab.offsetLeft, right = left + tab.offsetWidth
    let next = shift.current
    if (right - next > v.clientWidth) next = right - v.clientWidth
    else if (left < next) next = left
    next = Math.max(0, next)
    if (next === shift.current) return
    shift.current = next
    t.style.transform = `translateX(${-next}px)`
  }
  const onFocus = (e: FocusEvent) => keepInView((e.target as HTMLElement).closest<HTMLElement>('[role="tab"]'))
  const count = p.tabs.length
  const extras = [p.showMissing, !!p.mark, !!p.actions?.request, !!p.actions?.search, !!p.actions?.monitor].join()
  useLayoutEffect(() => keepInView(track.current?.querySelector<HTMLElement>('[aria-selected="true"]')), [p.chosen, count, extras])
  const one = p.tabs.length <= 1
  return <div className="dt-season-bar" data-row data-zone="seasons" role="group" aria-label="Seasons">
    {one
      ? <h2 className="dt-season-heading">{seasonName(p.chosen)}</h2>
      : <div ref={view} className="dt-tabs" role="tablist" aria-label="Seasons" onFocus={onFocus}>
        <div ref={track} className="dt-tabs__track">
          {p.tabs.map((t) => {
            const chosen = t.number === p.chosen
            const words = tabWords(t, chosen, p.showMissing)
            return <button key={t.number} type="button" role="tab" aria-selected={chosen} data-focus={`tab/${t.number}`}
              data-autofocus={(p.start && chosen) || undefined}
              aria-label={[seasonName(t.number), words.done ? 'watched' : words.count].filter(Boolean).join(', ')}
              className={`dt-tab${t.availableCount === 0 && !chosen ? ' dt-tab--none' : ''}`}
              onFocus={() => p.onTab(t.number)} onClick={() => p.onTab(t.number)}>
              {seasonName(t.number)}
              {words.done && <span className="dt-tab__done"><Icon name="check"/></span>}
              {words.count && <span className="dt-tab__count">{words.count}</span>}
            </button>
          })}
        </div>
      </div>}
    <span className="dt-season-bar__gap"/>
    {p.line && <span className="dt-season-line">{p.line}</span>}
    {p.mark && <span className="dt-small"><Button id="season/mark" busy={p.marking} autoFocus={p.start && one} onPress={() => p.onMark(p.mark!.watched)}>{p.mark.label}</Button></span>}
    {p.actions?.request && <span className="dt-small"><Button id="season/request" icon="plus" onPress={p.onRequest}>Request {seasonName(p.chosen).toLowerCase()}</Button></span>}
    {p.actions?.requested && <span className="dt-season-line">Requested</span>}
    {p.actions?.search && <span className="dt-small"><Button id="season/search" busy={p.searching} onPress={p.onSearch}>{p.searching ? 'Sonarr is searching' : 'Search now'}</Button></span>}
    {p.actions?.monitor && <span className="dt-small"><Button id="season/monitor" onPress={p.onMonitor}>Monitor</Button></span>}
    {p.showMissing && <button type="button" role="switch" aria-checked="true" data-focus="season/missing" className="dt-switch" onClick={() => p.onShowMissing(false)}>
      <span className="dt-switch__knob" aria-hidden="true"/>Show missing
    </button>}
  </div>
}

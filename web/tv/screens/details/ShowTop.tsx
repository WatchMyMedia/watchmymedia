import type { ReactNode } from 'react'
import type { components } from '../../../src/api/schema'
import { isPartlyWatched } from '../../../src/lib/episode'
import type { MissingStatus } from '../../../src/lib/requests'
import { onTopChip, type MissingActions, type OnTopReason } from '../../../src/lib/showPage'
import { Button } from '../../ui/Button'
import { WatchBar } from './DetailParts'
import { Glyph } from './Glyph'
import type { WatchLine } from './titleWords'
import { EPISODE_GONE_NOTE, WATCHED_IT_ALL_NOTE, seasonName } from './showLabels'
import { episodeFactLine, episodeKickerWords, episodeWatch } from './titleWords'

type ShowDetail = components['schemas']['ShowDetail']
type Episode = components['schemas']['Episode']

export type TopProps = {
  show: ShowDetail
  season: number
  e: Episode
  why?: OnTopReason
  gone?: boolean
  missing?: MissingStatus
  actions: MissingActions
  hasMore: boolean
  marking: boolean
  searching: boolean
  today: Date
  now: number
  onPlay(fromStart: boolean): void
  onMark(): void
  onMore(): void
  onRequest(): void
  onSearch(): void
  onMonitor(): void
  onInbox(): void
  /** One card across servers: "Also on Cabin", "On Cabin server · away from home". */
  also?: string
  /** The button after Play naming the copy it uses ("Home · 1080p"), while the show is on two servers or more. */
  pick?: { label: string; onPress(): void }
  /** Play's label and the bar when the latest place is another server's ("Resume · from Cabin"). */
  playLabel?: string
  line?: WatchLine
}

/**
 * The episode on top: the show, season and episode with why it is there,
 * its title, one line of facts, how far it got and when it would end, two
 * lines of story and its buttons (Resume or Play, Start over, Mark
 * watched, More). Its picture fills the screen behind it. Play is where
 * the screen's focus starts; for a missing episode, what can be done
 * about it.
 */
export function ShowTop(p: TopProps) {
  const { e } = p
  const partly = e.hasFile && isPartlyWatched(e)
  const line = p.line ?? episodeWatch(e, p.now)
  const title = e.title || `Episode ${e.number}`
  const buttons: ReactNode[] = []
  if (line) {
    buttons.push(<Button key="play" id="play" variant="primary" icon="play" autoFocus onPress={() => p.onPlay(line.play === 'Play again')}>{p.playLabel ?? line.play}</Button>)
    if (p.pick) buttons.push(<Button key="copies" id="copies" label={`${p.pick.label}. Choose another copy`} onPress={p.pick.onPress}>{p.pick.label}<Glyph name="down"/></Button>)
    if (partly || p.line?.progress !== undefined) buttons.push(<Button key="start" id="start" onPress={() => p.onPlay(true)}>Start over</Button>)
    buttons.push(<Button key="watched" id="watched" busy={p.marking} onPress={p.onMark}>{e.watch?.completed ? 'Mark unwatched' : 'Mark watched'}</Button>)
  } else {
    if (p.actions.request) buttons.push(<Button key="request" id="request" variant="primary" icon="plus" autoFocus onPress={p.onRequest}>Request {seasonName(p.season).toLowerCase()}</Button>)
    if (p.actions.search) buttons.push(<Button key="search" id="search" busy={p.searching} autoFocus={!buttons.length} onPress={p.onSearch}>{p.searching ? 'Sonarr is searching' : 'Search now'}</Button>)
    if (p.actions.monitor) buttons.push(<Button key="monitor" id="monitor" autoFocus={!buttons.length} onPress={p.onMonitor}>Monitor {seasonName(p.season).toLowerCase()}</Button>)
    if (p.actions.inbox) buttons.push(<Button key="inbox" id="inbox" autoFocus={!buttons.length} onPress={p.onInbox}>Open in Inbox</Button>)
  }
  if (p.hasMore) buttons.push(<Button key="more" id="more" autoFocus={!buttons.length} onPress={p.onMore}>More</Button>)
  return <section className="dt-head dt-head--show" aria-label={`${seasonName(p.season)}, episode ${e.number}: ${title}${p.missing ? `, ${p.missing.label}` : ''}`} data-zone="top">
    <p className="dt-kickerline">
      <span className="tv-kicker dt-kickerline__text">{episodeKickerWords(p.show.title, p.season, e.number)}</span>
      {p.why && <span className="tv-tag dt-gold-tag">{onTopChip[p.why]}</span>}
      {e.isNew && !e.watch?.completed && <span className="tv-badge tv-badge--gold">New</span>}
    </p>
    <h1 className={`tv-display dt-name${e.hasFile ? '' : ' dt-name--off'}`}>{title}</h1>
    <p className="dt-factline">{episodeFactLine(e, p.show, p.today)}</p>
    {p.also && <p className="dt-alsoline">{p.also}</p>}
    {line && <WatchBar line={line}/>}
    {p.missing && <p className={`dt-watch dt-tone--${p.missing.tone}`}><span className="dt-watch__where">{p.missing.label}</span></p>}
    {p.missing?.progress !== undefined && <span className="tv-bar dt-watch__bar" aria-hidden="true"><i style={{ width: `${Math.round(p.missing.progress * 100)}%` }}/></span>}
    {p.gone && <p className="dt-note">{EPISODE_GONE_NOTE}</p>}
    {p.why === 'again' && <p className="dt-note">{WATCHED_IT_ALL_NOTE}</p>}
    {e.overview && <p className="dt-story dt-story--two">{e.overview}</p>}
    {p.actions.requested && <p className="dt-note">Requested</p>}
    <div className="dt-actions" data-group="actions">{buttons}</div>
  </section>
}

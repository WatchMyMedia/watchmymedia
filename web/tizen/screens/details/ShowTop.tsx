import type { ReactNode } from 'react'
import type { components } from '../../../src/api/schema'
import { isPartlyWatched, minutesLeft } from '../../../src/lib/episode'
import type { MissingStatus } from '../../../src/lib/requests'
import { onTopChip, type MissingActions, type OnTopReason } from '../../../src/lib/showPage'
import { useMediaUrls } from '../../app/hooks'
import { Button } from '../../ui/Button'
import { Facts, MoreButton, Picture, Progress, Tag } from './DetailParts'
import { toneStyle } from './detailWords'
import { Glyph } from './Glyph'
import { EPISODE_GONE_NOTE, WATCHED_IT_ALL_NOTE, episodeFacts, episodeKicker, episodeProgress, episodeTech, playLabel, seasonName } from './showLabels'

type ShowDetail = components['schemas']['ShowDetail']
type Episode = components['schemas']['Episode']
type MediaInfo = components['schemas']['MediaInfo']

export type TopProps = {
  show: ShowDetail
  season: number
  e: Episode
  why?: OnTopReason
  gone?: boolean
  /** The episode's file's tracks, once fetched, for its subtitles line. */
  probe?: Pick<MediaInfo, 'audio' | 'subtitles'>
  missing?: MissingStatus
  actions: MissingActions
  hasMore: boolean
  marking: boolean
  searching: boolean
  today: Date
  onPlay(fromStart: boolean): void
  onMark(): void
  onMore(): void
  onRequest(): void
  onSearch(): void
  onMonitor(): void
  onInbox(): void
}

/**
 * The episode on top: the show and its code with
 * why it is there, its title, facts, overview, file and progress, its
 * buttons, and its still on the right. Play is where the screen's focus
 * starts; for a missing episode, what can be done about it.
 */
export function ShowTop(p: TopProps) {
  const media = useMediaUrls()
  const { e } = p
  const partly = e.hasFile && isPartlyWatched(e)
  const progress = episodeProgress(e)
  const play = playLabel(e)
  const tech = episodeTech(e.files[0], p.probe)
  const left = minutesLeft(e)
  const title = e.title || `Episode ${e.number}`
  const buttons: ReactNode[] = []
  if (play) {
    buttons.push(<Button key="play" id="play" variant="primary" icon="play" autoFocus onPress={() => p.onPlay(false)}>{play}</Button>)
    if (partly) buttons.push(<Button key="start" id="start" onPress={() => p.onPlay(true)}>From the start</Button>)
    buttons.push(<Button key="watched" id="watched" busy={p.marking} onPress={p.onMark}>{e.watch?.completed ? 'Mark unwatched' : 'Mark watched'}</Button>)
  } else {
    if (p.actions.request) buttons.push(<Button key="request" id="request" variant="primary" autoFocus onPress={p.onRequest}><Glyph name="plus"/>Request {seasonName(p.season).toLowerCase()}</Button>)
    if (p.actions.search) buttons.push(<Button key="search" id="search" busy={p.searching} autoFocus={!buttons.length} onPress={p.onSearch}>{p.searching ? 'Sonarr is searching' : 'Search now'}</Button>)
    if (p.actions.monitor) buttons.push(<Button key="monitor" id="monitor" autoFocus={!buttons.length} onPress={p.onMonitor}>Monitor {seasonName(p.season).toLowerCase()}</Button>)
    if (p.actions.inbox) buttons.push(<Button key="inbox" id="inbox" autoFocus={!buttons.length} onPress={p.onInbox}>Open in Inbox</Button>)
  }
  if (p.hasMore) buttons.push(<MoreButton key="more" id="more" onPress={p.onMore}/>)
  return <>
    <section className="dt-hero" aria-label={`${seasonName(p.season)}, episode ${e.number}: ${title}${p.missing ? `, ${p.missing.label}` : ''}`} data-zone="top">
      <p className="dt-eyebrow">
        <span className="dt-eyebrow__text">{episodeKicker(p.show.title, p.season, e.number)}</span>
        {p.why && <span className="dt-chip">{onTopChip[p.why]}</span>}
        {e.isNew && !e.watch?.completed && <span className="dt-new">New</span>}
      </p>
      <h1 className={`dt-hero__title${e.hasFile ? '' : ' dt-hero__title--off'}`}>{title}</h1>
      <Facts parts={episodeFacts(e, p.today)}>
        {e.hasFile && e.watch?.completed && <span className="dt-watched"><span className="dt-check"><Glyph name="check"/></span>Watched</span>}
      </Facts>
      {p.gone && <p className="dt-note">{EPISODE_GONE_NOTE}</p>}
      {e.overview && <p className="dt-hero__overview">{e.overview}</p>}
      {p.why === 'again' && <p className="dt-note">{WATCHED_IT_ALL_NOTE}</p>}
      {tech.tags.length > 0 && <p className="dt-tech">{tech.tags.map((t) => <Tag key={t}>{t}</Tag>)}{tech.line && <span className="dt-tech__line">{tech.line}</span>}</p>}
      {partly && progress !== undefined && <div className="dt-left"><Progress value={progress}/>{left > 0 && <span>{left} min left</span>}</div>}
      {p.missing && <p className={`dt-status dt-tone--${p.missing.tone}`}>{p.missing.label}</p>}
      {p.missing?.progress !== undefined && <div className="dt-left"><Progress value={p.missing.progress}/></div>}
      {p.actions.requested && <p><span className="dt-requested">Requested</span></p>}
      <div className="dt-actions">{buttons}</div>
    </section>
    {e.hasFile
      ? <div className="dt-still" style={toneStyle(`${p.show.title}${e.number}`)} aria-hidden="true">
        <span className="dt-ep__art" style={{ position: 'absolute', inset: 0, width: 'auto', height: 'auto', borderRadius: 0 }}>
          <Picture src={media.imageUrl(e.stillPath, 'w780')}/>
          {progress !== undefined && <Progress value={progress}/>}
        </span>
      </div>
      : <div className="dt-still dt-still--off" aria-hidden="true"><span>{p.missing?.thumb ?? (e.aired ? 'Not in library' : 'Not aired yet')}</span></div>}
  </>
}

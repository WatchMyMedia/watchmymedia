import { memo, type CSSProperties } from 'react'
import type { components } from '../../../src/api/schema'
import { rowWindow } from '../../app/focusRules'
import { useMediaUrls } from '../../app/hooks'
import { Icon } from '../../ui/Icon'
import { CARDS_IN_VIEW } from '../../ui/tones'
import { Picture } from './DetailParts'
import { toneStyle } from './detailWords'
import { cardLine, episodeProgress, missingOf, seasonName } from './showLabels'
import { cardTitle } from './titleWords'
import { elsewhereLine, type MergedEpisode } from './showCopies'

type RequestOptions = components['schemas']['RequestOptions']

/**
 * The season's episodes as the kit's wide cards: the one on top marked by
 * a gold bar above it, never a ring, so only the focused card is ringed.
 * Focus on a card puts its episode on top, and the row follows it as the
 * kit's rows do (the card before it stays in view, only the cards near the
 * view are drawn, the next one peeks); OK moves focus back up to its Play
 * and never plays. Cards are `ep/<id>`.
 */
export function EpisodeRow({ show, season, episodes, onTopId, options, today, onPick, onOk }: {
  show: string
  season: number
  /** The season's episodes; one only another server has carries `on`, and says so. */
  episodes: MergedEpisode[]
  onTopId?: number
  options?: RequestOptions
  today: Date
  onPick(id: number): void
  onOk(): void
}) {
  const index = Math.max(0, episodes.findIndex((e) => e.id === onTopId))
  const { start, from, to } = rowWindow(index, episodes.length, CARDS_IN_VIEW.episode)
  const step = '(var(--wide-w) + var(--card-gap))'
  return <section className="tv-row dt-eps" aria-label={`${seasonName(season)} episodes`}>
    <div className="tv-row__viewport" data-clip>
      <div className="tv-row__track" data-row style={{ transform: `translateX(calc(${-start} * ${step}))` } as CSSProperties}>
        {from > 0 && <span className="tv-row__spacer" style={{ width: `calc(${from} * ${step} - var(--card-gap))` }}/>}
        {episodes.slice(from, to).map((e) => <EpisodeCard key={e.id} e={e} show={show} season={season} onTop={e.id === onTopId}
          options={options} today={today} onPick={onPick} onOk={onOk}/>)}
      </div>
    </div>
  </section>
}

// Moving along the row redraws only the two cards whose On top changes.
const EpisodeCard = memo(function EpisodeCard({ e, show, season, onTop, options, today, onPick, onOk }: {
  e: MergedEpisode; show: string; season: number; onTop: boolean; options?: RequestOptions; today: Date; onPick(id: number): void; onOk(): void
}) {
  const media = useMediaUrls()
  const missing = missingOf(e, options)
  const elsewhere = elsewhereLine(e)
  const line = elsewhere ? { text: elsewhere, tone: undefined, dot: false } : cardLine(e, missing, today)
  const progress = e.hasFile ? episodeProgress(e) : missing?.progress
  const watched = e.hasFile && !!e.watch?.completed
  const label = [`${seasonName(season)}, episode ${e.number}: ${e.title}`, line.text, onTop ? 'on top' : ''].filter(Boolean).join(', ')
  return <button type="button" data-focus={`ep/${e.id}`} className={`tv-card tv-card--episode${onTop ? ' tv-card--selected' : ''}`} aria-label={label}
    aria-current={onTop || undefined} onFocus={() => onPick(e.id)} onClick={onOk}>
    <span className={`tv-card__art${e.hasFile ? '' : ' dt-ep__art--off'}`} style={e.hasFile ? toneStyle(`${show}${e.number}`) as CSSProperties : undefined}>
      {e.hasFile ? <><span className="tv-card__placeholder" aria-hidden="true"/><Picture className="tv-card__img" src={media.imageUrl(e.stillPath, 'w780')}/></>
        : <span className="dt-ep__thumb">{missing?.thumb ?? (e.aired ? 'Not in library' : line.text)}</span>}
      {watched && <span className="tv-card__done" aria-hidden="true"><Icon name="check"/></span>}
      {progress !== undefined && <span className="tv-card__progress" aria-hidden="true"><span style={{ width: `${Math.round(Math.min(1, progress) * 100)}%` }}/></span>}
    </span>
    <span className={`tv-card__title${e.hasFile ? '' : ' dt-off'}`}>{cardTitle(e)}</span>
    <span className={`tv-card__sub${line.tone && !e.hasFile ? ` dt-tone--${line.tone}` : ''}`}>{line.text}</span>
  </button>
})

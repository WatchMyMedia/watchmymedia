import { memo, type CSSProperties } from 'react'
import type { components } from '../../../src/api/schema'
import { rowWindow } from '../../app/focusRules'
import { useMediaUrls } from '../../app/hooks'
import { CARDS_IN_VIEW } from '../../ui/tones'
import { Picture, Progress } from './DetailParts'
import { toneStyle } from './detailWords'
import { Glyph } from './Glyph'
import { cardCode, cardLine, episodeProgress, missingOf, seasonName } from './showLabels'

type Episode = components['schemas']['Episode']
type RequestOptions = components['schemas']['RequestOptions']

/**
 * The season's episodes (ShowPage's row): the one on top ringed in gold
 * with On top. Focus on a card puts its episode on top, and the row follows
 * it as the kit's rows do (the card before it stays in view, only the cards
 * near the view are drawn); OK moves focus back up to its Play and never
 * plays. Cards are `ep/<id>`.
 */
export function EpisodeRow({ show, season, episodes, onTopId, options, today, onPick, onOk }: {
  show: string
  season: number
  episodes: Episode[]
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
  e: Episode; show: string; season: number; onTop: boolean; options?: RequestOptions; today: Date; onPick(id: number): void; onOk(): void
}) {
  const media = useMediaUrls()
  const missing = missingOf(e, options)
  const line = cardLine(e, missing, today)
  const progress = e.hasFile ? episodeProgress(e) : missing?.progress
  const label = [`${seasonName(season)}, episode ${e.number}: ${e.title}`, line.text, onTop ? 'on top' : ''].filter(Boolean).join(', ')
  return <button type="button" data-focus={`ep/${e.id}`} className={`dt-ep${onTop ? ' dt-ep--top' : ''}`} aria-label={label}
    onFocus={() => onPick(e.id)} onClick={onOk}>
    <span className={`dt-ep__art${e.hasFile ? '' : ' dt-ep__art--off'}`} style={e.hasFile ? toneStyle(`${show}${e.number}`) : undefined}>
      {e.hasFile ? <Picture src={media.imageUrl(e.stillPath, 'w780')}/> : <span className="dt-ep__thumb">{missing?.thumb ?? (e.aired ? 'Missing' : 'Not aired yet')}</span>}
      {e.hasFile && e.watch?.completed && <span className="dt-check" role="img" aria-label="Watched"><Glyph name="check"/></span>}
      {onTop && <span className="tv-badge tv-badge--gold dt-ep__top">On top</span>}
      <span className="tv-badge dt-ep__code">{cardCode(e)}</span>
      {progress !== undefined && <Progress value={progress}/>}
    </span>
    <span className={`dt-ep__title${e.hasFile ? '' : ' dt-ep__title--off'}`}>{e.title || `Episode ${e.number}`}</span>
    <span className={`dt-ep__line${line.tone && !e.hasFile ? ` dt-tone--${line.tone}` : ''}`}>{line.dot && <i/>}{line.text}</span>
  </button>
})

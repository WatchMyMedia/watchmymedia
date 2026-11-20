import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { seasonQuery, showQuery } from '../../../src/api/queries'
import type { components } from '../../../src/api/schema'
import { useMediaUrls } from '../../app/hooks'
import type { KeyInput } from '../../app/keys'
import type { FilePlayback, FilePlaybackState } from '../../player'
import { Button } from '../../ui/Button'
import { problemWords } from '../../ui/problemWords'
import { episodeProgress, episodeRowLine, playableEpisodes, playingEpisode, seasonTabList, type EpisodeContext } from './episodeList'
import { episodesOkHint } from './labels'
import { PlayerPanel } from './Pickers'
import { PlayerIcon } from './PlayerIcons'
import { useSelect } from './select'

type Episode = components['schemas']['Episode']

// Episodes: the season tabs (focusing one shows it at once) and the
// season's episodes with their stills. OK plays one as a new play; OK on
// the one playing goes back to the video where it is.

const shareOf = (s: FilePlaybackState) => s.durationMs > 0 ? Math.round((s.positionMs / s.durationMs) * 200) / 200 : 0
const minutesOf = (s: FilePlaybackState) => Math.floor(s.positionMs / 60_000)

export function EpisodesPanel({ playback, context, fileId, onPlay, onClose, onKey }: {
  playback: FilePlayback; context: EpisodeContext; fileId: number; onPlay(episode: Episode, showTitle: string, season: number): void; onClose(): void; onKey(input: KeyInput): boolean
}) {
  const [season, setSeason] = useState(context.season)
  const [onTab, setOnTab] = useState(false)
  const [focused, setFocused] = useState<number>()
  const show = useQuery(showQuery(context.showId))
  const episodes = useQuery(seasonQuery(context.showId, season))
  const list = playableEpisodes(episodes.data?.episodes)
  const playing = playingEpisode(list, fileId)
  const tabs = seasonTabList(show.data?.seasons, context.season)
  const title = show.data?.title ?? ''
  const hint = episodesOkHint(onTab, !!playing && focused === playing.id)
  return <PlayerPanel title="Episodes" wide onClose={onClose} onKey={onKey} okHint={hint}
    aside={<>
      {title && <p className="pl-episodes__show">{title}</p>}
      {tabs.length > 1 && <div className="pl-episodes__tabs" role="tablist" aria-label="Seasons" data-row data-group="seasons">
        {tabs.map((t) => <button key={t.number} type="button" role="tab" aria-selected={t.number === season} data-focus={`season/${t.number}`}
          className={`pl-tab${t.number === season ? ' pl-tab--on' : ''}`} onFocus={() => { setOnTab(true); setSeason(t.number) }} onClick={() => setSeason(t.number)}>{t.name}</button>)}
      </div>}
    </>}>
    {episodes.isPending && <p className="pl-panel__credit">Loading…</p>}
    {episodes.isError && <div className="pl-episodes__problem">
      <p className="pl-panel__credit">{problemWords(episodes.error).title}</p>
      <Button id="episodes/retry" variant="primary" onPress={() => void episodes.refetch()}>Try again</Button>
    </div>}
    {episodes.data && list.length === 0 && <p className="pl-panel__credit">Nothing of this season is in the library.</p>}
    {list.map((e, i) => <EpisodeRow key={e.id} episode={e} playing={e.id === playing?.id} autoFocus={playing ? e.id === playing.id : i === 0} playback={playback}
      onFocus={() => { setOnTab(false); setFocused(e.id) }} onPress={() => e.id === playing?.id ? onClose() : onPlay(e, title, season)}/>)}
  </PlayerPanel>
}

function EpisodeRow({ episode, playing, autoFocus, playback, onFocus, onPress }: {
  episode: Episode; playing: boolean; autoFocus: boolean; playback: FilePlayback; onFocus(): void; onPress(): void
}) {
  const media = useMediaUrls()
  const still = episode.stillPath ? media.imageUrl(episode.stillPath, 'w300') : undefined
  const watched = !!episode.watch?.completed && !playing
  const line = playing ? <PlayingLine episode={episode} playback={playback}/> : episodeRowLine(episode, false, 0)
  return <button type="button" className="pl-ep" data-focus={`episode/${episode.id}`} data-autofocus={autoFocus || undefined}
    aria-label={`Episode ${episode.number}, ${episode.title}`} onFocus={onFocus} onClick={onPress}>
    <span className="pl-ep__still">
      {still && <img src={still} alt="" decoding="async" loading="lazy"/>}
      {watched && <span className="pl-ep__check"><PlayerIcon name="check"/></span>}
      {playing ? <PlayingProgress playback={playback}/> : <Progress share={episodeProgress(episode, undefined)}/>}
    </span>
    <span className="pl-ep__text">
      <span className="pl-ep__head"><span className="pl-ep__title">{episode.number}. {episode.title}</span>{playing && <span className="pl-ep__now">Playing</span>}</span>
      <span className="pl-ep__line">{line}</span>
    </span>
  </button>
}

function Progress({ share }: { share: number | undefined }) {
  if (share === undefined) return null
  return <span className="pl-ep__progress"><span style={{ width: `${(share * 100).toFixed(1)}%` }}/></span>
}

function PlayingProgress({ playback }: { playback: FilePlayback }) {
  return <Progress share={useSelect(playback, shareOf, Object.is)}/>
}

function PlayingLine({ episode, playback }: { episode: Episode; playback: FilePlayback }) {
  return <>{episodeRowLine(episode, true, useSelect(playback, minutesOf, Object.is))}</>
}

import { useBack, useNav } from '../../app/hooks'
import type { ScreenProps } from '../../app/routes'
import { YouTubeTrailerScreen } from '../details/YouTubeTrailerScreen'
import { LivePlayerScreen } from '../live/LivePlayerScreen'
import { Placeholder } from '../Placeholder'

/**
 * The player route. Live channels go to the Live TV player and YouTube
 * trailers to the trailer screen, each drawn by its own feature; files,
 * recordings and trailer files play here, over the engine in player/.
 */
export function PlayerScreen({ route }: ScreenProps<'player'>) {
  const play = route.play
  if (play.kind === 'live') return <LivePlayerScreen play={play} />
  if (play.kind === 'trailer' && play.youtubeKey && play.fileId === undefined) return <YouTubeTrailerScreen play={play} />
  return <FilePlayerScreen route={route} />
}

/** Files, recordings and trailer files, until the player feature draws its controls. */
function FilePlayerScreen({ route }: ScreenProps<'player'>) {
  const nav = useNav()
  useBack(() => { nav.back(); return true })
  const play = route.play
  const what = play.kind === 'file' ? play.title ?? `File ${play.fileId}` : play.kind === 'recording' ? play.title ?? 'A recording'
    : play.kind === 'trailer' ? play.title ?? 'A trailer' : 'A channel'
  return <Placeholder full kicker={`Player · ${play.kind} · placeholder`} title={what}>
    The player engine (player/) plays this; its controls, pickers and Next episode come with the player feature.
  </Placeholder>
}

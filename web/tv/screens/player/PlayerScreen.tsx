import { useMemo } from 'react'
import { useNav, usePlayer, useSource } from '../../app/hooks'
import type { ScreenProps } from '../../app/routes'
import { platform } from '../../platform/current'
import { ProblemScreen } from '../../ui/States'
import { YouTubeTrailerScreen } from '../details/YouTubeTrailerScreen'
import { LivePlayerScreen } from '../live/LivePlayerScreen'
import { FilePlayer } from './FilePlayer'
import { fileRequest } from './playerSession'

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

/** Files, recordings and trailer files; where nothing can play (the TV build in a desktop browser), says so. */
function FilePlayerScreen({ route }: ScreenProps<'player'>) {
  const nav = useNav()
  const player = usePlayer()
  const request = useMemo(() => fileRequest(route.play), [route.play])
  const source = useSource(route.play.kind === 'file' ? route.play.server : undefined)
  if (source === 'gone') {
    return <ProblemScreen icon="alert" title="This copy’s server isn’t here any more" actions={[{ id: 'back', label: 'Back', onPress: () => nav.back() }]}>
      This TV is no longer signed in to the server it’s on.
    </ProblemScreen>
  }
  if (!player || !request) {
    return <ProblemScreen icon="alert" title={player ? 'This can’t play here' : 'Playing needs the TV'}
      actions={[{ id: 'back', label: 'Back', onPress: () => nav.back() }]}>
      {player ? 'There is no file to play for this.' : `This browser has no ${platform.words.player}. Open WatchMyMedia on the TV to watch.`}
    </ProblemScreen>
  }
  return <FilePlayer play={route.play} request={request} player={player} source={source}/>
}

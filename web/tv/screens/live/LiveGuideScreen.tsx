import type { ScreenProps } from '../../app/routes'
import { LiveSection } from './LiveSection'

/** The guide opened from elsewhere (Home's Live TV row, a reminder, a search result), at a channel or a time. */
export function LiveGuideScreen({ route }: ScreenProps<'liveGuide'>) {
  return <LiveSection initial="guide" guide={{ channelId: route.channelId, at: route.at }}/>
}

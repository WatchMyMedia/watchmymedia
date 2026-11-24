import { useSession } from '../../app/hooks'
import { watchlistsOn } from './watchlistTv'

/** Whether this server keeps watchlists, so the app offers them. */
export function useWatchlistsOn(): boolean {
  return watchlistsOn(useSession().server.apiLevel)
}

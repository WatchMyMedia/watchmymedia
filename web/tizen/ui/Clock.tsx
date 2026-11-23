import { timeOfDay } from '../../src/lib/liveGuide'
import { useNow } from '../screens/live/clock'

/** The time, top right inside the safe area, on the screens a viewer browses. */
export function Clock() {
  const now = useNow()
  return <p className="tv-clock" aria-hidden="true">{timeOfDay(now)}</p>
}

import { useContext } from 'react'
import { Dialog } from '../ui/Overlays'
import { SessionContext } from './hooks'

/**
 * Return at Home (or on the first start screen) asks before leaving, as
 * Samsung TV apps do (HomeStart's leave state): both buttons grey, focus on
 * Stay, which Return also chooses. Signed in, it says the TV stays signed in
 * to the server. Android TV leaves without asking.
 */
export function LeaveDialog({ onStay, onLeave }: { onStay(): void; onLeave(): void }) {
  const session = useContext(SessionContext)
  return <Dialog title="Leave WatchMyMedia?" onCancel={onStay} actions={[
    { id: 'leave/stay', label: 'Stay', safe: true, onPress: onStay },
    { id: 'leave/leave', label: 'Leave', onPress: onLeave },
  ]}>
    <p className="tv-body">{session
      ? `This TV stays signed in to ${session.server.name}, and Continue watching keeps your place.`
      : 'You can come back and pick a server any time.'}</p>
    <p className="tv-note" style={{ display: 'flex', alignItems: 'center', gap: '.75rem', order: 1 }}><span className="tv-kbd">Return</span>Stays here too</p>
  </Dialog>
}

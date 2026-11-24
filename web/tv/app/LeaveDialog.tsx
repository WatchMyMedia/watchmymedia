import { useContext } from 'react'
import { Dialog } from '../ui/Overlays'
import { SessionContext } from './hooks'

/**
 * Return at Home (or on the first start screen) asks before leaving, as
 * Samsung's certification asks: both buttons grey so the question pushes
 * neither, focus on Stay, which Return also chooses. Exit is left to the TV.
 * Android TV leaves without asking.
 */
export function LeaveDialog({ onStay, onLeave }: { onStay(): void; onLeave(): void }) {
  const session = useContext(SessionContext)
  return <Dialog title="Leave WatchMyMedia?" narrow onCancel={onStay} actions={[
    { id: 'leave/stay', label: 'Stay', safe: true, onPress: onStay },
    { id: 'leave/leave', label: 'Leave', onPress: onLeave },
  ]}>
    <p className="tv-lede tv-muted">{session
      ? `This TV stays signed in to ${session.server.name}, and Continue watching keeps your place.`
      : 'You can come back and pick a server any time.'}</p>
  </Dialog>
}

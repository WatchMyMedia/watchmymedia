import { Dialog } from '../ui/Overlays'

/**
 * Return at Home (or on the first start screen) asks before leaving, as
 * Samsung TV apps do; focus starts on Stay. Android TV leaves without asking.
 */
export function LeaveDialog({ onStay, onLeave }: { onStay(): void; onLeave(): void }) {
  return <Dialog title="Leave WatchMyMedia?" onCancel={onStay} actions={[
    { id: 'leave/stay', label: 'Stay', variant: 'primary', safe: true, onPress: onStay },
    { id: 'leave/leave', label: 'Leave', onPress: onLeave },
  ]}>
    <p className="tv-body">Your place in what you’re watching is saved, and this TV stays signed in.</p>
  </Dialog>
}

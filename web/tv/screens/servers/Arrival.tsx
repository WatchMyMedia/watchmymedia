import { useApp, useSession } from '../../app/hooks'
import { Dialog } from '../../ui/Overlays'
import { Icon } from '../../ui/Icon'
import { addedWords, nowOn } from './serverRows'
import './servers.css'

/** How long "Now on …" stays up. */
export const NOTE_MS = 4000

/** "Now on Cabin server", at the top right after a switch. It takes no focus and no keys, and the shell takes it down by itself. */
export function SwitchNote() {
  const session = useSession()
  return <aside className="servers-now" role="status" aria-live="polite">
    <span className="servers-now__icon" aria-hidden="true"><Icon name="server"/></span>
    <span className="servers-now__text">{nowOn(session.server.name)}</span>
  </aside>
}

/**
 * The last step of Add a server, over the new server's Home: it is added
 * and the app is on it, this TV is signed in to one more server and Search
 * looks in all of them, and switching back asks for no password.
 */
export function AddedDialog({ onDone }: { onDone(): void }) {
  const app = useApp()
  const session = useSession()
  const words = addedWords(session.server.name, session.account.name, app.store.list().filter((s) => s.account).length)
  return <Dialog title={words.title} onCancel={onDone} actions={[{ id: 'added/done', label: 'Done', variant: 'primary', safe: true, onPress: onDone }]}>
    <p className="tv-body">{words.lead}</p>
    <div><span className="servers-kicker">What changed</span><p className="tv-body">{words.changed}</p></div>
    <div><span className="servers-kicker">What happens next</span><p className="tv-body">{words.next}</p></div>
  </Dialog>
}

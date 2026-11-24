import { useState } from 'react'
import { useApp } from '../../app/hooks'
import type { StartState } from '../../session/session'
import { Brand } from '../../ui/Icon'
import { Dialog } from '../../ui/Overlays'
import { ProblemScreen } from '../../ui/States'
import { StartTop } from './ServerScreen'
import { DETAILS_LINE, problemDetails, startProblemWords } from './startWords'

type Problem = Extract<StartState, { screen: 'problem' }>

/** The address and what the TV heard back, for whoever looks after the server. Return closes it. */
function DetailsDialog({ state, onClose }: { state: Problem; onClose(): void }) {
  return <Dialog title="Details" onCancel={onClose} actions={[{ id: 'details/close', label: 'Close', safe: true, onPress: onClose }]}>
    <dl className="start-details">
      {problemDetails(state.problem, state.server, state.found).map((d) => <div key={d.label}><dt>{d.label}</dt><dd>{d.value}</dd></div>)}
    </dl>
  </Dialog>
}

/**
 * Why this TV can't go on with a server: it can't be reached, it isn't set
 * up yet, it is too old for this TV, it moved, or this TV was signed out
 * from another device. A title, why, what to do, and the first action
 * focused; the address and what the TV heard back are behind Details.
 */
export function StartProblemScreen({ state }: { state: Problem }) {
  const app = useApp()
  const [details, setDetails] = useState(false)
  const words = startProblemWords(state.problem, state.server)
  return <div className="tv-page start">
    <div className="tv-glow"/>
    <StartTop name={false}/>
    <ProblemScreen title={words.title} actions={[
      { id: 'retry', label: words.first, onPress: () => app.retry() },
      { id: 'servers', label: 'Choose another server', onPress: () => app.showServers() },
      ...(words.details ? [{ id: 'details', label: 'Details', onPress: () => setDetails(true) }] : []),
    ]} foot={words.details ? DETAILS_LINE : undefined}>
      <p>{words.why}</p>
      <p className="tv-muted">{words.what}</p>
    </ProblemScreen>
    {details && <DetailsDialog state={state} onClose={() => setDetails(false)}/>}
  </div>
}

/** While the last sign-in is checked on launch. */
export function BootScreen({ state }: { state: Extract<StartState, { screen: 'booting' }> }) {
  return <div className="tv-page start">
    <div className="tv-glow"/>
    <div className="tv-boot"><div className="tv-boot__inner">
      <Brand/>
      <p role="status" className="start-status"><span className="tv-spinner tv-spinner--big" aria-hidden="true"/>{state.server ? `Connecting to ${state.server.name}…` : 'Starting…'}</p>
    </div></div>
  </div>
}

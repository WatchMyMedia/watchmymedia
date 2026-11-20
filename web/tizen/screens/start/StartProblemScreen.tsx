import { useApp } from '../../app/hooks'
import { displayAddress } from '../../session/address'
import type { StartState } from '../../session/session'
import { KeyHints } from '../../ui/Button'
import { Brand } from '../../ui/Icon'
import { ProblemScreen } from '../../ui/States'
import { startProblemWords } from './startWords'

type Problem = Extract<StartState, { screen: 'problem' }>

/**
 * Why this TV can't go on with a server:
 * it can't be reached, it isn't set up yet, it is too old for this
 * TV, or this TV was signed out from another device.
 */
export function StartProblemScreen({ state }: { state: Problem }) {
  const app = useApp()
  const words = startProblemWords(state.problem, state.server)
  return <div className="tv-page" style={{ background: 'var(--ground-deep)' }}>
    <div className="start-brand"><Brand/></div>
    <ProblemScreen icon={words.icon} title={words.title} actions={[
      { id: 'retry', label: words.retry, onPress: () => app.retry() },
      { id: 'servers', label: 'Change server', onPress: () => app.showServers() },
    ]} extra={<>
      {words.address && <div className="start-problem__address"><span>{words.address.label}</span><b>{words.address.value}</b></div>}
      {words.note && <p className="tv-note">{words.note}</p>}
    </>}>{words.body}</ProblemScreen>
    <KeyHints hints={[{ key: '◀ ▶', label: 'Move' }, { key: 'OK', label: 'Choose' }, { key: 'Return', label: 'Change server' }]}
      right={`${state.server.name} · ${displayAddress(state.server.baseUrl)} · This device: Samsung TV`}/>
  </div>
}

/** While the last sign-in is checked on launch. */
export function BootScreen({ state }: { state: Extract<StartState, { screen: 'booting' }> }) {
  return <div className="tv-page">
    <div className="tv-glow"/>
    <div className="tv-boot"><div className="tv-boot__inner">
      <Brand/>
      <p role="status" className="start-status"><span className="tv-spinner" aria-hidden="true"/>{state.server ? `Connecting to ${state.server.name}…` : 'Starting…'}</p>
    </div></div>
  </div>
}

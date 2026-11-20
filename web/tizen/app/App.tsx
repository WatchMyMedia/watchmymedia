import { useSyncExternalStore } from 'react'
import { AppContext, InputContext, PlayerContext } from './hooks'
import type { Input } from './input'
import { Shell } from './Shell'
import type { AppSession } from '../session/session'
import type { Player } from '../player'
import { StartFlow } from '../screens/start/StartFlow'

/** The whole app: the start screens until a session is signed in, then that session's shell. */
export function App({ app, input, player, exit }: { app: AppSession; input: Input; player?: Player; exit(): void }) {
  const state = useSyncExternalStore(app.subscribe, app.snapshot)
  return <InputContext.Provider value={input}>
    <AppContext.Provider value={app}>
      <PlayerContext.Provider value={player}>
        {state.screen === 'signedIn' ? <Shell key={state.session.serial} session={state.session} exit={exit}/> : <StartFlow state={state} exit={exit}/>}
      </PlayerContext.Provider>
    </AppContext.Provider>
  </InputContext.Provider>
}

import { useEffect, useEffectEvent, useLayoutEffect, useMemo, useState } from 'react'
import { useApp, useInput } from '../../app/hooks'
import { emptyMemory } from '../../app/focusRules'
import { LeaveDialog } from '../../app/LeaveDialog'
import type { StartState } from '../../session/session'
import { ServerScreen } from './ServerScreen'
import { SignInScreen } from './SignInScreen'
import { BootScreen, StartProblemScreen } from './StartProblemScreen'

type Start = Exclude<StartState, { screen: 'signedIn' }>

/** Which start screen is showing, for putting focus on its first item as it changes. */
function view(state: Start) {
  if (state.screen === 'signIn') return `signIn/${state.mode}`
  if (state.screen === 'servers') return `servers/${state.busy ?? ''}/${state.servers.length > 0}`
  return state.screen
}

/**
 * The screens before a session: connecting, the server picker, signing in
 * and the problems. Return cancels what is under way and goes back to the
 * servers; on the servers it asks before leaving. Opened from the signed-in
 * app (Add a server, or the sign-in of a server signed out here), Return
 * walks back to the app instead, which is still on its server.
 */
export function StartFlow({ state, exit }: { state: Start; exit(): void }) {
  const app = useApp()
  const input = useInput()
  const [leaving, setLeaving] = useState(false)
  const memory = useMemo(() => emptyMemory(), [])
  const key = view(state)
  const failed = state.screen === 'servers' && !!state.error

  useLayoutEffect(() => {
    input.focus.setMemory(memory)
    // A typed address that failed keeps focus by the field; anything else starts on the screen's first item.
    input.focus.restore(failed ? ['connect', 'address'] : [], 'content', 4000, 0)
  }, [input, memory, key, failed])

  const onBack = useEffectEvent(() => {
    if (state.screen === 'servers' && !state.busy && !state.adding) setLeaving(true)
    else app.cancel()
    return true
  })
  useEffect(() => {
    input.setAppHandler((i) => i.action === 'back' && onBack())
    return () => input.setAppHandler(undefined)
  }, [input])

  return <main className="tv-shell" data-scope="content">
    {state.screen === 'booting' && <BootScreen state={state}/>}
    {state.screen === 'servers' && <ServerScreen state={state}/>}
    {state.screen === 'signIn' && <SignInScreen key={state.server.id} state={state}/>}
    {state.screen === 'problem' && <StartProblemScreen state={state}/>}
    {leaving && <LeaveDialog onStay={() => setLeaving(false)} onLeave={exit}/>}
  </main>
}

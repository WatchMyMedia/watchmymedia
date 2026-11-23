import { useEffect, useState } from 'react'
import { useMediaUrls } from '../../app/hooks'
import type { FilePlayback, FilePlaybackState, UpNext } from '../../player'
import { Button } from '../../ui/Button'
import { useNow } from '../live/clock'
import { creditsLine, nextKicker, nextLine, nextTitle, startingWords } from './labels'
import { PlayerIcon } from './PlayerIcons'
import { useSelect } from './select'

// What comes over the video: Up next, the starting view, Still watching,
// an administrator's stop or a failure, the spinner and the note that takes
// no focus. The prompts sit in the screen, so the screen's own Return and
// keys answer them (Return keeps watching, or leaves).

const countdownOf = (s: FilePlaybackState) => s.next?.seconds

/**
 * Up next, at the bottom right while the credits roll: when it starts,
 * the episode's picture, its name, its length and when it would end, with
 * Play now focused and Watch credits beside it. The line at the left says
 * what is rolling.
 */
export function NextCard({ playback, episode, title, line }: { playback: FilePlayback; episode: UpNext; title: string; line?: string }) {
  const media = useMediaUrls()
  const seconds = useSelect(playback, countdownOf)
  const now = useNow(60_000)
  const still = episode.stillPath ? media.imageUrl(episode.stillPath, 'w780') : undefined
  const length = nextLine(episode, now)
  return <>
    <div className="pl-shade pl-shade--bottom"/>
    <p className="pl-credits">{creditsLine(title, line)}</p>
    <aside className="pl-next" role="dialog" aria-label="Up next">
      <p className="tv-kicker">{nextKicker({ seconds })}</p>
      <div className="pl-next__body">
        <div className="pl-next__art">{still && <img className="pl-next__img" src={still} alt="" decoding="async"/>}</div>
        <div className="pl-next__text">
          <h2 className="pl-next__title">{nextTitle(episode)}</h2>
          {length && <p className="pl-next__line">{length}</p>}
        </div>
      </div>
      <div className="tv-actions pl-next__actions">
        <Button id="next/play" variant="primary" icon="play" autoFocus onPress={() => playback.playNext()}>Play now</Button>
        <Button id="next/credits" onPress={() => playback.watchCredits()}>Watch credits</Button>
      </div>
    </aside>
  </>
}

export const PROMPT_FOCUS = 'prompt/first'

type PromptAction = { id: string; label: string; primary?: boolean; onPress(): void }

/** A question over the dimmed video: a title, why, what to do, its buttons with the first focused. */
function Prompt({ title, why, what, actions }: { title: string; why: string; what?: string; actions: PromptAction[] }) {
  return <div className="pl-prompt-host">
    <section role="alertdialog" aria-label={title} className="tv-dialog pl-prompt">
      <h2 className="tv-dialog__title">{title}</h2>
      <p className="tv-lede">{why}</p>
      {what && <p className="tv-note">{what}</p>}
      <div className="tv-dialog__actions">
        {actions.map((a, i) => <Button key={a.id} id={a.id} variant={a.primary ? 'primary' : 'ghost'} autoFocus={i === 0} onPress={a.onPress}>{a.label}</Button>)}
      </div>
    </section>
  </div>
}

/** Asked before the next episode starts by itself after a long while with no press. Focus stays on Keep watching. */
export function StillWatching({ title, onKeep, onStop }: { title?: string; onKeep(): void; onStop(): void }) {
  return <Prompt title={title ? `Still watching ${title}?` : 'Still watching?'} why="It has been a while since anyone pressed the remote."
    what="The next episode waits until you answer, so the server doesn’t stream to an empty room."
    actions={[{ id: PROMPT_FOCUS, label: 'Keep watching', primary: true, onPress: onKeep }, { id: 'prompt/stop', label: 'Stop', onPress: onStop }]}/>
}

/** An administrator stopped the play, with their words: OK leaves. */
export function AdminStop({ message, onOk }: { message: string; onOk(): void }) {
  return <Prompt title="Playback was stopped" why={message} what="Your place is kept." actions={[{ id: PROMPT_FOCUS, label: 'OK', primary: true, onPress: onOk }]}/>
}

/**
 * The play failed: why, in the engine's words, and what to do. Another
 * version of the title comes first when there is one; then Try again (from
 * where it stopped) and going back.
 */
export function PlayError({ message, other, backLabel, onOther, onRetry, onBack }: {
  message: string; other?: string; backLabel: string; onOther(): void; onRetry(): void; onBack(): void
}) {
  const actions: PromptAction[] = [
    ...(other ? [{ id: PROMPT_FOCUS, label: `Play the ${other} version`, primary: true, onPress: onOther }] : []),
    { id: other ? 'prompt/retry' : PROMPT_FOCUS, label: 'Try again', primary: !other, onPress: onRetry },
    { id: 'prompt/back', label: backLabel, onPress: onBack },
  ]
  return <Prompt title="This can’t play right now" why={message}
    what="What went wrong is kept in Settings, under About, for whoever looks after the server." actions={actions}/>
}

/** Starting: where it will start and what is left, with Cancel focused. */
export function Starting({ startMs, durationMs, recording, onCancel }: { startMs?: number; durationMs: number; recording?: boolean; onCancel(): void }) {
  const words = recording ? { title: 'The recording is starting', line: undefined } : startingWords(startMs, durationMs)
  return <div className="pl-start" role="status">
    <span className="tv-spinner tv-spinner--big pl-start__spin" aria-hidden="true"/>
    <h2 className="tv-title">{words.title}</h2>
    {words.line && <p className="tv-lede tv-muted">{words.line}</p>}
    <Button id="starting/cancel" autoFocus onPress={onCancel}>Cancel</Button>
  </div>
}

/** How long buffering goes on before it says why: a quick catch-up after a seek passes with the spinner alone. */
const SLOW_AFTER_MS = 2500

/** Buffering: a spinner in the middle, and after a moment what is happening. */
export function Spinner({ automatic }: { automatic: boolean }) {
  const [slow, setSlow] = useState(false)
  useEffect(() => { const t = setTimeout(() => setSlow(true), SLOW_AFTER_MS); return () => clearTimeout(t) }, [])
  return <div className="pl-wait" role="status" aria-label="Loading">
    <span className="tv-spinner tv-spinner--big pl-wait__spin" aria-hidden="true"/>
    {slow && <>
      <p className="tv-head">Catching up…</p>
      {automatic && <p className="tv-lede tv-muted">Quality is on Automatic, so the picture may get softer for a while.</p>}
    </>}
  </div>
}

/** A passing line over the video that takes no focus: Automatic's step, a slow connection, a subtitle that failed. */
export function Note({ text, live }: { text: string; live?: boolean }) {
  return <div className="pl-note-host" role="status" aria-live="polite">
    <span className="pl-note">{live ? <span className="pl-dot pl-dot--live"/> : <PlayerIcon name="signal"/>}{text}</span>
  </div>
}

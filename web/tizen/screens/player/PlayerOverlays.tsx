import { useMediaUrls } from '../../app/hooks'
import type { FilePlayback, UpNext } from '../../player'
import { Button } from '../../ui/Button'
import { nextMeta, runtimeWords } from './labels'
import { PlayerIcon } from './PlayerIcons'
import { MiniProgress, NextButton } from './Ticking'

// What comes over the video: the Next episode card (PlayerNext), Still
// watching, an administrator's stop or an error, the spinner and the note
// that takes no focus. The prompts sit in the screen, so the screen's own
// Return and keys answer them (Return keeps watching, or leaves).

/** PlayerNext's card: the next episode's still, its title, the countdown button and Watch credits. */
export function NextCard({ playback, episode, heading }: { playback: FilePlayback; episode: UpNext; heading: string }) {
  const media = useMediaUrls()
  const still = episode.stillPath ? media.imageUrl(episode.stillPath, 'w780') : undefined
  const runtime = runtimeWords(episode.runtimeMin)
  return <>
    <div className="pl-shade pl-shade--card"/>
    <MiniProgress playback={playback}/>
    <section className="pl-next" role="dialog" aria-label="Next episode">
      <div className="pl-next__art">
        {still && <img className="pl-next__img" src={still} alt="" decoding="async"/>}
        <span className="pl-next__badge">Next episode</span>
        {runtime && <span className="pl-next__badge pl-next__badge--end">{runtime}</span>}
      </div>
      <div className="pl-next__body">
        <span className="pl-next__title">{heading}</span>
        <span className="pl-next__meta">{nextMeta(episode)}</span>
        <div className="pl-next__actions">
          <NextButton playback={playback}/>
          <button type="button" className="pl-next__btn pl-next__btn--ghost" data-focus="next/credits" onClick={() => playback.watchCredits()}>Watch credits</button>
        </div>
      </div>
    </section>
  </>
}

/** A question over the dimmed video, with its buttons; focus starts on the first. */
function Prompt({ kicker, title, children, foot, actions }: {
  kicker?: string; title: string; children: string; foot?: string; actions: { id: string; label: string; primary?: boolean; play?: boolean; onPress(): void }[]
}) {
  return <div className="pl-prompt-host">
    <section role="alertdialog" aria-label={title} className="pl-prompt">
      {kicker && <span className="pl-prompt__kicker">{kicker}</span>}
      <h2 className="pl-prompt__title">{title}</h2>
      <p className="pl-prompt__body">{children}</p>
      <div className="pl-prompt__actions">
        {actions.map((a, i) => <Button key={a.id} id={a.id} variant={a.primary ? 'primary' : 'ghost'} icon={a.play ? 'play' : undefined} autoFocus={i === 0} onPress={a.onPress}>{a.label}</Button>)}
      </div>
      {foot && <p className="pl-prompt__foot">{foot}</p>}
    </section>
  </div>
}

export const PROMPT_FOCUS = 'prompt/first'

/** Asked before the next episode starts by itself after a long while with no press (PlayerNext's Still watching). */
export function StillWatching({ episode, heading, onKeep, onStop }: { episode?: UpNext; heading?: string; onKeep(): void; onStop(): void }) {
  const body = heading ? `${heading} is up next. ` : ''
  return <Prompt kicker={episode?.showTitle} title="Still watching?" foot="Any press on the remote counts as an answer."
    actions={[{ id: PROMPT_FOCUS, label: 'Keep watching', primary: true, play: true, onPress: onKeep }, { id: 'prompt/stop', label: 'Stop', onPress: onStop }]}>
    {`${body}Nothing plays until you answer, so the server doesn’t keep streaming to an empty room.`}
  </Prompt>
}

/** An administrator stopped the play, with their words: OK leaves. */
export function AdminStop({ message, onOk }: { message: string; onOk(): void }) {
  return <Prompt title="Playback stopped" actions={[{ id: PROMPT_FOCUS, label: 'OK', primary: true, onPress: onOk }]}>{message}</Prompt>
}

/** The play failed: the engine's words, Try again (from where it stopped) and Back. */
export function PlayError({ message, backLabel, onRetry, onBack }: { message: string; backLabel: string; onRetry(): void; onBack(): void }) {
  return <Prompt title="Can’t play this" actions={[{ id: PROMPT_FOCUS, label: 'Try again', primary: true, onPress: onRetry }, { id: 'prompt/back', label: backLabel, onPress: onBack }]}>{message}</Prompt>
}

/** Loading and buffering: a gold arc turning in the middle; a recording just starting says so. */
export function Spinner({ starting }: { starting?: boolean }) {
  return <div className="pl-wait" role="status" aria-label={starting ? 'The recording is starting' : 'Loading'}>
    <span className="pl-spinner" aria-hidden="true"/>
    {starting && <span className="pl-wait__line">The recording is starting…</span>}
  </div>
}

/** A passing line over the video that takes no focus: Automatic's step, a slow connection, a subtitle that failed. */
export function Note({ text, live }: { text: string; live?: boolean }) {
  return <div className="pl-note-host" role="status" aria-live="polite">
    <span className="pl-note">{live ? <span className="pl-dot pl-dot--live"/> : <PlayerIcon name="signal"/>}{text}</span>
  </div>
}

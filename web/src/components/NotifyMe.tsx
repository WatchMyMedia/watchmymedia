import { useEffect } from 'react'
import { Link } from '@tanstack/react-router'
import { bellWords, followFailLine, type FollowFailure } from '../lib/newEpisodes'
import { BellIcon, primaryAction, secondaryAction } from './liveTV/LiveReminders'

// Notify me on a show's page. The state and the press are
// useNotifyMe's (newEpisodeHooks.ts).

const bellBase = 'inline-flex min-h-[44px] items-center justify-center gap-2 whitespace-nowrap rounded-md px-[18px] text-sm'

/**
 * Off: a secondary button with the reminders' outline bell. On: the filled
 * bell in the accent colour with the accent outline, as Reminder set is. A
 * plain button whose name says what pressing does, not aria-pressed.
 */
export function NotifyMeButton({ show, following, onPress, look }: { show: string; following: boolean; onPress: () => void; look?: 'clear' }) {
  const words = bellWords(following, show)
  // clear sits on the show's picture, beside its name.
  const shape = look === 'clear'
    ? `btn btn-clear ${following ? 'border-accent/55 font-semibold text-accent' : ''}`
    : following ? `${bellBase} border border-accent/55 bg-transparent font-semibold text-accent hover:bg-accent/5` : `${bellBase} border border-line-2 bg-surface font-medium hover:bg-surface-2`
  return (
    <button
      type="button"
      onClick={onPress}
      aria-label={words.name}
      className={shape}
    >
      <BellIcon filled={following} />
      {words.label}
    </button>
  )
}

/** Why the last change was refused, under the actions until the next press. */
export function NotifyMeFailure({ failure, show }: { failure: FollowFailure; show: string }) {
  return (
    <p role="alert" className="text-[13.5px] text-warn">
      {failure === 'limit' ? (
        <>
          You follow 500 shows, the most you can. Choose Stop notifying for one in{' '}
          <Link to="/settings/notifications" className="underline">Settings › Notifications</Link> first.
        </>
      ) : (
        followFailLine(failure, show)
      )}
    </p>
  )
}

/**
 * The first Notify me in a browser that can still ask, with the follow
 * already made: Continue asks the browser; Not now, Escape and the scrim
 * keep the follow and don't ask. The reminders' explainer frame, with
 * these words.
 */
export function FollowExplainer({ show, onAnswer }: { show: string; onAnswer: (allow: boolean) => void }) {
  useEffect(() => {
    const key = (e: KeyboardEvent) => { if (e.key === 'Escape') onAnswer(false) }
    window.addEventListener('keydown', key)
    return () => window.removeEventListener('keydown', key)
  }, [onAnswer])
  return <div className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-black/65 p-4 pt-24 sm:pt-36" onClick={(e) => { if (e.target === e.currentTarget) onAnswer(false) }}>
    <section role="dialog" aria-modal="true" aria-labelledby="follow-head" className="flex w-full max-w-[34rem] flex-col gap-3.5 rounded-2xl border border-line-2 bg-surface-2 p-5 shadow-2xl sm:p-6">
      <div className="flex items-start gap-3.5">
        <span aria-hidden="true" className="hidden h-11 w-11 shrink-0 place-items-center rounded-full bg-accent/10 text-accent sm:grid"><BellIcon size={20} /></span>
        <div className="min-w-0 flex-1 space-y-2">
          <h2 id="follow-head" className="text-xl font-semibold leading-snug">Get notified of new episodes</h2>
          <p className="text-[15px] leading-relaxed text-neutral-200">When a new episode of {show} reaches the library, this browser shows a notification.</p>
          <p className="text-[13px] leading-relaxed text-neutral-400">Only while WatchMyMedia is open in a tab.</p>
          <p className="text-[13px] leading-relaxed text-neutral-400">Not now still follows {show}. Its new episodes show on Home, and your other devices still notify you.</p>
        </div>
      </div>
      <div className="mt-1 flex flex-wrap justify-end gap-2">
        <button type="button" className={secondaryAction} onClick={() => onAnswer(false)}>Not now</button>
        <button type="button" autoFocus className={primaryAction} onClick={() => onAnswer(true)}>Continue</button>
      </div>
    </section>
  </div>
}

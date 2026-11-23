import type { LivePlaybackState } from '../../player'

// Why a channel can't play, in the design's words and with its actions.
// The reason codes are the server's; the words
// are ours or the shared ones (src/lib/liveTV.ts liveFailure, which the
// engine already put in `failure`), never the server's own sentences.

export type ProblemIcon = 'tuner' | 'server' | 'antennaOff' | 'lock' | 'offline' | 'gauge' | 'noNet' | 'stop' | 'power' | 'record' | 'clock' | 'pause'

export type ProblemAction = 'retry' | 'tuneAgain' | 'guide' | 'another' | 'lower' | 'home' | 'watchRecording' | 'keepWatching' | 'stop'

export type LiveProblem = {
  icon: ProblemIcon
  title: string
  body: string
  /** A line for admins only: where in the web app to look. */
  adminNote?: string
  /** The buttons, the first focused. */
  actions: { id: ProblemAction; label: string }[]
  /** Show other channels on now under it (no signal, protected). */
  others: boolean
  /** Shown as a dialog over the picture rather than a page: the tuner was given back, or every tuner is busy. */
  dialog: boolean
}

type Failure = NonNullable<LivePlaybackState['failure']>

const LABEL: Record<ProblemAction, string> = {
  retry: 'Try again', tuneAgain: 'Tune again', guide: 'Back to the guide', another: 'Another channel', lower: 'Play at a lower quality',
  home: 'Go to Home', watchRecording: 'Watch from the start', keepWatching: 'Keep watching', stop: 'Stop',
}
const act = (...ids: ProblemAction[]) => ids.map((id) => ({ id, label: LABEL[id] }))

/** The next quality down for "Play at 720p", or none at the lowest. */
export function lowerQuality(quality: string): '720' | '480' | '360' | undefined {
  return quality === 'auto' || quality === '1080' ? '720' : quality === '720' ? '480' : quality === '480' ? '360' : undefined
}

/**
 * The problem for a failure: by its reason code, with the channel's number
 * where the board names it. `pauseMinutes` is how long a pause keeps the
 * tuner (the server's pauseSeconds).
 */
export function liveProblem(failure: Failure, o: { number?: string; admin: boolean; quality: string; pauseMinutes?: number }): LiveProblem {
  const num = o.number ?? 'This channel'
  const base = { others: false, dialog: false }
  const lower = lowerQuality(o.quality)
  switch (failure.reason) {
    case 'tuners_busy': return {
      ...base, dialog: true, icon: 'tuner', title: 'All tuners are in use',
      body: o.admin ? 'Every tuner is busy. WatchMyMedia never takes one from someone already watching.' : 'Every tuner is busy. Try again when someone stops watching.',
      adminNote: o.admin ? 'You can stop someone’s viewing from Activity in the web app.' : undefined, actions: act('retry', 'guide'),
    }
    case 'server_busy': return {
      ...base, icon: 'server', title: 'The server is busy', body: 'All of its playback sessions are in use by movies, shows and Live TV. Try again when one ends.',
      adminNote: o.admin ? 'The limit is in the web app, under Settings › Transcoding.' : undefined, actions: act('retry', 'guide'),
    }
    case 'no_signal': return { ...base, icon: 'antennaOff', title: `No signal on ${num}`, body: 'The tuner couldn’t pick up this channel. Reception changes with the weather and the antenna’s position.', actions: act('retry', 'another'), others: true }
    case 'signal_lost': return { ...base, icon: 'antennaOff', title: `${num} stopped coming in`, body: 'The broadcast cut out, often from weak reception. Its tuner has been freed.', actions: act('tuneAgain', 'another'), others: true }
    case 'stream_stalled': case 'encoder_failed': return {
      ...base, icon: 'gauge', title: o.number ? `The server can’t keep up with ${o.number}` : 'The server can’t keep up with this channel',
      body: lower ? 'Converting this channel ran slower than live. A lower quality may play.' : 'Converting this channel ran slower than live. Try again in a moment.',
      actions: lower ? [{ id: 'lower', label: `Play at ${lower}p` }, ...act('guide')] : act('retry', 'guide'),
    }
    case 'protected_channel': return {
      ...base, icon: 'lock', title: `${num} can’t be played here`, body: 'The channel is copy-protected, and WatchMyMedia can’t play protected broadcasts.',
      adminNote: o.admin ? 'Hide it from everyone in the web app, under Settings › Live TV.' : undefined, actions: act('another'), others: true,
    }
    case 'tuner_unavailable': case 'device_changed': return {
      ...base, icon: 'offline', title: 'The tuner isn’t answering',
      body: o.admin ? 'This server couldn’t reach the antenna tuner. Check that it’s switched on and still at its address.' : 'This server couldn’t reach the antenna tuner. Live TV comes back when it does.',
      actions: act('retry', 'guide'),
    }
    case 'lease_expired': return { ...base, icon: 'noNet', title: 'This TV lost its connection', body: 'Live TV stopped, and the server gives the tuner back within a minute. Tune again when the TV is back online.', actions: act('tuneAgain', 'guide') }
    case 'stopped_by_admin': return { ...base, icon: 'stop', title: 'An admin stopped this', body: 'An admin stopped your Live TV and gave its tuner back.', actions: act('guide') }
    case 'access_changed': case 'access_revoked': case 'source_changed': case 'source_removed': case 'channel_disabled': return {
      ...base, icon: 'power', title: failure.title, body: 'An admin changed Live TV on this server, so the channel stopped. Go back to Home to see what is available.', actions: act('home'),
    }
    case 'not_allowed': return { ...base, icon: 'power', title: failure.title, body: failure.detail, actions: act('home') }
    case 'recording': return {
      ...base, icon: 'record', title: 'Stopped for a recording',
      body: failure.recordingId ? `This tuner is now recording ${failure.recordingTitle ?? 'for your household'}. You can watch it from the start.` : 'This tuner is now recording for your household.',
      actions: failure.recordingId ? act('watchRecording', 'another') : act('another', 'guide'),
    }
    case 'pause_expired': return {
      ...base, dialog: true, icon: 'pause', title: 'The tuner was given back',
      body: `${num} was paused for ${o.pauseMinutes ?? 5} ${o.pauseMinutes === 1 ? 'minute' : 'minutes'}, so its tuner was freed for someone else. Tune again to watch it live; a tuner may not be free.`,
      actions: act('tuneAgain', 'guide'),
    }
    case 'inactive': return {
      ...base, dialog: true, icon: 'clock', title: 'Live TV stopped', body: 'Nothing was pressed for 4 hours, so the tuner was given back. Tune again to keep watching.', actions: act('tuneAgain', 'guide'),
    }
    default: return { ...base, icon: 'tuner', title: failure.title, body: failure.detail, actions: act('retry', 'guide') }
  }
}

/** Still watching?: the words the prompt shows while the minute runs. */
export function stillWatchingWords(secondsLeft: number): string {
  const s = Math.max(0, Math.ceil(secondsLeft))
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}

/**
 * How Settings › About's error log files a Live TV failure (app/playbackErrors.ts):
 * its kind, or undefined for a stop that isn't a failure (a pause or four
 * quiet hours gave the tuner back, a recording took it, an admin stopped it).
 */
export function loggedKind(reason: string | undefined): 'network' | 'auth' | 'format' | 'other' | undefined {
  switch (reason) {
    case 'pause_expired': case 'inactive': case 'recording': case 'stopped_by_admin': return undefined
    case 'lease_expired': case 'tuner_unavailable': case 'device_changed': case 'signal_lost': case 'no_signal': case 'stream_stalled': return 'network'
    case 'encoder_failed': case 'protected_channel': return 'format'
    case 'not_allowed': case 'access_changed': case 'access_revoked': return 'auth'
    default: return 'other'
  }
}

import type { StateMessage } from '../src/cast/protocol'
import type { LiveProgramme } from '../src/api/liveTV'
import { duration, episodeLine, progress, timeOfDay, timeRange } from '../src/lib/liveGuide'
import { SKIP_LABELS } from '../src/lib/play/rules'
import type { ReceiverFailure } from './live'

/**
 * The receiver's own screens over the video (PLAN-CAST.md §4.4). The Cast
 * player element draws the paused and seeking overlay; these are the
 * idle screen, loading, the Skip prompt, Up Next, Still watching, and the
 * messages. A remote cannot press our buttons, so prompts say where to.
 */
const root = () => document.getElementById('overlay') as HTMLDivElement

const esc = (s: string) => s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c] as string)

/**
 * What the TV shows for a Live TV channel (the web canvas's LiveReceiver
 * board). Its pictures are this receiver's own, fetched with the cast
 * token; senders get none of them.
 */
export type LiveScreen =
  | { kind: 'failure'; failure: ReceiverFailure }
  | {
    kind: 'tuning' | 'banner' | 'playing' | 'paused' | 'still'
    number: string
    name: string
    logo?: string
    picture?: string
    now?: LiveProgramme
    next?: LiveProgramme
    /** Minutes until the tuner goes back, while paused. */
    pauseLeftMin?: number
  }

export type View = StateMessage & { senderName?: string; backdrop?: string; note?: string; liveScreen?: LiveScreen }

let last = ''
let lastView: View | undefined

export function render(v: View) {
  lastView = v
  const html = view(v)
  if (html === last) return
  last = html
  root().innerHTML = html
}

function view(v: View): string {
  const from = v.senderName ? `<p class="small">Cast from ${esc(v.senderName)}</p>` : ''
  // A brief note, such as Automatic changing the quality; it asks for nothing.
  const note = v.note ? `<div class="note">${esc(v.note)}</div>` : ''
  if (v.liveScreen) return liveView(v.liveScreen, from) + note
  switch (v.phase) {
    case 'idle':
      return `<section class="idle"><img src="logo.svg" alt="WatchMyMedia" class="logo" /><p>Ready to cast</p></section>`
    case 'loading':
      return `<section class="loading"><img src="mark.svg" alt="" class="mark" /><h1>${esc(v.title ?? '')}</h1>${v.subtitle ? `<p>${esc(v.subtitle)}</p>` : ''}<div class="spinner"></div>${from}</section>${note}`
    case 'stopped':
      return `<section class="message"><h1>Playback stopped</h1><p>${v.stopMessage ? `The server&rsquo;s admin stopped this play: &ldquo;${esc(v.stopMessage)}&rdquo;` : 'The server&rsquo;s admin stopped this play.'}</p></section>`
    case 'error':
      return `<section class="message"><h1>Can&rsquo;t play this here</h1><p>${esc(v.error ?? '')}</p></section>`
    case 'ended':
      return v.upNext ? upNext(v) : `<section class="idle"><img src="logo.svg" alt="WatchMyMedia" class="logo" /><p>Finished ${esc(v.title ?? '')}</p></section>`
  }
  let out = note
  if (v.stillWatching) {
    out += `<section class="message"><h1>Still watching${v.nextEpisode ? ` ${esc(v.nextEpisode.showTitle)}` : ''}?</h1><p>Press play, or answer on your phone.</p></section>`
  } else if (v.upNext) {
    out += upNext(v)
  } else if (v.skip) {
    const label = SKIP_LABELS[v.skip.kind] ?? 'Skip'
    out += v.skip.auto
      ? `<div class="toast">${esc(label.replace('Skip', 'Skipping'))}…</div>`
      : `<div class="toast">${esc(label)} <span>on your phone or computer</span></div>`
  }
  return out
}

function upNext(v: View) {
  const n = v.upNext!
  const left = n.secondsLeft
  return `<aside class="next">${n.stillUrl ? `<img src="${esc(n.stillUrl)}" alt="" />` : ''}<div><p class="small">Up next${left !== null ? ` · playing in ${left}` : ''}</p><h2>${esc(n.showTitle)}</h2><p>S${n.seasonNumber} · E${n.episodeNumber} · ${esc(n.title)}</p></div></aside>`
}

// Pictures load before they are drawn: one the server can't fetch, or a
// token that ran out, leaves its place empty rather than a broken image.
// They load with CORS, which the server's media routes answer for any
// origin: Chromium's opaque-response blocking refused them without it.
const loaded = new Set<string>()
const loading = new Set<string>()
function art(url?: string): string | undefined {
  if (!url) return undefined
  if (loaded.has(url)) return url
  if (!loading.has(url)) {
    loading.add(url)
    const img = new Image()
    img.crossOrigin = 'anonymous'
    img.onload = () => {
      loaded.add(url)
      if (lastView) {
        last = ''
        render(lastView)
      }
    }
    img.src = url
  }
  return undefined
}

const message = (f: ReceiverFailure, cls = 'message') =>
  `<section class="${cls}"><h1>${esc(f.title)}</h1><p>${esc(f.body)}</p>${f.small ? `<p class="small">${esc(f.small)}</p>` : ''}</section>`

function liveView(s: LiveScreen, from: string): string {
  if (s.kind === 'failure') return message(s.failure, 'message failure')
  const channel = [s.number, s.name].filter(Boolean).join(' ')
  const now = s.now
  switch (s.kind) {
    case 'tuning':
      return `<section class="loading"><img src="mark.svg" alt="" class="mark" /><h1>${esc(channel)}</h1>${now ? `<p>${esc([now.title, episodeLine(now)].filter(Boolean).join(' · '))}</p>` : ''}<div class="spinner"></div>${from}</section>`
    case 'banner':
      return banner(s)
    case 'paused': {
      const left = s.pauseLeftMin ?? 0
      return `<section class="paused"><span class="pz" aria-hidden="true"><svg viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="5" width="4" height="14" rx="1"/><rect x="14" y="5" width="4" height="14" rx="1"/></svg></span><h1>Paused</h1><p>${left > 0 ? `The tuner goes back in ${left} min, so someone else can watch.` : 'The tuner goes back any moment now, so someone else can watch.'}</p><p class="small">${esc([channel, now?.title].filter(Boolean).join(' · '))} · press play on the remote, your phone or your browser</p></section>`
    }
    case 'still':
      return message({ title: `Still watching ${channel}?`, body: 'Press play, or answer on your phone or browser.', small: 'Nothing has been pressed for 4 hours, so the TV asks before it keeps the tuner.' })
    case 'playing':
      return ''
  }
}

/** The channel banner, on a start and each change. */
function banner(s: Exclude<LiveScreen, { kind: 'failure' }>): string {
  const at = Date.now()
  const now = s.now
  const next = s.next
  const logoUrl = art(s.logo)
  const logo = logoUrl ? `<img src="${esc(logoUrl)}" alt="" crossorigin="anonymous" />` : ''
  const number = `<div class="tvnum">${logo}<span class="num">${esc(s.number)}</span><span class="name">${esc(s.name)}</span></div>`
  if (!now) return `<section class="tvb">${number}<div class="tvnow"><div class="line"><span class="live">LIVE</span><span class="title">${esc(s.name)}</span></div><div class="times">No listings</div></div></section>`
  const start = Date.parse(now.start)
  const end = Date.parse(now.end)
  const pictureUrl = art(s.picture)
  const picture = pictureUrl ? `<div class="tvpic"><img src="${esc(pictureUrl)}" alt="" crossorigin="anonymous" /></div>` : ''
  const tags = `${now.new ? '<span class="tag new">New</span>' : ''}${now.hd ? '<span class="tag">HD</span>' : ''}`
  const ep = episodeLine(now)
  const following = next
    ? `<div class="tvnext"><span class="when">Next · ${esc(timeOfDay(Date.parse(next.start)))}</span><span class="title">${esc(next.title)}</span>${episodeLine(next) ? `<span class="ep">${esc(episodeLine(next))}</span>` : ''}</div>`
    : ''
  return `<section class="tvb">${number}${picture}<div class="tvnow"><div class="line"><span class="live">LIVE</span><span class="title">${esc(now.title)}</span>${tags}</div>${ep ? `<div class="ep">${esc(ep)}</div>` : ''}<div class="times">${esc(timeRange(start, end))} · ${esc(duration(end - at))} left</div><div class="thin"><span style="width:${Math.round(progress(now, at))}%"></span></div></div>${following}</section>`
}

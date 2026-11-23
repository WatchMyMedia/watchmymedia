import type { components } from '../../src/api/schema'
import { FakeServer, problem } from './server'
import { FAKE_USER } from './data'

// Live TV for the browser preview: a fictional antenna lineup with listings
// over today and the next two days, favourites, reminders (one due as the
// preview opens), tuner sessions that start, pause and give the tuner back,
// and household recordings with a clash and one still recording. Every
// channel, programme and name is made up. Logos are server paths that the
// preview can't load, so the screens show their fallbacks.
//
// Modes (?fakes=…): "nolistings" (channels only), "member" (may watch, not
// record), "notv" (no tuner: Live TV leaves the rail).
//
// Some channels misbehave on purpose: 4.4 has no signal,
// 20.1 finds every tuner busy, 36.1 isn't in the tuner's list, and a
// recording takes 7.1's tuner 45 s after it starts.

type Channel = components['schemas']['LiveTVChannel']
type Programme = components['schemas']['LiveTVProgramme']
type GuideChannel = components['schemas']['LiveTVGuideChannel']
type Recording = components['schemas']['LiveTVRecording']
type Rule = components['schemas']['LiveTVRecordingRule']
type Reminder = components['schemas']['LiveTVReminder']
type Session = components['schemas']['LiveTVSessionView']
type Conflict = components['schemas']['LiveTVConflictRef']

const MIN = 60_000
const HOUR = 60 * MIN

/** [minutes, title, episode, category, flags (N new, L live, H HD), categories]; a channel's evening repeats through the listings. */
type Show = [number, string, string, 'series' | 'news' | 'movie' | 'sports' | 'kids', string, string]

const LINEUP: { number: string; name: string; shows: Show[] | null; available?: boolean }[] = [
  { number: '2.1', name: 'HRBR-HD', shows: [[60, 'Harbor Evening News', '', 'news', 'LH', 'News'], [30, 'Quiz Parlor', 'S12 E88', 'series', 'H', 'Game show'], [30, 'Dollar Dash', 'S3 E14 · Bargain Bins', 'series', 'NH', 'Game show, Reality'], [60, 'Coastline Rescue', 'S2 E5 · Fog Bank', 'series', 'NH', 'Series, Drama'], [60, 'Harbor News at 10', '', 'news', 'LH', 'News']] },
  { number: '2.2', name: 'Harbor Weather', shows: [[120, 'Weather Now', 'Forecast and radar', 'news', 'L', 'Weather, News']] },
  { number: '2.3', name: 'Retro Reels', shows: [[60, 'Laugh Lines', 'S2 E5 · The Dinner Party', 'series', '', 'Sitcom'], [90, 'The Iron Coast', '1958', 'movie', '', 'Movie, Adventure'], [90, 'Night of the Lanterns', '1961', 'movie', '', 'Movie, Mystery']] },
  // 4.1's schedule is laid out around the game, which starts within five minutes of the preview opening (its reminder is due).
  { number: '4.1', name: 'NFLD-HD', shows: [[90, 'Conference Football', 'Ridgeway at Lakeport', 'sports', 'LH', 'Sports, Football'], [30, 'Northfield News', '', 'news', 'LH', 'News'], [60, 'Market Minute', '', 'news', 'H', 'News, Business'], [60, 'The Sleuth Files', 'S5 E3 · Cold Ledger', 'series', 'NH', 'Series, Crime drama']] },
  { number: '4.2', name: 'Sleuth Hour', shows: [[60, 'Inspector Vane', 'S3 E6 · The Glass Orchard', 'series', 'N', 'Series, Mystery'], [60, 'Inspector Vane', 'S3 E3 · A Quiet Village', 'series', '', 'Series, Mystery'], [120, 'Case Closed?', 'S1 E7', 'series', '', 'Documentary, Crime']] },
  { number: '4.3', name: 'Coast Shop', shows: null },
  { number: '4.4', name: 'Night Owl', shows: [[60, 'Midnight Mysteries', 'S1 E4 · Low Fog', 'series', '', 'Series, Mystery']] },
  { number: '7.1', name: 'LKSD-HD', shows: [[60, 'Lakeside Evening News', '', 'news', 'LH', 'News'], [60, 'Valley Bake-Off', 'S6 E10 · Pies', 'series', 'NH', 'Series, Cooking, Reality'], [60, 'Station 9', 'S4 E12 · The Harbor Fire', 'series', 'NH', 'Series, Drama']] },
  { number: '7.2', name: 'Driftwood', shows: [[60, 'Trail Kitchen', 'S2 E1 · Smoke and Cedar', 'series', '', 'Cooking, Travel'], [60, 'Cabin Builders', 'S1 E5', 'series', '', 'Reality, Home'], [120, 'The Long Shore', '1974', 'movie', '', 'Movie, Drama']] },
  { number: '7.3', name: 'Kids Corner', shows: [[60, 'Robo Rangers', 'S3 E8 · Gear Up', 'kids', '', 'Children, Animated'], [30, 'Sprout & Pip', 'Rainy Day Garden', 'kids', '', 'Children'], [30, 'Puzzle Pals', 'S1 E20', 'kids', '', 'Children, Educational']] },
  { number: '9.1', name: 'PNVL-HD', shows: [[60, 'Trivia Town', 'S4 E31', 'series', 'H', 'Game show'], [120, 'Pro Hockey', 'Ridgeway Hawks vs. Port Ellis', 'sports', 'LH', 'Sports, Hockey'], [60, 'Postgame', '', 'sports', 'LH', 'Sports']] },
  { number: '11.1', name: 'Valley Public', shows: [[60, 'World Desk', '', 'news', 'H', 'News'], [60, 'Nature’s Edge', '2026 E14 · Owls of the Valley', 'series', 'NH', 'Documentary, Nature'], [120, 'Great Rail Trips', 'S2 E3 · The Northern Line', 'series', 'H', 'Documentary, Travel']] },
  { number: '20.1', name: 'Cinema Vault', shows: [[105, 'The Glass Harbor', '1949', 'movie', '', 'Movie, Drama'], [135, 'Seven Bridges West', '1955', 'movie', '', 'Movie, Western']] },
  { number: '36.1', name: 'LKPT-36', shows: null, available: false },
]

const DESCRIPTIONS: Record<string, string> = {
  'Conference Football': 'Ridgeway travel to Lakeport with the conference title in reach. Live from Lakeport Field.',
  'Inspector Vane': 'A quiet village, a missing ledger and a vicar who knows more than he says.',
  'Valley Bake-Off': 'Pie week: the bakers face a hot-water crust and a lattice showstopper.',
  'The Iron Coast': 'A lighthouse keeper and a smuggler share one stormy winter.',
  'Weather Now': 'The forecast for the valley and the coast, with live radar.',
}

const idOf = (number: string) => `ch-${number.replace('.', '-')}`
const localMidnight = (at: number) => { const d = new Date(at); return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime() }
const iso = (at: number) => new Date(at).toISOString()

function programmesFor(number: string, shows: Show[], from: number, to: number, anchor: number): Programme[] {
  const cycle = shows.reduce((n, s) => n + s[0], 0) * MIN
  // The schedule starts at the anchor, repeated back and forth to cover the listings.
  let at = anchor - Math.ceil((anchor - from) / cycle) * cycle
  const out: Programme[] = []
  for (let i = 0; at < to; i++) {
    const [minutes, title, episode, category, flags, categories] = shows[i % shows.length]
    const end = at + minutes * MIN
    if (end > from) {
      const numbered = /^S(\d+) E(\d+)(?: · (.+))?$/.exec(episode)
      const year = /^(\d{4})( E(\d+) · (.+))?$/.exec(episode)
      out.push({
        id: `${idOf(number)}@${at}`, start: iso(at), end: iso(end), title,
        ...(numbered ? { season: Number(numbered[1]), episode: Number(numbered[2]), episodeTitle: numbered[3] } : {}),
        ...(year && year[2] ? { season: Number(year[1]), episode: Number(year[3]), episodeTitle: year[4] } : {}),
        ...(!numbered && !year && episode ? { episodeTitle: episode } : {}),
        ...(category === 'movie' ? { kind: 'movie', airDate: episode } : category === 'news' ? { kind: 'show' } : { kind: 'episode' }),
        categories: categories.split(', '), new: flags.includes('N'), live: flags.includes('L'), hd: flags.includes('H'),
        description: DESCRIPTIONS[title],
        ...(category === 'series' ? { seriesKey: `series-${title.toLowerCase().replace(/[^a-z]+/g, '-')}` } : {}),
      })
    }
    at = end
  }
  return out
}

/** The fake's state: listings fixed when the server is made, and what people change. */
class LiveStore {
  readonly opened = Date.now()
  readonly listedThrough: number
  readonly channels: Channel[]
  readonly listings = new Map<string, Programme[]>()
  favourites = new Set([idOf('4.1'), idOf('7.1'), idOf('9.1'), idOf('11.1')])
  reminders: Reminder[] = []
  recordings: Recording[] = []
  rules: Rule[] = []
  sessions = new Map<string, Session & { tunedAt: number; pausedAt?: number; ends?: { at: number; reason: string } }>()
  private serial = 0

  constructor(readonly listingsOn: boolean, readonly member: boolean, readonly noTuner = false) {
    const from = localMidnight(this.opened) - 3 * HOUR
    this.listedThrough = localMidnight(this.opened) + 2 * 24 * HOUR + 19 * HOUR
    const halfHour = Math.floor(this.opened / (30 * MIN)) * 30 * MIN
    // The game starts on the next 5 minutes, at least a minute away: its 5-minute reminder is due at once.
    const game = Math.ceil((this.opened + MIN) / (5 * MIN)) * 5 * MIN
    this.channels = LINEUP.map((c) => ({
      id: idOf(c.number), sourceId: 'hdhr-1', number: c.number, name: c.name, enabled: true, available: !noTuner && c.available !== false,
      ...(noTuner || c.available === false ? { reason: 'not_in_lineup' } : {}),
      ...(c.shows ? { logoUrl: `/api/live-tv/art/logo-${idOf(c.number)}.png` } : {}),
    }))
    for (const c of LINEUP) {
      if (c.shows) this.listings.set(idOf(c.number), programmesFor(c.number, c.shows, from, this.listedThrough, c.number === '4.1' ? game : halfHour - HOUR))
    }
    this.seed()
  }

  id(prefix: string) { return `${prefix}-${++this.serial}` }

  programme(id: string): { channel: Channel; programme: Programme } | undefined {
    for (const channel of this.channels) {
      const programme = this.listings.get(channel.id)?.find((p) => p.id === id)
      if (programme) return { channel, programme }
    }
    return undefined
  }

  /** The first programme with this title on the channel that ends after `after`. */
  find(number: string, title: string, after: number, startsAfter = false) {
    const channel = this.channels.find((c) => c.number === number)!
    const programme = this.listings.get(channel.id)!.find((p) => p.title === title && (startsAfter ? Date.parse(p.start) > after : Date.parse(p.end) > after))!
    return { channel, programme }
  }

  /** On a channel at a time. */
  onAt(channelId: string, at: number) {
    return this.listings.get(channelId)?.find((p) => Date.parse(p.start) <= at && at < Date.parse(p.end))
  }

  recordingFor(found: { channel: Channel; programme: Programme }, state: string, extra: Partial<Recording> = {}): Recording {
    const { channel, programme: p } = found
    return {
      id: this.id('rec'), userId: FAKE_USER.id, userName: 'Robin', sourceId: channel.sourceId, channelId: channel.id, number: channel.number, channelName: channel.name,
      programmeId: p.id, title: p.title, episodeTitle: p.episodeTitle, season: p.season, episode: p.episode, start: p.start, end: p.end, state,
      series: !!extra.ruleId, padBefore: 1, padAfter: 2, bytes: 0, gaps: [], canCancel: true, canDelete: false, ...extra,
    }
  }

  private seed() {
    const now = this.opened
    // Recording now: what is on 2.1, part of a series rule.
    const ch21 = this.channels.find((c) => c.number === '2.1')!
    const news = { channel: ch21, programme: this.onAt(ch21.id, now)! }
    const newsRule: Rule = { id: this.id('rule'), kind: 'series', title: news.programme.title, userName: 'Robin', newOnly: false, anyChannel: false, keep: 5, padBefore: 1, padAfter: 2, canCancel: true, channelId: ch21.id, number: '2.1', seriesKey: news.programme.seriesKey ?? 'series-harbor' }
    const recordingNow = this.recordingFor(news, 'recording', { ruleId: newsRule.id, series: true, startedAt: iso(Math.min(now, Date.parse(news.programme.start))) })
    // Tonight's clash: two recordings hold both tuners when Coastline Rescue would record.
    const vane = this.find('4.2', 'Inspector Vane', now + HOUR, true)
    const vaneRule: Rule = { id: this.id('rule'), kind: 'series', title: 'Inspector Vane', userName: 'Robin', newOnly: true, anyChannel: false, keep: 0, padBefore: 1, padAfter: 2, canCancel: true, channelId: vane.channel.id, number: '4.2', seriesKey: 'series-inspector-vane' }
    const vaneRec = this.recordingFor(vane, 'scheduled', { ruleId: vaneRule.id })
    const vaneStart = Date.parse(vane.programme.start)
    const game = this.find('4.1', 'Conference Football', vaneStart, true)
    const gameRec = this.recordingFor(game, 'scheduled', { userName: 'Sam', canCancel: true })
    const rescue = this.find('2.1', 'Coastline Rescue', vaneStart - 30 * MIN)
    const clash = this.recordingFor(rescue, 'conflict', { reason: 'tuners_busy', conflictsWith: [this.conflict(vaneRec), this.conflict(gameRec)] })
    const weatherRule: Rule = { id: this.id('rule'), kind: 'manual', title: 'Harbor Weather', userName: 'Sam', newOnly: false, anyChannel: false, keep: 0, padBefore: 0, padAfter: 0, canCancel: true, channelId: idOf('2.2'), number: '2.2', repeat: 'weekdays', start: iso(localMidnight(now) + 30 * HOUR), zone: Intl.DateTimeFormat().resolvedOptions().timeZone }
    const weather = this.recordingFor(this.find('2.2', 'Weather Now', localMidnight(now) + 30 * HOUR), 'scheduled', { title: 'Harbor Weather', ruleId: weatherRule.id, userName: 'Sam', series: false })
    // Recorded: an episode, a game with a gap, one that didn't record and an older news.
    const past = (days: number, hours: number) => localMidnight(now) - days * 24 * HOUR + hours * HOUR
    const done = (title: string, number: string, start: number, minutes: number, state: string, extra: Partial<Recording> = {}): Recording => ({
      id: this.id('rec'), userId: FAKE_USER.id, userName: 'Robin', sourceId: 'hdhr-1', channelId: idOf(number), number, channelName: LINEUP.find((c) => c.number === number)!.name,
      title, start: iso(start), end: iso(start + minutes * MIN), startedAt: iso(start - MIN), endedAt: iso(start + minutes * MIN + 2 * MIN), state, series: false,
      padBefore: 1, padAfter: 2, bytes: state === 'failed' ? 0 : 2_400_000_000, gaps: [], canCancel: false, canDelete: state !== 'failed', ...extra,
    })
    this.recordings = [
      recordingNow, clash, vaneRec, gameRec, weather,
      // API level 34's facts for Sam: the newest is new and unseen, the next part watched, the oldest watched.
      done('Inspector Vane', '4.2', past(1, 21), 60, 'done', { season: 3, episode: 5, episodeTitle: 'Lantern Street', series: true, ruleId: vaneRule.id, mediaFileId: 7105, new: true, kind: 'episode' }),
      done('Inspector Vane', '4.2', past(8, 21), 60, 'done', { season: 3, episode: 4, episodeTitle: 'The Night Ferry', series: true, ruleId: vaneRule.id, mediaFileId: 7108, new: true, kind: 'episode', progress: { positionMs: 38 * MIN, durationMs: 60 * MIN } }),
      done('Inspector Vane', '4.2', past(15, 21), 60, 'done', { season: 3, episode: 3, episodeTitle: 'A Quiet Village', series: true, ruleId: vaneRule.id, mediaFileId: 7109, repeat: true, kind: 'episode', watched: true, progress: { positionMs: 60 * MIN, durationMs: 60 * MIN } }),
      done('Conference Football', '4.1', past(1, 21), 90, 'partial', { userName: 'Sam', episodeTitle: 'Ridgeway at Lakeport', mediaFileId: 7106, gaps: [{ start: iso(past(1, 21) + 12 * MIN), end: iso(past(1, 21) + 14 * MIN), reason: 'signal_lost' }] }),
      done('Penvale Tonight', '9.1', past(1, 22), 30, 'failed', { reason: 'missed' }),
      done('Harbor Evening News', '2.1', past(2, 18), 60, 'done', { ruleId: newsRule.id, series: true, mediaFileId: 7107 }),
    ]
    this.rules = [vaneRule, newsRule, weatherRule]
    // Reminders: the game (due now), a moved one, two later and one no longer listed.
    const remind = (found: { channel: Channel; programme: Programme }, state = 'set'): Reminder => ({
      id: this.id('rem'), programmeId: found.programme.id, channelId: found.channel.id, number: found.channel.number, channelName: found.channel.name,
      title: found.programme.title, episodeTitle: found.programme.episodeTitle, season: found.programme.season, episode: found.programme.episode,
      start: found.programme.start, end: found.programme.end, state,
    })
    const gone: Reminder = { ...remind(this.find('2.1', 'Dollar Dash', now + 24 * HOUR, true)), state: 'gone', programmeId: 'gone-1', id: this.id('rem') }
    this.reminders = [
      remind(this.find('4.1', 'Conference Football', now, true)),
      remind(this.find('2.1', 'Dollar Dash', now + 20 * MIN, true)),
      remind(this.find('2.1', 'Harbor News at 10', now, true), 'moved'),
      remind(this.find('7.1', 'Valley Bake-Off', now + 20 * HOUR, true)),
      gone,
    ]
  }

  conflict(r: Recording): Conflict {
    return { id: r.id, title: r.title, number: r.number, channelName: r.channelName, start: r.start, end: r.end, userName: r.userName, running: r.state === 'recording' }
  }

  /** A programme as the guide sends it: the viewer's reminder and the household's recording mark. */
  dress(p: Programme): Programme {
    const reminder = this.reminders.some((r) => r.programmeId === p.id && r.state !== 'gone')
    const rec = this.member ? undefined : this.recordings.find((r) => r.programmeId === p.id && ['scheduled', 'recording', 'conflict'].includes(r.state))
    return { ...p, reminder, ...(rec ? { recording: { id: rec.id, state: rec.state, series: !!rec.ruleId && rec.series, reason: rec.reason, canCancel: rec.canCancel } } : {}) }
  }

  channel(c: Channel, at: number): Channel {
    const list = this.listingsOn ? this.listings.get(c.id) : undefined
    const i = list?.findIndex((p) => Date.parse(p.start) <= at && at < Date.parse(p.end)) ?? -1
    return {
      ...c, favourite: this.favourites.has(c.id),
      ...(list && i >= 0 ? { now: this.dress(list[i]), next: list[i + 1] && this.dress(list[i + 1]) } : {}),
      ...(this.listingsOn ? {} : { logoUrl: undefined }),
    }
  }
}

function sessionView(store: LiveStore, s: Session & { tunedAt: number; pausedAt?: number; ends?: { at: number; reason: string } }, now: number): Session {
  const { tunedAt, pausedAt, ends, ...view } = s
  void tunedAt; void pausedAt; void ends
  return { ...view, channel: store.channel(view.channel, now), lastSeenAt: iso(now) }
}

export function registerLive(server: FakeServer, mode: string) {
  // ?fakes=notuner: the tuner can't be found, so no channel can play.
  const store = new LiveStore(mode !== 'nolistings', mode === 'member', mode === 'notuner')
  const pauseSeconds = 60
  server.route('GET', '/api/live-tv/capabilities', () => ({
    enabled: mode !== 'notv', allowed: mode !== 'notv', playback: true, guide: mode !== 'nolistings', setupComplete: true,
    recording: true, canRecord: mode !== 'member', automatic: true, bufferSeconds: 60, pauseSeconds, cast: false,
  }))
  server.route('GET', '/api/live-tv/channels', () => ({ items: store.channels.map((c) => store.channel(c, Date.now())) }))

  server.route('GET', '/api/live-tv/guide', ({ url }) => {
    const start = Date.parse(url.searchParams.get('start') ?? '') || Date.now()
    const end = start + Number(url.searchParams.get('hours') ?? 6) * HOUR
    const channels: GuideChannel[] = store.channels.map((c) => {
      const list = store.listingsOn ? store.listings.get(c.id) : undefined
      return {
        ...store.channel(c, Date.now()), listings: !!list,
        programmes: (list ?? []).filter((p) => Date.parse(p.end) > start && Date.parse(p.start) < end).map((p) => store.dress(p)),
      }
    })
    return { start: iso(start), end: iso(end), state: { enabled: store.listingsOn, stale: false, listedThrough: iso(store.listedThrough), updatedAt: iso(store.opened - 2 * HOUR) }, channels }
  })
  server.route('GET', '/api/live-tv/guide/search', ({ url }) => {
    const words = (url.searchParams.get('q') ?? '').toLowerCase().split(/\s+/).filter(Boolean)
    const now = Date.now()
    const items: { channel: Channel; programme: Programme }[] = []
    for (const c of store.channels) {
      for (const p of store.listings.get(c.id) ?? []) {
        if (Date.parse(p.end) > now && words.every((w) => p.title.toLowerCase().includes(w))) items.push({ channel: store.channel(c, now), programme: store.dress(p) })
      }
    }
    items.sort((a, b) => Date.parse(a.programme.start) - Date.parse(b.programme.start))
    return { items: items.slice(0, Number(url.searchParams.get('limit') ?? 20)) }
  })

  const favourite = (on: boolean) => ({ params }: { params: Record<string, string> }) => {
    if (!store.channels.some((c) => c.id === params.id)) return problem(404, 'no such channel')
    if (on) store.favourites.add(params.id); else store.favourites.delete(params.id)
    return undefined
  }
  server.route('PUT', '/api/live-tv/channels/:id/favourite', favourite(true))
  server.route('DELETE', '/api/live-tv/channels/:id/favourite', favourite(false))

  // Reminders: the viewer's, soonest first, with their lead time.
  server.route('GET', '/api/live-tv/reminders', () => {
    const now = Date.now()
    store.reminders = store.reminders.filter((r) => Date.parse(r.end) > now)
    return { items: [...store.reminders].sort((a, b) => Date.parse(a.start) - Date.parse(b.start)), leadMinutes: 5 }
  })
  server.route('PUT', '/api/live-tv/reminders', ({ body }) => {
    const id = (body as { programmeId?: string })?.programmeId ?? ''
    const found = store.programme(id)
    if (!found) return problem(404, 'no such programme')
    const existing = store.reminders.find((r) => r.programmeId === id)
    if (existing) return existing
    const r: Reminder = { id: store.id('rem'), programmeId: id, channelId: found.channel.id, number: found.channel.number, channelName: found.channel.name, title: found.programme.title, episodeTitle: found.programme.episodeTitle, season: found.programme.season, episode: found.programme.episode, start: found.programme.start, end: found.programme.end, state: 'set' }
    store.reminders.push(r)
    return r
  })
  server.route('DELETE', '/api/live-tv/reminders/:id', ({ params }) => {
    store.reminders = store.reminders.filter((r) => r.id !== params.id)
    return undefined
  })

  // Sessions: a channel starts within a second or two, a pause keeps the tuner pauseSeconds.
  server.route('POST', '/api/live-tv/sessions', ({ body }) => {
    const b = body as { channelId: string; quality?: string; clientName?: string; requestId: string }
    const channel = store.channels.find((c) => c.id === b.channelId)
    if (!channel || !channel.available) return problem(404, 'channel_unavailable')
    if (channel.number === '20.1') return problem(503, 'tuners_busy')
    const now = Date.now()
    const existing = [...store.sessions.values()].find((s) => s.id === `live-${b.requestId}`)
    if (existing) return sessionView(store, existing, now)
    const id = `live-${b.requestId}`
    // A recording set for later on 7.1 needs its tuner 45 s after it starts.
    const takeover = channel.number === '7.1' && !store.member ? store.recordingFor(store.find('7.1', 'Station 9', now), 'scheduled') : undefined
    if (takeover) store.recordings.push(takeover)
    const s = {
      id, channel, state: 'starting', quality: b.quality ?? '1080', url: `/api/live-tv/sessions/${id}/hls/index.m3u8`,
      userId: FAKE_USER.id, userName: FAKE_USER.name, clientName: b.clientName ?? 'Samsung TV', startedAt: iso(now), lastSeenAt: iso(now),
      paused: false, pauseRemainingSeconds: pauseSeconds, bufferSeconds: 60, bufferBytes: 0, mode: 'remux', automatic: b.quality === 'auto',
      ...(b.quality === 'auto' ? { actualQuality: '1080' } : {}),
      tunedAt: now,
      ...(takeover ? { takeoverAt: iso(now + 45_000), takeoverRecordingId: takeover.id, takeoverTitle: takeover.title, takeoverCanCancel: true } : {}),
      ...(channel.number === '4.4' ? { ends: { at: now + 1500, reason: 'no_signal' } } : takeover ? { ends: { at: now + 45_000, reason: 'recording' } } : {}),
    }
    store.sessions.set(id, s)
    return sessionView(store, s, now)
  })
  const advance = (id: string) => {
    const s = store.sessions.get(id)
    if (!s) return undefined
    const now = Date.now()
    if (s.state === 'starting' && now - s.tunedAt >= 1200) { s.state = 'playing'; s.readyAt = iso(now) }
    if (s.ends && now >= s.ends.at && s.state !== 'stopped') { s.state = 'stopped'; s.reason = s.ends.reason }
    if (s.paused && s.pausedAt !== undefined) {
      s.pauseRemainingSeconds = Math.max(0, pauseSeconds - Math.floor((now - s.pausedAt) / 1000))
      if (!s.pauseRemainingSeconds) { s.state = 'stopped'; s.reason = 'pause_expired' }
    }
    return s
  }
  server.route('GET', '/api/live-tv/sessions/:id', async ({ params, url }) => {
    const wait = Math.min(Number(url.searchParams.get('waitMs') ?? 0), 1500)
    if (wait && store.sessions.get(params.id)?.state === 'starting') await new Promise((r) => setTimeout(r, wait))
    const s = advance(params.id)
    return s ? sessionView(store, s, Date.now()) : problem(404, 'no such session')
  })
  server.route('PUT', '/api/live-tv/sessions/:id', ({ params, body }) => {
    const s = advance(params.id)
    if (!s) return problem(404, 'no such session')
    const paused = !!(body as { paused?: boolean })?.paused
    if (paused && !s.paused) { s.paused = true; s.pausedAt = Date.now() }
    if (!paused && s.paused) { s.paused = false; s.pausedAt = undefined; s.pauseRemainingSeconds = pauseSeconds }
    return sessionView(store, s, Date.now())
  })
  server.route('DELETE', '/api/live-tv/sessions/:id', ({ params }) => { store.sessions.delete(params.id); return undefined })

  // Recordings, by section, as the server pages them (one page here).
  const sorted = (items: Recording[], newest = false) => [...items].sort((a, b) => (Date.parse(a.start) - Date.parse(b.start)) * (newest ? -1 : 1))
  server.route('GET', '/api/live-tv/recordings', ({ url }) => {
    if (store.member) return problem(403, 'not_allowed')
    const section = url.searchParams.get('section')
    const r = store.recordings
    const page = { tuners: 2, freeBytes: 412_000_000_000 }
    switch (section) {
      case 'now': return { ...page, items: r.filter((x) => x.state === 'recording') }
      case 'conflicts': return { ...page, items: sorted(r.filter((x) => x.state === 'conflict')) }
      case 'upcoming': return { ...page, items: sorted(r.filter((x) => x.state === 'scheduled' || x.state === 'gone')) }
      case 'series': return { ...page, items: [], rules: store.rules }
      case 'recorded': return { ...page, items: sorted(r.filter((x) => ['done', 'partial', 'failed'].includes(x.state)), true) }
      default: return problem(422, 'unknown section')
    }
  })
  server.route('GET', '/api/live-tv/recordings/:id', ({ params }) => store.recordings.find((r) => r.id === params.id) ?? problem(404, 'no such recording'))
  server.route('POST', '/api/live-tv/recordings', ({ body }) => {
    const b = body as { programmeId: string; scope: 'once' | 'series'; newOnly?: boolean; anyChannel?: boolean; keep?: number; padBefore?: number; padAfter?: number }
    const found = store.programme(b.programmeId)
    if (!found) return problem(404, 'no such programme')
    const start = Date.parse(found.programme.start), end = Date.parse(found.programme.end)
    const holders = store.recordings.filter((r) => ['scheduled', 'recording'].includes(r.state) && Date.parse(r.start) < end && start < Date.parse(r.end))
    let ruleId: string | undefined
    if (b.scope === 'series') {
      const rule: Rule = { id: store.id('rule'), kind: 'series', title: found.programme.title, userName: FAKE_USER.name, newOnly: b.newOnly ?? true, anyChannel: b.anyChannel ?? false, keep: b.keep ?? 0, padBefore: b.padBefore ?? 1, padAfter: b.padAfter ?? 2, canCancel: true, channelId: found.channel.id, number: found.channel.number, seriesKey: found.programme.seriesKey }
      store.rules.push(rule)
      ruleId = rule.id
    }
    const airing = start <= Date.now()
    const r = store.recordingFor(found, holders.length >= 2 ? 'conflict' : airing ? 'recording' : 'scheduled', {
      userName: FAKE_USER.name, ruleId, series: b.scope === 'series', padBefore: b.padBefore ?? 1, padAfter: b.padAfter ?? 2,
      ...(holders.length >= 2 ? { reason: 'tuners_busy', conflictsWith: holders.map((h) => store.conflict(h)) } : {}),
      ...(airing ? { startedAt: iso(Date.now()) } : {}),
    })
    store.recordings.push(r)
    return r
  })
  server.route('PATCH', '/api/live-tv/recordings/:id', ({ params, body }) => {
    const r = store.recordings.find((x) => x.id === params.id)
    if (!r) return problem(404, 'no such recording')
    const b = body as { prefer?: boolean; over?: string; padBefore?: number; padAfter?: number }
    if (b.prefer) {
      const over = store.recordings.find((x) => x.id === (b.over ?? r.conflictsWith?.find((h) => !h.running)?.id))
      if (over) Object.assign(over, { state: 'conflict', reason: 'tuners_busy', conflictsWith: [store.conflict(r)] })
      Object.assign(r, { state: 'scheduled', reason: undefined, conflictsWith: undefined })
    }
    if (b.padBefore !== undefined) r.padBefore = b.padBefore
    if (b.padAfter !== undefined) r.padAfter = b.padAfter
    return r
  })
  server.route('DELETE', '/api/live-tv/recordings/:id', ({ params }) => {
    const r = store.recordings.find((x) => x.id === params.id)
    if (!r) return problem(404, 'no such recording')
    // Stopping one under way keeps what was recorded; any other is cancelled.
    if (r.state === 'recording') Object.assign(r, { state: 'partial', reason: 'stopped', endedAt: iso(Date.now()), canDelete: true, bytes: 900_000_000 })
    else store.recordings = store.recordings.filter((x) => x.id !== r.id)
    return undefined
  })
  server.route('DELETE', '/api/live-tv/recordings/:id/file', ({ params }) => {
    store.recordings = store.recordings.filter((x) => x.id !== params.id)
    return undefined
  })
  server.route('PATCH', '/api/live-tv/recording-rules/:id', ({ params, body }) => {
    const rule = store.rules.find((x) => x.id === params.id)
    if (!rule) return problem(404, 'no such rule')
    Object.assign(rule, body as Partial<Rule>)
    return rule
  })
  server.route('DELETE', '/api/live-tv/recording-rules/:id', ({ params }) => {
    store.rules = store.rules.filter((x) => x.id !== params.id)
    // Its upcoming recordings go with it; what it recorded stays.
    store.recordings = store.recordings.filter((x) => x.ruleId !== params.id || !['scheduled', 'conflict'].includes(x.state))
    return undefined
  })
}

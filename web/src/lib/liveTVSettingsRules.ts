import type { LiveChannel } from '../api/liveTV'

export type LiveSetupStep = 'sources' | 'tmdb' | 'subtitles' | 'transcode' | 'done'

/** A saved Live TV setup remains complete even when its tuner is later removed. */
export function liveTVSetupPlan(libraryCount: number, setupComplete: boolean) {
  const hasLibraries = libraryCount > 0
  const steps: [LiveSetupStep, string][] = [['sources', 'What to watch']]
  if (hasLibraries) steps.push(['tmdb', 'Metadata'], ['subtitles', 'Subtitles'])
  steps.push(['transcode', 'Transcoding'], ['done', 'Done'])
  return {
    steps,
    hasLibraries,
    canContinue: hasLibraries || setupComplete,
    nextStep: hasLibraries ? 'tmdb' as const : 'transcode' as const,
    backFromTranscode: hasLibraries ? 'subtitles' as const : 'sources' as const,
    destination: hasLibraries ? '/' as const : '/live-tv' as const,
  }
}

/** Preserve hidden-channel choices even while a tuner or channel is unavailable. */
export function liveTVChannelSetting(channel: Pick<LiveChannel, 'available' | 'enabled' | 'reason'>) {
  const protectedChannel = channel.reason === 'protected_channel'
  let status: string
  switch (channel.reason) {
    case 'protected_channel': status = 'Copy-protected · can’t be played'; break
    case 'source_disabled': status = 'Tuner is disabled'; break
    case 'unavailable': status = 'Missing from the last refresh'; break
    default: status = channel.reason || !channel.available ? 'Unavailable' : channel.enabled ? 'Shown' : 'Hidden'
  }
  return { checked: channel.enabled && !protectedChannel, locked: protectedChannel, status }
}

/** The listings' status pill (PLAN-LIVE-TV-GUIDE.md §6.2); the server's message says what to do. */
export function liveGuideStatus(state: string): { label: string; tone: 'ok' | 'warn' | 'off'; busy: boolean } {
  switch (state) {
    case 'ok': return { label: 'Up to date', tone: 'ok', busy: false }
    case 'fetching': return { label: 'Updating…', tone: 'off', busy: true }
    case 'waiting': return { label: 'Not fetched yet', tone: 'off', busy: true }
    case 'off': return { label: 'Off', tone: 'off', busy: false }
    case 'stale': return { label: 'Out of date', tone: 'warn', busy: false }
    case 'firmware': return { label: 'Tuner firmware too old', tone: 'warn', busy: false }
    case 'refused': return { label: 'Refused by SiliconDust', tone: 'warn', busy: false }
    case 'unavailable': return { label: 'SiliconDust unavailable', tone: 'warn', busy: false }
    case 'failed': return { label: 'Couldn’t read the listings', tone: 'warn', busy: false }
    case 'no_tuner': return { label: 'No tuner', tone: 'warn', busy: false }
    default: return { label: state, tone: 'warn', busy: false }
  }
}

/** How a channel matched the listings; a channel the feed doesn't list keeps its switch off and still plays. */
export function liveGuideMatch(match: string): { label: string; warn: boolean; locked: boolean } {
  switch (match) {
    case 'name': return { label: 'Number and name', warn: false, locked: false }
    case 'number': return { label: 'By number · the tuner gives no name', warn: false, locked: false }
    case 'differs': return { label: 'Names differ · check before trusting them', warn: true, locked: false }
    case 'missing': return { label: 'Not in the listings · it still plays', warn: false, locked: true }
    case 'pending': return { label: 'After the first update', warn: false, locked: true }
    default: return { label: match, warn: false, locked: false }
  }
}

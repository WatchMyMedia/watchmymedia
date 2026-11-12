import type { LiveChannel, LiveProfile, LiveQuality } from '../api/liveTV'
import { halfHourStart } from './liveGuide'

export const LIVE_QUALITIES: LiveQuality[] = ['auto', '1080', '720', '480', '360']
/** Shared conservative HLS profile for web and the receiver. Broadcast MPEG-2,
 * AC-3 and interlaced material continue through server conversion. */
export const compatibleLiveProfile: LiveProfile = {
  segmentFormats: ['mpegts'], videoCodecs: ['h264'], audioCodecs: ['aac'],
  interlaced: false, maxHeight: 1080,
}
export function liveQuality(value: string | null): LiveQuality {
  return LIVE_QUALITIES.find((quality) => quality === value) ?? '1080'
}
const QUALITY_KEY = 'wmm.liveQuality'
/** This browser's Live TV quality: what it plays here and asks a TV to play. */
export function savedLiveQuality(): LiveQuality {
  try { return liveQuality(localStorage.getItem(QUALITY_KEY)) } catch { return liveQuality(null) }
}
export function saveLiveQuality(quality: LiveQuality) {
  try { localStorage.setItem(QUALITY_KEY, quality) } catch { /* This browser may block storage. */ }
}
export function nextLiveChannel(channels: LiveChannel[], current: string, direction: -1 | 1): LiveChannel | undefined {
  const index = channels.findIndex((channel) => channel.id === current)
  const start = index < 0 ? (direction === 1 ? -1 : 0) : index
  for (let step = 1; step <= channels.length; step++) {
    const channel = channels[(start + step * direction + channels.length) % channels.length]
    if (channel.available && channel.enabled) return channel
  }
  return undefined
}
export function filterLiveChannels<T extends Pick<LiveChannel, 'number' | 'name'>>(channels: T[], query: string): T[] {
  const q = query.trim().toLocaleLowerCase()
  return channels.filter((channel) => `${channel.number} ${channel.name}`.toLocaleLowerCase().includes(q))
}
export function liveViewingPrompt(lastInput: number, now: number): 'none' | 'ask' | 'expired' {
  const idle = now - lastInput
  return idle >= 4 * 60 * 60_000 + 60_000 ? 'expired' : idle >= 4 * 60 * 60_000 ? 'ask' : 'none'
}
export function liveEdge(start: number, end: number): number { return Math.max(start, end - 3) }
export function pauseExpired(position: number, start: number, end: number): boolean { return position < start || position > end }
export function guideTimes(now: number): Date[] {
  // Subtract elapsed time: setMinutes picks the earlier occurrence when the
  // clock repeats an hour at the end of daylight saving time.
  const start = halfHourStart(now)
  return Array.from({ length: 4 }, (_, i) => new Date(start + i * 30 * 60_000))
}
/** Why a channel stopped or couldn't start. `watchRecording` false where a recording still recording can't be watched (a TV, while casting). */
export function liveFailure(reason?: string, { watchRecording = true }: { watchRecording?: boolean } = {}): { title: string; detail: string } {
  switch (reason) {
    case 'tuners_busy': return { title: 'All tuners are in use', detail: 'Someone else is watching, here or in another app. Try again after a tuner becomes free.' }
    case 'server_busy': return { title: 'The server is busy', detail: 'All transcoding slots are in use. Stop another play or try again in a moment.' }
    case 'no_signal': return { title: 'No signal on this channel', detail: 'Try another channel, or check the antenna connection and reception.' }
    case 'signal_lost': case 'stream_stalled': return { title: 'The broadcast stopped', detail: 'The signal was lost or the server could not keep up. Try again, or choose a lower quality.' }
    case 'protected_channel': return { title: 'This channel is protected', detail: 'WatchMyMedia can play clear antenna channels only.' }
    case 'tuner_unavailable': return { title: 'The tuner is not answering', detail: 'Check its power and network connection, then try again.' }
    case 'device_changed': return { title: 'A different tuner is at this address', detail: 'An admin needs to check the tuner address in Settings → Live TV.' }
    case 'pause_expired': return { title: 'Your tuner was released', detail: 'A pause keeps the tuner for five minutes. Resume starts a new session at live and needs a free tuner.' }
    case 'lease_expired': return { title: 'Your session ended', detail: 'The server lost contact with this player. Start again at the live broadcast.' }
    case 'inactive': return { title: 'Still watching?', detail: 'Your tuner was released after four hours without input. Resume to keep watching.' }
    case 'recording': return { title: 'Stopped for a recording', detail: `This tuner is now recording for your household.${watchRecording ? ' You can watch that recording from the start.' : ''}` }
    case 'stopped_by_admin': return { title: 'Stopped by an admin', detail: 'An admin ended this play and released its tuner.' }
    case 'access_changed': case 'access_revoked': case 'source_changed': case 'source_removed': case 'channel_disabled': return { title: 'Live TV access changed', detail: 'The source, channel or viewing permission was changed. Return to the channels to see what is available.' }
    case 'storage_limit': case 'storage_error': return { title: 'The live buffer could not be kept', detail: 'An admin needs to check the server’s transcoding disk space.' }
    case 'encoder_failed': return { title: 'The broadcast could not be converted', detail: 'Try again at a lower quality. If it keeps happening, an admin should check the transcoder.' }
    default: return { title: 'Live TV could not start', detail: reason || 'Try again, or choose another channel.' }
  }
}

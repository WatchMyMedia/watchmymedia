/** Where a seek's target is: in what the player has loaded, or outside it.
 * A VOD playlist or a file holds the whole title from zero; an event playlist
 * that started at `offset` holds only what the server has produced so far. */
export function seekStaysInPlayer(localS: number, offset: number, wholeTitle: boolean, loadedS: number): boolean {
  if (offset === 0 && wholeTitle) return true
  return localS >= 0 && localS <= loadedS
}

/** How to answer CAF's standard SEEK (system controls, Google Home), which
 * is in player time. A seek the player can make goes back to CAF unchanged:
 * calling PlayerManager.seek from here enters the same interceptor again and
 * hung the receiver. A seek outside an event playlist reloads at the target. */
export function interceptSeek(currentTime: number | undefined, offset: number, wholeTitle: boolean, loadedS: number): { fileS: number; reload: boolean } | undefined {
  if (currentTime === undefined || !Number.isFinite(currentTime)) return undefined
  return { fileS: offset + currentTime, reload: !seekStaysInPlayer(currentTime, offset, wholeTitle, loadedS) }
}

/** CAF resets its clock to zero before MEDIA_FINISHED(ERROR). Only a playable
 * state replaces the position retained for reload/fallback and final reports. */
export function rememberedPosition(previous: number, current: number, state: string): number {
  return (state === 'PLAYING' || state === 'PAUSED') && Number.isFinite(current) && current >= 0 ? current : previous
}

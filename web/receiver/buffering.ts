/** CAF's default Shaka forward buffer is shorter than the server's
 * twenty-second file recovery prerequisite. Allow a bounded thirty seconds
 * forward, with a shorter retained history for a live channel. */
export function receiverBuffering(live: boolean) {
  return { bufferingGoal: 30, rebufferingGoal: 3, bufferBehind: live ? 10 : 30 }
}

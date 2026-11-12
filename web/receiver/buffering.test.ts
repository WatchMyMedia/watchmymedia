import { expect, it } from 'vitest'
import { receiverBuffering } from './buffering'

it('lets file playback satisfy recovery without delaying startup until that threshold', () => {
  const config = receiverBuffering(false)
  expect(config.bufferingGoal).toBeGreaterThan(20)
  expect(config.bufferingGoal).toBeLessThanOrEqual(30)
  expect(config.rebufferingGoal).toBeLessThan(20)
  expect(config.rebufferingGoal).toBeLessThan(config.bufferingGoal)
  expect(config.bufferBehind).toBeLessThanOrEqual(30)
})

it('keeps live history shorter while preserving a bounded forward target', () => {
  const live = receiverBuffering(true)
  expect(live.bufferBehind).toBeLessThan(receiverBuffering(false).bufferBehind)
  expect(live.bufferingGoal).toBeLessThanOrEqual(30)
  expect(live.rebufferingGoal).toBeLessThan(live.bufferingGoal)
})

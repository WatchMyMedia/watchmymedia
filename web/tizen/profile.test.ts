import { expect, it } from 'vitest'
import { filePosition, playerPosition, prototypeProfile, uhd } from './profile'

it('maps event and VOD timelines without applying the offset twice', () => {
  const event = { protocol: 'hls' as const, playlist: 'event' as const, startMs: 60_000 }
  expect(filePosition(event, 10_000)).toBe(70_000)
  expect(playerPosition(event, 70_000)).toBe(10_000)
  expect(filePosition({ ...event, playlist: 'vod' }, 70_000)).toBe(70_000)
  expect(filePosition({ ...event, protocol: 'file' }, 70_000)).toBe(70_000)
})

it('keeps HDR and surround disabled until explicitly testing those capabilities', () => {
  expect(prototypeProfile('safe').audioCodecs).toEqual(['aac'])
  expect(prototypeProfile('safe').videoCodecs).toEqual(['h264'])
  expect(prototypeProfile('hevc').hdr).toBe(false)
  expect(prototypeProfile('surround').audioCodecs).toContain('eac3')
  expect(prototypeProfile('transcode').maxHeight).toBe(480)
})

it('asks for 4K mode only for streams larger than 1080p', () => {
  expect(uhd({ video: { copy: true, toneMap: false, width: 3840, height: 2160 } })).toBe(true)
  expect(uhd({ video: { copy: true, toneMap: false, width: 1920, height: 1080 } })).toBe(false)
  expect(uhd({ video: { copy: false, toneMap: false, width: 854, height: 480 } })).toBe(false)
  expect(uhd({ video: { copy: true, toneMap: false } })).toBe(false)
})

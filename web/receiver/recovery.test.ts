import { expect, it } from 'vitest'
import { TransferMeter } from '../src/lib/play/recovery'
import { recordCastTransfer, recordLiveCastTransfer } from './recovery'

it('measures only the active session media and never a manifest or opaque transfer', () => {
  const meter = new TransferMeter()
  const base = 'https://fixture.example'
  const entry = (name: string, encodedBodySize = 1_000_000) => ({ name, startTime: 10, responseStart: 100, responseEnd: 1100, encodedBodySize, transferSize: encodedBodySize + 300 })
  recordCastTransfer(meter, base, 'new', entry(`${base}/api/playback/old/v00001.m4s?access_token=private`))
  recordCastTransfer(meter, base, 'new', entry(`${base}/api/playback/new/video.m3u8?access_token=private`))
  recordCastTransfer(meter, base, 'new', entry(`${base}/api/playback/new/v00001.m4s?access_token=private`, 0))
  expect(meter.read(1200)).toBeUndefined()
  recordCastTransfer(meter, base, 'new', entry(`${base}/api/playback/new/v00001.m4s?access_token=private`))
  expect(meter.read(1200)).toEqual({ throughputBps: 8_000_000, throughputAgeMs: 100 })
})

it('measures only the active Live TV output segments', () => {
  const meter = new TransferMeter()
  const base = 'https://fixture.example'
  const output = '/api/live-tv/sessions/output-2/hls/live.m3u8'
  const entry = (name: string, bytes = 1_000_000) => ({ name, startTime: 10, responseStart: 100, responseEnd: 1100, encodedBodySize: bytes, transferSize: bytes + 300 })
  recordLiveCastTransfer(meter, base, output, entry(`${base}/api/live-tv/sessions/output-1/hls/segment-000000001.ts?access_token=private`))
  recordLiveCastTransfer(meter, base, output, entry(`${base}/api/live-tv/sessions/output-2/hls/live.m3u8?access_token=private`))
  expect(meter.read(1200)).toBeUndefined()
  recordLiveCastTransfer(meter, base, output, entry(`${base}/api/live-tv/sessions/output-2/hls/segment-000000002.ts?access_token=private`))
  expect(meter.read(1200)).toEqual({ throughputBps: 8_000_000, throughputAgeMs: 100 })
})

it('separates slow preparation from video transfer and ignores separate audio', () => {
  const meter = new TransferMeter()
  const base = 'https://fixture.example'
  const entry = { name: `${base}/api/playback/new/v00001.m4s`, startTime: 100,
    responseStart: 5100, responseEnd: 5300, encodedBodySize: 1_000_000, transferSize: 1_000_300 }
  recordCastTransfer(meter, base, 'new', entry)
  expect(meter.read(5400)).toEqual({ throughputBps: 40_000_000, throughputAgeMs: 100 })
  recordCastTransfer(meter, base, 'new', { ...entry, name: `${base}/api/playback/new/a00001.m4s`, responseEnd: 5400, encodedBodySize: 10_000 })
  expect(meter.read(5500)).toEqual({ throughputBps: 40_000_000, throughputAgeMs: 200 })
})

it('measures either live video rung without accepting audio or another output generation', () => {
  const meter = new TransferMeter()
  const base = 'https://fixture.example'
  const output = '/api/live-tv/sessions/current/hls/live.m3u8'
  const entry = (name: string) => ({ name: `${base}/api/live-tv/sessions/${name}`, startTime: 10,
    responseStart: 100, responseEnd: 1100, encodedBodySize: 1_000_000, transferSize: 1_000_300 })
  for (const rung of ['low', 'high']) {
    meter.reset()
    recordLiveCastTransfer(meter, base, output, entry(`previous/hls/${rung}-segment-000000001.m4s`))
    recordLiveCastTransfer(meter, base, output, entry('current/hls/audio-segment-000000001.m4s'))
    recordLiveCastTransfer(meter, base, output, entry(`current/hls/${rung}-live.m3u8`))
    expect(meter.read(1200)).toBeUndefined()
    recordLiveCastTransfer(meter, base, output, entry(`current/hls/${rung}-segment-000000001.m4s?access_token=private`))
    expect(meter.read(1200)).toEqual({ throughputBps: 8_000_000, throughputAgeMs: 100 })
  }
})

it('does not use cached, opaque, missing or invalid first-byte timing for recovery', () => {
  const base = 'https://fixture.example'
  const entry = { name: `${base}/api/playback/new/v00001.m4s`, startTime: 100,
    responseStart: 200, responseEnd: 1200, encodedBodySize: 1_000_000, transferSize: 1_000_300 }
  for (const changed of [{ transferSize: 0 }, { encodedBodySize: 0 }, { responseStart: 0 }, { responseStart: 99 }, { responseStart: Number.NaN }]) {
    const meter = new TransferMeter()
    recordCastTransfer(meter, base, 'new', { ...entry, ...changed })
    expect(meter.read(1300)).toBeUndefined()
  }
})

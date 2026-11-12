import { describe, expect, it } from 'vitest'
import { TransferMeter, higherStep, switchGeneration } from './recovery'

describe('fresh transfer evidence', () => {
  it('uses completed download bytes and age', () => {
    const meter = new TransferMeter()
    expect(meter.read(1000)).toBeUndefined()
    meter.record(1_000_000, 1000, 2000)
    expect(meter.read(2500)).toEqual({ throughputBps: 8_000_000, throughputAgeMs: 500 })
    expect(meter.read(1900)).toBeUndefined()
    meter.reset()
    expect(meter.read(2600)).toBeUndefined()
  })

  it('ignores invalid or incomplete downloads', () => {
    const meter = new TransferMeter()
    meter.record(0, 100, 200)
    meter.record(1000, 100, 100)
    meter.record(Number.NaN, 100, 200)
    meter.record(1000, 0, 200)
    expect(meter.read(300)).toBeUndefined()
  })

  it('bounds the estimate from a near-instant small response', () => {
    const meter = new TransferMeter()
    meter.record(10_000, 100, 100.01)
    expect(meter.read(200)).toEqual({ throughputBps: 1_600_000, throughputAgeMs: 100 })
  })
})

it('recognizes upward generations without treating manual quality as Automatic', () => {
  expect(higherStep('720', 'original')).toBe(true)
  expect(higherStep('720', '480')).toBe(false)
  expect(switchGeneration('720', 'auto', 'original', 7)).toBe(7)
  expect(switchGeneration('720', '720', 'original', 7)).toBeUndefined()
  expect(switchGeneration('720', 'auto', '720', 7)).toBeUndefined()
  expect(switchGeneration('720', 'auto', 'original', 0)).toBeUndefined()
})

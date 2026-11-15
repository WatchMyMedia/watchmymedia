import { describe, expect, it } from 'vitest'
import type { components } from '../api/schema'
import {
  deleteThumbnailsWords, encoderChoices, encoderDescription, encoderLine, encoderStep, encoderTest, failureAdvice, fallbackBanner, gpuName, inUseWords,
  takesGpu, testFailure, testedAt, uploadSaveError, uploadSavedLine, uploadTitle, uploadWarning,
} from './transcodingSettings'

type Caps = components['schemas']['Capabilities']
const caps = (over: Partial<Caps> = {}) => ({ hwaccel: 'nvenc', encoder: 'h264_nvenc', detected: '2026-11-14T09:02:00Z', maxHeight: 2160, speed4k: 62, encoders: [], ffmpeg: '', gpuFilters: true, libass: true, overlay: true, toneMap: true, ...over }) as Caps
const checking = caps({ detected: '0001-01-01T00:00:00Z' })

describe('Choose an encoder', () => {
  it('lists what this machine can have and names the rest', () => {
    const win = encoderChoices('windows')
    expect(win.choices.map((c) => c.label)).toEqual(['Automatic', 'NVENC', 'Quick Sync', 'AMF', 'Software'])
    expect(win.notHere).toBe('Not on Windows: VideoToolbox (Macs), VAAPI (Linux).')
    expect(encoderChoices('darwin').choices.map((c) => c.backend)).toEqual(['auto', 'videotoolbox', 'software'])
    expect(encoderChoices('linux').notHere).toBe('Not on Linux: VideoToolbox (Macs), AMF (Windows).')
  })

  it('keeps the saved choice even where it can’t work, and lists all seven without a platform', () => {
    expect(encoderChoices('darwin', 'vaapi').choices.map((c) => c.backend)).toContain('vaapi')
    expect(encoderChoices(undefined).choices).toHaveLength(7)
    expect(encoderChoices(undefined).notHere).toBe('')
  })

  it('says what Automatic tries on each platform', () => {
    expect(encoderDescription('auto', 'windows')).toBe('Tries NVENC, then Quick Sync, then AMF, and uses the first that passes a test.')
    expect(encoderDescription('auto', 'darwin')).toBe('Tries VideoToolbox, and uses software if it doesn’t pass a test.')
    expect(encoderChoices('windows').choices[0].recommended).toBe(true)
  })

  it('shows the GPU only beside the encoders that take one', () => {
    expect(takesGpu('nvenc', 'windows')).toBe(true)
    expect(takesGpu('amf', 'windows')).toBe(true)
    expect(takesGpu('vaapi', 'linux')).toBe(true)
    expect(takesGpu('auto', 'windows')).toBe(false)
    expect(takesGpu('videotoolbox', 'darwin')).toBe(false)
  })

  it('names a GPU from the server’s list', () => {
    const inventory = { supported: true, adapters: [{ index: 0, name: 'GeForce RTX 3060', namespace: 'cuda' as const }], diagnostics: [] }
    expect(gpuName('nvenc', 0, 'windows', inventory)).toBe('GeForce RTX 3060')
    expect(gpuName('nvenc', -1, 'windows', inventory)).toBeUndefined()
    expect(gpuName('nvenc', 3, 'windows', inventory)).toBeUndefined()
  })
})

describe('which step an address opens', () => {
  it('starts on Choose', () => {
    expect(encoderStep(undefined, 'passed').key).toBe('choose')
    expect(encoderStep('choose', 'failed').key).toBe('choose')
  })

  it('opens Test at any time, and In use only once the test passed', () => {
    expect(encoderStep('test', 'testing').key).toBe('test')
    expect(encoderStep('in-use', 'testing').key).toBe('test')
    expect(encoderStep('in-use', 'failed').key).toBe('test')
    expect(encoderStep('in-use', 'passed').key).toBe('in-use')
  })

  it('reads the test from the capabilities', () => {
    expect(encoderTest('nvenc', checking)).toBe('testing')
    expect(encoderTest('nvenc', undefined)).toBe('testing')
    expect(encoderTest('nvenc', caps())).toBe('passed')
    expect(encoderTest('nvenc', caps({ hwaccel: 'software' }))).toBe('failed')
    expect(encoderTest('auto', caps({ hwaccel: 'software' }))).toBe('passed')
  })
})

describe('a failed test', () => {
  it('says the GPU is missing when the device check failed', () => {
    const d = [{ encoder: 'nvenc', stage: 'device', ok: false, detail: 'no such adapter' }]
    expect(testFailure('nvenc', 1, d)).toEqual({ kind: 'gpu-missing', detail: 'no such adapter' })
    expect(testFailure('nvenc', -1, d).kind).toBe('failed')
  })

  it('quotes ffmpeg for the chosen encoder only', () => {
    const d = [
      { encoder: 'h264_qsv', stage: 'encode', ok: false, detail: 'qsv broke' },
      { encoder: 'h264_nvenc', stage: 'encode', ok: false, detail: 'Cannot load nvcuda.dll' },
    ]
    expect(testFailure('nvenc', undefined, d)).toEqual({ kind: 'failed', detail: 'Cannot load nvcuda.dll' })
    expect(testFailure('amf', undefined, d)).toEqual({ kind: 'failed', detail: undefined })
    expect(failureAdvice('nvenc')).toContain('NVIDIA driver')
  })

  it('is a banner on the page that says what the server uses instead', () => {
    expect(fallbackBanner('nvenc', caps({ hwaccel: 'software' }))).toEqual({
      title: 'NVENC didn’t pass its test, so the server is using software',
      body: 'Software converting is slow and limited to 1080p. Check the GPU’s drivers, then test again, or choose another encoder.',
    })
  })
})

describe('the encoder in use', () => {
  it('leads the page with how it was chosen and how far it converts', () => {
    expect(encoderLine('auto', caps(), 'today 9:02 AM')).toBe('Chosen automatically. New plays can convert up to 4K. Tested today 9:02 AM.')
    expect(encoderLine('nvenc', caps({ maxHeight: 1080 }), 'today 9:02 AM')).toContain('up to 1080p')
  })

  it('says when it was tested', () => {
    const now = new Date(2026, 10, 14, 12, 0)
    expect(testedAt(new Date(2026, 10, 14, 9, 2).toISOString(), now)).toBe('today 9:02 AM')
    expect(testedAt(new Date(2026, 10, 13, 18, 10).toISOString(), now)).toBe('yesterday 6:10 PM')
  })

  it('finishes with what changed and what happens next', () => {
    const w = inUseWords(caps(), 'GeForce RTX 3060')
    expect(w.title).toBe('NVENC is in use')
    expect(w.lede).toBe('GeForce RTX 3060 · passed its test at 62 frames a second in 4K')
    expect(w.changed[1]).toBe('4K converting is on, because the test ran at 48 frames a second or better.')
    expect(w.next).toHaveLength(3)
    const soft = inUseWords(caps({ hwaccel: 'software', speed4k: 0, maxHeight: 1080 }))
    expect(soft.lede).toBe('Passed its test')
    expect(soft.next).toHaveLength(2)
  })
})

describe('remote upload limit', () => {
  it('leads with the limit and whether it is enforced', () => {
    expect(uploadTitle({ configuredBps: 40_000_000, enforcing: true })).toBe('40 Mbps, enforced')
    expect(uploadTitle({ configuredBps: 40_000_000, enforcing: false })).toBe('40 Mbps, measured only')
    expect(uploadTitle({ configuredBps: 0, enforcing: false })).toBe('No limit')
  })

  it('warns when streams away from home are close to a new limit', () => {
    const u = { activeViews: 3, deliveredBps: 31_000_000, reservedBps: 0 }
    expect(uploadWarning(u, 35_000_000, true)).toEqual({ title: '3 streams are going to people away from home, using 31 Mbps', body: 'At 35 Mbps their quality may step down.' })
    expect(uploadWarning(u, 35_000_000, false)).toBeNull()
    expect(uploadWarning(u, 100_000_000, true)).toBeNull()
    expect(uploadWarning({ ...u, activeViews: 0 }, 35_000_000, true)).toBeNull()
  })

  it('tells a change made elsewhere from other refusals', () => {
    const words = (e: unknown) => (e as Error).message
    expect(uploadSaveError(Object.assign(new Error('stale'), { status: 409 }), words)).toEqual({ conflict: true })
    expect(uploadSaveError(Object.assign(new Error('Too big.'), { status: 422 }), words)).toEqual({ conflict: false, message: 'Too big.' })
  })

  it('ends with a banner that says what changed', () => {
    expect(uploadSavedLine(35_000_000, true)).toBe('Saved. Remote streams now share 35 Mbps, paced to fit, from the next segment on.')
    expect(uploadSavedLine(35_000_000, false)).toContain('doesn’t limit them')
  })
})

describe('Delete thumbnails', () => {
  it('says what is freed, and that they come back while the switch is on', () => {
    expect(deleteThumbnailsWords(1.8 * 1024 ** 3, true)).toEqual({
      lede: 'Frees 1.8 GB. Scrubbing shows no pictures until they’re made again.',
      note: 'Make seek thumbnails is on, so making them starts again straight away and takes a few hours. To keep the space, turn that switch off first.',
    })
    expect(deleteThumbnailsWords(1024, false).note).toBeUndefined()
  })
})

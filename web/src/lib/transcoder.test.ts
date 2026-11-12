import { describe, expect, it } from 'vitest'
import { adapterChoices, adapterChoiceValue, adapterDiscoveryDetail, adapterIndex, adapterIndexHint, adapterNamespace, adapterPreference, hardwareState, libraryPathExample, softwareEncoderNote } from './transcoder'
import type { components } from '../api/schema'

const caps = { hwaccel: 'software', detected: '2026-11-03T12:00:00Z' } as components['schemas']['Capabilities']

describe('server-specific hardware preferences', () => {
  it('keeps CUDA, Windows DirectX and Linux render node namespaces separate', () => {
    expect(adapterNamespace('nvenc', 'linux')).toBe('CUDA')
    expect(adapterNamespace('qsv', 'windows')).toBe('DirectX')
    expect(adapterNamespace('amf', 'windows')).toBe('DirectX')
    expect(adapterNamespace('qsv', 'linux')).toBe('render node')
    expect(adapterNamespace('vaapi', 'linux')).toBe('render node')
    for (const backend of ['auto', 'software', 'videotoolbox', 'amf']) {
      expect(adapterNamespace(backend, 'linux')).toBeUndefined()
    }
    expect(adapterNamespace('vaapi', 'windows')).toBeUndefined()
    expect(adapterNamespace('qsv')).toBeUndefined()
    expect(adapterIndexHint('render node')).toContain('/dev/dri/renderD128')
    expect(adapterIndexHint('CUDA')).toContain('CUDA adapter index')
    expect(adapterPreference('vaapi', '1')).toEqual({ hwaccel: 'vaapi', hwDevice: 1 })
  })
  it('words the software-encoder note for Unraid, setup and everywhere else', () => {
    expect(softwareEncoderNote(false, 'unraid')).toContain('/dev/dri device')
    expect(softwareEncoderNote(true, 'unraid')).toContain('Nvidia Driver plugin')
    expect(softwareEncoderNote(true)).toContain('Settings › Transcoding')
    expect(softwareEncoderNote(false)).toContain('Docker Desktop on macOS')
  })
  it('preserves adapter zero, supports default reset and rejects ambiguous numbers', () => {
    expect(adapterIndex('0')).toBe(0)
    expect(adapterIndex(' 2 ')).toBe(2)
    expect(adapterIndex('')).toBe(-1)
    expect(adapterIndex('2147483647')).toBe(2147483647)
    for (const invalid of ['-1', '-2', '+1', '1.5', '1e2', 'NaN', 'Infinity', '2147483648']) {
      expect(adapterIndex(invalid)).toBeUndefined()
    }
  })
  it('does not call software selected by Auto or a pending probe a failed GPU', () => {
    expect(hardwareState('auto', caps)).toBe('ready')
    expect(hardwareState('software', caps)).toBe('ready')
    expect(hardwareState('nvenc', caps)).toBe('fallback')
    expect(hardwareState('nvenc', { ...caps, detected: '0001-01-01T00:00:00Z' })).toBe('checking')
    expect(hardwareState('nvenc')).toBe('unknown')
  })
  it('uses the server platform for folders, retaining generic paths when absent', () => {
    expect(libraryPathExample('movie', 'windows')).toBe('D:\\Movies')
    expect(libraryPathExample('tv', 'windows')).toBe('D:\\TV')
    expect(libraryPathExample('movie', 'darwin')).toBe('/Volumes/Media/Movies')
    expect(libraryPathExample('tv', 'linux')).toBe('/tv')
    expect(libraryPathExample('movie')).toBe('/movies')
  })
})

describe('named Windows adapter choices', () => {
  const inventory: components['schemas']['Inventory'] = {
    supported: true,
    adapters: [
      { namespace: 'directx', index: 0, name: 'Fictional integrated', vendorId: 0x8086 },
      { namespace: 'directx', index: 3, name: 'Fictional discrete', vendorId: 0x1002 },
      { namespace: 'directx', index: 1, name: 'Fictional CUDA card in DXGI', vendorId: 0x10de },
      { namespace: 'cuda', index: 2, name: 'Fictional second CUDA' },
      { namespace: 'cuda', index: 0, name: 'Fictional first CUDA' },
    ],
    diagnostics: [{ namespace: 'cuda', ok: false, detail: 'CUDA driver unavailable' }, { namespace: 'directx', ok: true }],
  }
  it('keeps native indices and never joins CUDA names to DirectX indices', () => {
    expect(adapterChoices('nvenc', 'windows', inventory).map((a) => a.index)).toEqual([0, 2])
    expect(adapterChoices('qsv', 'windows', inventory).map((a) => a.index)).toEqual([0])
    expect(adapterChoices('amf', 'windows', inventory).map((a) => a.index)).toEqual([3])
    expect(inventory.adapters[0].namespace).toBe('directx')
  })
  it('retains manual/default behavior for old, unsupported or empty discovery', () => {
    expect(adapterChoices('nvenc', 'windows')).toEqual([])
    expect(adapterChoices('nvenc', 'windows', { ...inventory, supported: false })).toEqual([])
    expect(adapterChoices('auto', 'windows', inventory)).toEqual([])
    expect(adapterChoices('qsv', 'linux', inventory)).toEqual([])
    expect(adapterChoices('amf', 'windows', { ...inventory, adapters: [] })).toEqual([])
  })
  it('does not silently replace unknown or manual indices with the first GPU', () => {
    const choices = adapterChoices('nvenc', 'windows', inventory)
    expect(adapterChoiceValue('', choices, false)).toBe('')
    expect(adapterChoiceValue('0', choices, false)).toBe('0')
    expect(adapterChoiceValue('2', choices, false)).toBe('2')
    expect(adapterChoiceValue('42', choices, false)).toBe('manual')
    expect(adapterChoiceValue('2', choices, true)).toBe('manual')
    expect(adapterChoiceValue('1e2', choices, false)).toBe('manual')
  })
  it('binds Apply to the backend the user selected, including default and zero', () => {
    expect(adapterPreference('nvenc', '0')).toEqual({ hwaccel: 'nvenc', hwDevice: 0 })
    expect(adapterPreference('amf', '3')).toEqual({ hwaccel: 'amf', hwDevice: 3 })
    expect(adapterPreference('qsv', '')).toEqual({ hwaccel: 'qsv', hwDevice: -1 })
    expect(adapterPreference('auto', '0')).toBeUndefined()
    expect(adapterPreference('nvenc', '2147483648')).toBeUndefined()
  })
  it('shows only this backend namespace’s discovery failure', () => {
    expect(adapterDiscoveryDetail('nvenc', 'windows', inventory)).toBe('CUDA driver unavailable')
    expect(adapterDiscoveryDetail('qsv', 'windows', inventory)).toBeUndefined()
    expect(adapterDiscoveryDetail('auto', 'windows', inventory)).toBeUndefined()
    expect(adapterDiscoveryDetail('nvenc', 'windows')).toBeUndefined()
    expect(adapterDiscoveryDetail('nvenc', 'windows', { ...inventory, diagnostics: [{ namespace: 'cuda', ok: false }] })).toBe('Device-name discovery did not complete.')
  })
})

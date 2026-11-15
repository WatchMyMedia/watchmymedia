import { describe, expect, it } from 'vitest'
import { isShortening, keepLabel, minPlayOptions, retentionLine, retentionOptions, retentionSaved, shorteningWarning } from './historySettings'

describe('Watch history', () => {
  it('offers the usual choices, and keeps a value set elsewhere', () => {
    expect(minPlayOptions(120).find((o) => o.value === 120)?.label).toBe('2 minutes')
    expect(minPlayOptions(45).map((o) => o.value)).toEqual([0, 30, 45, 60, 120, 300, 600])
    expect(retentionOptions(0)[0]).toEqual({ value: 0, label: 'Forever' })
    expect(retentionOptions(400).find((o) => o.value === 400)?.label).toBe('400 days')
  })

  it('warns only when a change removes plays', () => {
    expect(isShortening(0, 365)).toBe(true)
    expect(isShortening(730, 365)).toBe(true)
    expect(isShortening(365, 730)).toBe(false)
    expect(isShortening(365, 0)).toBe(false)
  })

  it('says how many plays a shorter limit removes', () => {
    expect(shorteningWarning(365, 4200)).toBe('Choosing 1 year removes about 4,200 plays older than that. This can’t be undone.')
    expect(shorteningWarning(30, undefined)).toBe('Choosing 30 days removes the plays older than 30 days. This can’t be undone.')
    expect(keepLabel(0)).toBe('Keep forever')
    expect(keepLabel(730)).toBe('Keep 2 years')
  })

  it('confirms what a saved limit does', () => {
    expect(retentionSaved(365)).toBe('Plays older than a year are removed, starting within 6 hours.')
    expect(retentionSaved(0)).toBe('Plays are kept forever.')
    expect(retentionLine(90)).toBe('Plays older than 90 days are removed.')
  })
})

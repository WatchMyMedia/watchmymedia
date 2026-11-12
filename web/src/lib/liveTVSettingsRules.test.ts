import { describe, expect, it } from 'vitest'
import { liveGuideMatch, liveGuideStatus, liveTVChannelSetting, liveTVSetupPlan } from './liveTVSettingsRules'

describe('Live TV setup choices', () => {
  it('requires a saved tuner or a library before continuing', () => {
    expect(liveTVSetupPlan(0, false).canContinue).toBe(false)
    expect(liveTVSetupPlan(1, false).canContinue).toBe(true)
    expect(liveTVSetupPlan(0, true).canContinue).toBe(true)
  })

  it('skips file metadata and subtitles for a tuner-only server', () => {
    const plan = liveTVSetupPlan(0, true)
    expect(plan.steps.map(([step]) => step)).toEqual(['sources', 'transcode', 'done'])
    expect(plan.nextStep).toBe('transcode')
    expect(plan.backFromTranscode).toBe('sources')
    expect(plan.destination).toBe('/live-tv')
  })

  it('uses the persistent completion flag without requiring an enabled or present source', () => {
    expect(liveTVSetupPlan(0, true)).toMatchObject({ canContinue: true, destination: '/live-tv' })
  })

  it.each([false, true])('keeps the library setup path when Live TV completion is %s', (setupComplete) => {
    const plan = liveTVSetupPlan(2, setupComplete)
    expect(plan.steps.map(([step]) => step)).toEqual(['sources', 'tmdb', 'subtitles', 'transcode', 'done'])
    expect(plan.nextStep).toBe('tmdb')
    expect(plan.backFromTranscode).toBe('subtitles')
    expect(plan.destination).toBe('/')
  })
})

describe('Live TV channel choices', () => {
  it('does not offer protected channels even if their saved flag is enabled', () => {
    expect(liveTVChannelSetting({ available: false, enabled: true, reason: 'protected_channel' })).toEqual({
      checked: false, locked: true, status: 'Copy-protected · can’t be played',
    })
  })

  it.each([
    ['unavailable', 'Missing from the last refresh'],
    ['source_disabled', 'Tuner is disabled'],
  ])('preserves the choice for %s channels', (reason, status) => {
    expect(liveTVChannelSetting({ available: false, enabled: true, reason })).toEqual({ checked: true, locked: false, status })
    expect(liveTVChannelSetting({ available: false, enabled: false, reason }).checked).toBe(false)
  })

  it.each([undefined, 'new_server_reason'])('shows an unavailable state for reason %s', (reason) => {
    expect(liveTVChannelSetting({ available: false, enabled: true, reason }).status).toBe('Unavailable')
  })

  it.each([[true, 'Shown'], [false, 'Hidden']] as const)('labels a playable channel with enabled=%s', (enabled, status) => {
    expect(liveTVChannelSetting({ available: true, enabled })).toEqual({ checked: enabled, locked: false, status })
  })
})

describe('Live TV listings settings', () => {
  it('names every status the server reports, and keeps a new one readable', () => {
    for (const state of ['off', 'waiting', 'fetching', 'ok', 'stale', 'firmware', 'refused', 'unavailable', 'failed', 'no_tuner']) {
      expect(liveGuideStatus(state).label).not.toBe(state)
    }
    expect(liveGuideStatus('ok').tone).toBe('ok')
    expect(liveGuideStatus('firmware').tone).toBe('warn')
    expect(liveGuideStatus('fetching').busy).toBe(true)
    expect(liveGuideStatus('later_state')).toEqual({ label: 'later_state', tone: 'warn', busy: false })
  })

  it('flags names that differ and locks channels the listings don’t cover', () => {
    expect(liveGuideMatch('differs')).toMatchObject({ warn: true, locked: false })
    expect(liveGuideMatch('missing')).toMatchObject({ warn: false, locked: true })
    expect(liveGuideMatch('pending').locked).toBe(true)
    expect(liveGuideMatch('name').locked).toBe(false)
    expect(liveGuideMatch('later').label).toBe('later')
  })
})

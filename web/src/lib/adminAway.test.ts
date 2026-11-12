import { expect, it } from 'vitest'
import { adminAwayWording } from './adminAway'

it('explains how each admin limit can be resolved', () => {
  expect(adminAwayWording('network')).toEqual({
    short: 'Server settings are off away from home.',
    long: 'Server settings are off away from your home network or tailnet. An admin there can turn them on in Settings › Security.',
  })
  expect(adminAwayWording('https', false)).toEqual({
    short: 'Away from home, server settings open only over HTTPS.',
    long: 'Away from home, server settings open only over HTTPS. Open this server at its https:// address, or from your home network or tailnet.',
  })
})

it('says the proxy is the problem when the page itself is HTTPS', () => {
  const { short, long } = adminAwayWording('https', true)
  expect(short).toBe('The server can’t tell this visit is over HTTPS.')
  expect(long).toContain('trusted_proxies')
  expect(adminAwayWording('network', true).short).toBe('Server settings are off away from home.')
})

import { describe, expect, it } from 'vitest'
import configXml from './public/config.xml?raw'
import { TV_APP_VERSION } from '../../session/device'
import { platform } from '.'
import { REGISTERED_KEYS } from './keys'

describe('the Samsung TV', () => {
  it('asks Tizen for the media, channel and number keys', () => {
    for (const name of ['MediaPlayPause', 'ChannelUp', 'ChannelDown', '0', '9', 'Info']) expect(REGISTERED_KEYS).toContain(name)
  })

  it('keeps config.xml at the version the app reports', () => {
    expect(configXml).toContain(`version="${TV_APP_VERSION}"`)
  })

  it('keeps the store identity in config.xml', () => {
    expect(configXml).toContain('<tizen:application id="cPFf0iV3Li.WatchMyMedia" package="cPFf0iV3Li"')
    expect(configXml).toContain('id="https://watchmy.media/tizen"')
  })

  it('keeps the storage names that hold every Samsung TV’s servers and sign-ins', () => {
    expect(platform.storage).toEqual({
      servers: 'watchmymedia.tizen.servers.v1',
      device: 'watchmymedia.tizen.device',
      copies: 'watchmymedia.tizen.copies.v1',
      recentSearches: 'watchmymedia.tizen.recentSearches.v1',
      remindersSeen: 'wmm.tizen.reminders.seen',
    })
  })

  it('names itself as it always has', () => {
    expect(platform.id).toBe('tizen')
    expect(platform.words).toMatchObject({ tv: 'Samsung TV', product: 'WatchMyMedia for Samsung TV', back: 'Return' })
  })

  it('keeps its remote as it was: number keys, no pointer, every repeat, and its own Leave question at Home', () => {
    expect(platform).toMatchObject({ numberKeys: true, pointer: false, repeatMs: 0 })
    expect(platform.backAtHome()).toBe(false)
  })
})

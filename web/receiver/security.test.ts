import { describe, expect, it } from 'vitest'
import { publicCastMessage } from './security'

describe('Cast broadcasts', () => {
  it('keeps playback metadata while stripping credentials from media, queues and artwork', () => {
    const secret = 'c1.synthetic-secret'
    const media = { contentId: 'wmm:file:4', contentUrl: `https://example.test/api/files/4/stream?access_token=${secret}`,
      customData: { token: secret, server: { id: 'public-id' } },
      metadata: { title: 'Fiction', images: [{ url: `https://example.test/a?access_token=${secret}` }] },
      tracks: [{ trackId: 3, trackContentId: `https://example.test/s?access_token=${secret}` }] }
    const input = { media, items: [{ media, credentials: secret }], currentTime: 42, playerState: 'PLAYING',
      nextEpisode: { fileId: 5, title: 'Next', stillUrl: `https://example.test/i?access_token=${secret}` } }
    const output = publicCastMessage(input)
    expect(JSON.stringify(output)).not.toContain(secret)
    expect(output.media.customData).toBeUndefined()
    expect(output.media.metadata.title).toBe('Fiction')
    expect(output.currentTime).toBe(42)
    expect(output.media.tracks[0].trackId).toBe(3)
    expect(output.nextEpisode.stillUrl).toBeUndefined()
    expect(input.media.customData.token).toBe(secret)
    expect(input.media.contentUrl).toContain(secret)
  })
})

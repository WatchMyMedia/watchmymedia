import { afterEach, describe, expect, it, vi } from 'vitest'
import { detectNativeProfile, detectProfile } from './capabilities'

// A browser as detectProfile sees it: whether the screen is HDR, the codecs
// Media Source Extensions and <video> take, and what MediaCapabilities says
// of a transfer function.
function browser(o: { hdrScreen: boolean; takes: (type: string) => boolean; transfers?: string[] }) {
  vi.stubGlobal('window', { matchMedia: (q: string) => ({ matches: o.hdrScreen && q.includes('dynamic-range: high') }) })
  vi.stubGlobal('document', { createElement: () => ({ canPlayType: (type: string) => (o.takes(type) ? 'probably' : '') }) })
  vi.stubGlobal('MediaSource', { isTypeSupported: o.takes })
  vi.stubGlobal('navigator', {
    mediaCapabilities: {
      decodingInfo: async (c: { video: { contentType: string; transferFunction?: string } }) => ({
        supported: o.takes(c.video.contentType) && (!c.video.transferFunction || (o.transfers ?? ['pq', 'hlg']).includes(c.video.transferFunction)),
      }),
    },
  })
}

const chrome = (type: string) => !/dvh1|ac-3|ec-3/.test(type)
const safari = (type: string) => !/webm|opus|vorbis/.test(type)

describe('detectProfile HDR', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('offers 10-bit HEVC on an SDR screen and says it shows no HDR', async () => {
    browser({ hdrScreen: false, takes: chrome })
    const p = await detectProfile()
    expect(p.videoCodecs).toContain('hevc10')
    expect(p.hdr).toBe(false)
    expect(p.hdrTypes).toEqual([])
  })

  it('lists HDR10 and HLG on an HDR screen, without Dolby Vision the browser does not decode', async () => {
    browser({ hdrScreen: true, takes: chrome })
    const p = await detectProfile()
    expect(p.hdrTypes).toEqual(['hdr10', 'hlg'])
    expect(p.videoCodecs?.some((c) => c.startsWith('dovi'))).toBe(false)
  })

  it('adds the Dolby Vision keys where the browser decodes them on an HDR screen', async () => {
    browser({ hdrScreen: true, takes: safari })
    const p = await detectProfile()
    expect(p.hdrTypes).toEqual(['hdr10', 'hlg', 'dovi'])
    expect(p.videoCodecs).toEqual(expect.arrayContaining(['hevc10', 'dovi5', 'dovi8']))
  })

  it('sends no Dolby Vision to an SDR screen, however it decodes', async () => {
    browser({ hdrScreen: false, takes: safari })
    const p = await detectProfile()
    expect(p.hdrTypes).toEqual([])
    expect(p.videoCodecs?.some((c) => c.startsWith('dovi'))).toBe(false)
  })

  it('leaves out a transfer function the browser cannot show on an HDR screen', async () => {
    browser({ hdrScreen: true, takes: chrome, transfers: [] })
    expect((await detectProfile()).hdrTypes).toEqual([])
    browser({ hdrScreen: true, takes: chrome, transfers: ['pq'] })
    expect((await detectProfile()).hdrTypes).toEqual(['hdr10'])
  })
})

describe('detectNativeProfile HDR', () => {
  afterEach(() => vi.unstubAllGlobals())

  it("follows the screen, with Dolby Vision where Safari's own player decodes it", () => {
    browser({ hdrScreen: true, takes: safari })
    const p = detectNativeProfile()
    expect(p.hdrTypes).toEqual(['hdr10', 'hlg', 'dovi'])
    expect(p.videoCodecs).toEqual(expect.arrayContaining(['dovi5', 'dovi8']))
    browser({ hdrScreen: false, takes: safari })
    expect(detectNativeProfile()).toMatchObject({ hdr: false, hdrTypes: [] })
  })
})

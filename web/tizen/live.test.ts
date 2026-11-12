import { expect, it } from 'vitest'
import { pair } from './session'
import { mediaUrl, result, tvClient } from './server'
import { prototypeProfile, type ProfileId } from './profile'

// Opt-in: this must never find/sign into a real server. The dedicated synthetic
// fixture identity is checked before any credentials or writes are sent.
const base = import.meta.env.WMM_TIZEN_TEST_SERVER as string | undefined

it.skipIf(!base)('pairs, streams direct and HLS media, saves resume, and revokes the device on the isolated fixture', async () => {
  const url = new URL(base!)
  expect(['127.0.0.1', 'localhost']).toContain(url.hostname)
  expect(url.port).toBe('27686')
  const anonymous = tvClient(base!)
  const info = result(await anonymous.GET('/api/server'))
  expect(info.name).toBe('Samsung prototype fixture')
  const login = result(await anonymous.POST('/api/auth/login', { body: {
    name: 'viewer', password: 'fixture-viewer-1', client: { deviceId: 'samsung-fixture-approver', platform: 'test' },
  } }))
  expect(login.token).toBeTruthy()
  const approver = tvClient(base!, login.token)
  const abort = new AbortController()
  let approval: Promise<unknown> = Promise.resolve()
  const linked = await pair(base!, { deviceId: 'samsung-fixture-live-test', platform: 'tizen' }, abort.signal, (code) => {
    approval = approver.POST('/api/auth/pair/{code}/approve', { params: { path: { code: code.code } } }).then(result)
  })
  await approval
  const api = linked.api
  const sessions: string[] = []
  try {
    const libraries = result(await api.GET('/api/libraries'))
    const files = result(await api.GET('/api/libraries/{id}/files', { params: { path: { id: libraries.items[0].id } } })).items
    expect(files).toHaveLength(3)
    const token = result(await api.POST('/api/auth/media-token'))
    const hevc = files.find((file) => file.videoCodec === 'hevc')!
    expect(hevc).toBeDefined()
    const cases = [
      ...files.map((file) => ({ file, profile: 'safe' as ProfileId,
        mode: file.videoCodec === 'hevc' ? 'transcode' : file.relativePath.endsWith('.mp4') ? 'direct' : 'remux' })),
      { file: hevc, profile: 'hevc' as ProfileId, mode: 'audio' },
    ]
    for (const { file, profile, mode } of cases) {
      const decision = result(await api.POST('/api/files/{id}/playback', { params: { path: { id: file.id } }, body: {
        profile: prototypeProfile(profile), playlist: 'vod', quality: 'original', client: { deviceId: 'samsung-fixture-live-test', platform: 'tizen' },
      } }))
      if (decision.sessionId) sessions.push(decision.sessionId)
      expect(decision.mode).toBe(mode)
      const stream = mediaUrl(base!, decision.url, token.token)
      if (decision.protocol === 'file') {
        const data = await fetch(stream, { headers: { Range: 'bytes=0-1023' }, redirect: 'error' })
        expect(data.status).toBe(206)
        expect((await data.arrayBuffer()).byteLength).toBe(1024)
      } else {
        const masterResponse = await fetch(stream, { redirect: 'error' })
        expect(masterResponse.status).toBe(200)
        let playlist = await masterResponse.text()
        let playlistURL = stream
        if (playlist.includes('#EXT-X-STREAM-INF')) {
          const child = playlist.split('\n').find((line) => line.trim() && !line.startsWith('#'))!
          playlistURL = new URL(child, stream).href
          expect(new URL(playlistURL).searchParams.get('access_token')).toBe(token.token)
          playlist = await (await fetch(playlistURL, { redirect: 'error' })).text()
        }
        const segment = playlist.split('\n').find((line) => line.trim() && !line.startsWith('#'))!
        const segmentURL = new URL(segment, playlistURL)
        expect(segmentURL.searchParams.get('access_token')).toBe(token.token)
        const segmentResponse = await fetch(segmentURL, { redirect: 'error' })
        expect(segmentResponse.status).toBe(200)
        expect((await segmentResponse.arrayBuffer()).byteLength).toBeGreaterThan(100)
      }
      result(await api.PUT('/api/files/{id}/progress', { params: { path: { id: file.id } }, body: {
        playId: decision.playId, positionMs: 20_000, durationMs: decision.durationMs, state: 'stopped',
      } }))
      const resumed = result(await api.POST('/api/files/{id}/playback', { params: { path: { id: file.id } }, body: {
        profile: prototypeProfile(profile), playlist: 'vod', client: { deviceId: 'samsung-fixture-live-test', platform: 'tizen' },
      } }))
      if (resumed.sessionId) sessions.push(resumed.sessionId)
      expect(resumed.resumeMs).toBe(20_000)
      result(await api.PUT('/api/files/{id}/progress', { params: { path: { id: file.id } }, body: {
        playId: resumed.playId, positionMs: 20_000, durationMs: resumed.durationMs, state: 'stopped',
      } }))
    }
    result(await approver.DELETE('/api/auth/sessions/{id}', { params: { path: { id: linked.saved.sessionId } } }))
    expect((await api.GET('/api/auth/me')).response.status).toBe(401)
    const stream = mediaUrl(base!, `/api/files/${files[0].id}/stream`, token.token)
    expect((await fetch(stream, { headers: { Range: 'bytes=0-1' }, redirect: 'error' })).status).toBe(401)
  } finally {
    for (const session of sessions) await approver.DELETE('/api/playback/{session}', { params: { path: { session } } })
    await approver.DELETE('/api/auth/sessions/{id}', { params: { path: { id: linked.saved.sessionId } } })
    await approver.POST('/api/auth/logout')
  }
}, 90_000)

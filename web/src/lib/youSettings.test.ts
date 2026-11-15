import { describe, expect, it } from 'vitest'
import { ApiError } from '../api/client'
import type { components } from '../api/schema'
import {
  accountLede, accountLine, andList, approveError, automaticReason, cancelError, cancelLine, changePasswordLabel, choiceSaveError, codeHint,
  downloadsLine, fallbackCodes, fallbackRefusal, fallbackTags, languageLede, languageSaveError, linkHref, linkRedirect, linkStep, lookupError,
  openSubtitlesError, passwordChangeError, passwordChangedBanner, passwordFieldErrors, playbackLede, requestsOff, requestsSummary, serviceLede,
  serviceState, sessionLine, signOutError, signOutTitle, skipModeOf, skipSavedLine, versionChanged,
} from './youSettings'

type Session = components['schemas']['Session']
type SubtitleStatus = components['schemas']['SubtitleStatus']

const refused = (status: number, detail = 'refused') => new ApiError(new Response(null, { status }), { detail })
const offline = new TypeError('Failed to fetch')

describe('Playback', () => {
  it('says how the parts skip in a line, grouping parts that share a mode', () => {
    expect(playbackLede({ skip: { intro: 'ask', recap: 'ask', credits: 'ask', preview: 'none', commercial: 'ask' }, autoplayNext: true }))
      .toBe('Intros, recaps, credits and ads show a Skip button; previews play through. The next episode plays by itself.')
    expect(playbackLede({ skip: { intro: 'auto', credits: 'auto-delay' }, autoplayNext: false }))
      .toBe('Intros skip right away; recaps and ads show a Skip button; credits skip after 3 seconds; previews play through. The next episode waits for you.')
  })
  it('uses the server defaults for parts with no saved mode', () => {
    expect(skipModeOf({}, 'preview')).toBe('none')
    expect(skipModeOf(undefined, 'intro')).toBe('ask')
    expect(skipModeOf({ intro: 'auto' }, 'intro')).toBe('auto')
  })
  it('says what a saved choice did', () => {
    expect(skipSavedLine('credits', 'auto-delay')).toBe('Credits now skip after 3 seconds, on every device.')
    expect(skipSavedLine('commercial', 'auto')).toBe('Ads now skip right away, on every device.')
  })
  it('says to reload when the server refuses a value it does not know, and to check the connection otherwise', () => {
    expect(choiceSaveError(refused(422), 'Do nothing')).toBe('The server didn’t accept this choice, so it’s still Do nothing. This page may be older than the server: reload it and choose again.')
    expect(choiceSaveError(offline, 'Show a Skip button')).toBe('Couldn’t save, so it’s still Show a Skip button. Check your connection and choose again.')
    expect(choiceSaveError(refused(500), 'Show a Skip button')).toContain('Check your connection')
  })
})

describe('Audio & subtitles, yours', () => {
  const draft = { audio: ['en', 'ur'], subtitles: ['en'], choice: 'foreign' as const, automaticDownload: false }
  it('says the saved state in a line, and the first visit as the boards do', () => {
    expect(languageLede(draft)).toBe('Videos start in English, then Urdu. Subtitles start when the audio isn’t in your languages.')
    expect(languageLede({ audio: [], subtitles: [], choice: 'forced', automaticDownload: false }))
      .toBe('Before you choose: videos play the file’s default audio, and subtitles start only when the file marks them as forced.')
    expect(languageLede({ ...draft, audio: [], choice: 'on' })).toBe('Videos play the file’s default audio. Subtitles always start, in your first language that has one.')
  })
  it('says why Automatic download cannot be ticked', () => {
    expect(automaticReason(draft)).toBeNull()
    expect(automaticReason({ ...draft, choice: 'forced', subtitles: [] })).toBe('Off because subtitles don’t start by themselves. Choose On or When the audio isn’t in my languages, and add a subtitle language.')
    expect(automaticReason({ ...draft, choice: 'off' })).toBe('Off because subtitles don’t start by themselves. Choose On or When the audio isn’t in my languages.')
    expect(automaticReason({ ...draft, subtitles: [] })).toBe('Off because there’s no subtitle language to look for. Add a subtitle language.')
  })
  it('keeps the draft when a save fails, and says so', () => {
    expect(languageSaveError(offline, '')).toEqual({ title: 'Couldn’t save your choices', body: 'The server didn’t answer. Your changes are still here and nothing was saved. Check your connection and choose Save again.' })
    expect(languageSaveError(refused(422), 'Unknown language.').body).toBe('Unknown language. Your changes are still here and nothing was saved.')
  })
})

describe('Audio & subtitles, the server subtitle service', () => {
  const account = { allowedDownloads: 20, remainingDownloads: 14, level: 'Free', vip: false }
  const base: SubtitleStatus = { available: true, downloads: 'everyone', languages: ['en', 'es'] }
  it('names the block’s state', () => {
    expect(serviceState({ ...base, available: false })).toBe('unavailable')
    expect(serviceState(base)).toBe('signed-out')
    expect(serviceState({ ...base, username: 'rivera-home' })).toBe('failed')
    expect(serviceState({ ...base, username: 'rivera-home', account })).toBe('signed-in')
  })
  it('says the allowance in words', () => {
    expect(serviceLede({ ...base, username: 'rivera-home', account })).toBe('For everyone on this server. Only admins see this. Online subtitles come from OpenSubtitles.com; 14 of 20 downloads are left today.')
    expect(accountLine({ ...base, username: 'rivera-home', account })).toBe('Signed in as rivera-home · Free account · 14 of 20 downloads left today')
    expect(accountLine(base)).toBe('Not signed in. The server gets 5 downloads a day.')
    expect(accountLine({ ...base, username: 'rivera-home', account: { ...account, remainingDownloads: -1 } })).toContain('20 downloads a day')
  })
  it('says who can download, and that a change did it', () => {
    expect(downloadsLine('admins', true)).toBe('Only admins can pick an online subtitle now. Searching is free either way.')
    expect(downloadsLine('everyone')).toBe('Everyone on this server can pick an online subtitle. Searching is free either way.')
  })
  it('words a refused login (422), an unreachable OpenSubtitles (502) and an unreachable server', () => {
    expect(openSubtitlesError(refused(422), '').title).toBe('OpenSubtitles didn’t accept this username and password')
    expect(openSubtitlesError(refused(422), '').body).toContain('the server keeps the login it had')
    expect(openSubtitlesError(refused(502), '').title).toBe('Couldn’t reach OpenSubtitles, so nothing was saved')
    expect(openSubtitlesError(offline, '').title).toBe('Couldn’t reach the server, so nothing was saved')
    expect(openSubtitlesError(refused(500), 'Disk full.')).toEqual({ title: 'Nothing was saved', body: 'Disk full.' })
  })
  it('turns names into the codes OpenSubtitles searches, and back', () => {
    expect(fallbackCodes(['en', 'pt-BR'])).toEqual(['en', 'pt-br'])
    expect(fallbackTags(['en', 'pt-br'])).toEqual(['en', 'pt-BR'])
    expect(fallbackRefusal('pt-BR')).toBeNull()
    expect(fallbackRefusal('zh-Hant')).toContain('can’t search for that one')
  })
})

describe('Account & devices', () => {
  const session = (over: Partial<Session>): Session => ({
    id: 's1', kind: 'device', name: 'Living room TV', product: 'WatchMyMedia for Google TV', version: '1.4.3', createdAt: '2026-01-01T00:00:00Z',
    lastSeenAt: '2026-01-02T00:00:00Z', expiresAt: '2027-01-01T00:00:00Z', lastIp: '192.0.2.40', current: false, userId: 1, userName: 'Robin', ...over,
  })
  const ago = () => 'yesterday'
  it('counts the devices in the page’s line', () => {
    expect(accountLede('Robin', true, 4, false)).toBe('Signed in as Robin · admin. Signed in on 4 devices.')
    expect(accountLede('Robin', true, 1, false)).toBe('Signed in as Robin · admin. Signed in on this browser only.')
    expect(accountLede('Casey', false, 7, true)).toBe('Signed in as Casey. Everyone on this server is signed in on 7 devices.')
  })
  it('puts the person first in each row of Everyone’s', () => {
    expect(sessionLine(session({}), false, ago)).toBe('WatchMyMedia for Google TV 1.4.3 · signed in yesterday · last used yesterday · 192.0.2.40')
    expect(sessionLine(session({ userName: 'Alex' }), true, ago)).toBe('Alex · WatchMyMedia for Google TV 1.4.3 · last used yesterday · 192.0.2.40')
  })
  it('names the device, and the person when it is someone else’s', () => {
    expect(signOutTitle(session({}), 1)).toBe('Sign out Living room TV?')
    expect(signOutTitle(session({ userId: 2, userName: 'Alex', name: 'Bedroom TV' }), 1)).toBe('Sign out Alex’s Bedroom TV?')
    expect(signOutTitle(session({ current: true }), 1)).toBe('Sign out of this browser?')
  })
  it('treats a device already signed out as gone, and anything else as still signed in', () => {
    expect(signOutError(refused(404))).toEqual({ gone: true })
    expect(signOutError(offline)).toEqual({ gone: false, text: 'Couldn’t sign it out, so it’s still signed in. Check your connection and try again.' })
  })
  it('puts the count of devices it signs out on the button', () => {
    expect(changePasswordLabel(3)).toBe('Change password and sign out 3 devices')
    expect(changePasswordLabel(1)).toBe('Change password and sign out 1 device')
    expect(changePasswordLabel(0)).toBe('Change password')
  })
  it('shows mistakes under their fields', () => {
    expect(passwordFieldErrors('', 'short')).toEqual({ current: 'Enter your current password.', new: 'Use 8 or more characters.' })
    expect(passwordFieldErrors('old', 'x'.repeat(257))).toEqual({ new: 'Use 256 characters or fewer.' })
    expect(passwordFieldErrors('old', 'long enough')).toEqual({})
    expect(passwordChangeError(refused(401), '')).toEqual({ field: 'current', text: 'That isn’t your current password. Nothing was changed.' })
  })
  it('says nothing was changed for too many tries and an unreachable server', () => {
    const tries = passwordChangeError(refused(429), '')
    expect(tries.field === null && tries.title).toBe('Too many tries')
    expect(tries.field === null && tries.body).toMatch(/^Nothing was changed/)
    const off = passwordChangeError(offline, '')
    expect(off.field === null && off.title).toBe('Couldn’t reach the server, so your password wasn’t changed')
  })
  it('counts the signed-out devices in the banner', () => {
    expect(passwordChangedBanner(3).body).toBe('3 other devices were signed out and will ask for the new password. Link a TV signs a TV back in without typing.')
    expect(passwordChangedBanner(1).body).toMatch(/^1 other device was signed out/)
    expect(passwordChangedBanner(undefined).body).toMatch(/^Every other device was signed out/)
  })
})

describe('Link a TV', () => {
  it('opens the step the address names', () => {
    expect(linkStep({})).toEqual({ step: 'code' })
    expect(linkStep({ step: 'abc234' })).toEqual({ step: 'check', code: 'ABC234' })
    expect(linkStep({ step: 'ABC234', rest: 'done' })).toEqual({ step: 'done', code: 'ABC234' })
    expect(linkStep({ step: 'ABC2' })).toEqual({ step: 'code' })
    expect(linkStep({ step: 'not-a-code!' })).toEqual({ step: 'code' })
  })
  it('keeps each step at its own address', () => {
    expect(linkHref('code')).toBe('/settings/account/link')
    expect(linkHref('check', 'ABC234')).toBe('/settings/account/link/ABC234')
    expect(linkHref('done', 'ABC234')).toBe('/settings/account/link/ABC234/done')
    expect(linkHref('check')).toBe('/settings/account/link')
  })
  it('sends /link from a TV’s QR code to the second step', () => {
    expect(linkRedirect('abc-234')).toBe('/settings/account/link/ABC234')
    expect(linkRedirect(undefined)).toBe('/settings/account/link')
    expect(linkRedirect('ABC')).toBe('/settings/account/link')
  })
  it('counts what is left to type', () => {
    expect(codeHint('ABC2')).toBe('Six characters. Two more to go.')
    expect(codeHint('ABC23')).toBe('Six characters. One more to go.')
    expect(codeHint('')).toBe('Six characters.')
  })
  it('words a code nobody is waiting with, one that ran out, one already approved and an unreachable server', () => {
    expect(lookupError(refused(404), 'ABC234', '').title).toBe('No device is waiting with ABC234')
    expect(approveError(refused(404), 'ABC234', '')).toMatchObject({ kind: 'expired', title: 'The code ran out before it was approved' })
    expect(approveError(refused(409), 'ABC234', '')).toMatchObject({ kind: 'approved', title: 'This device is already signed in' })
    expect(approveError(offline, 'ABC234', '')).toMatchObject({ kind: 'other', body: 'The server didn’t answer. The TV is still waiting. Try again.' })
  })
})

describe('Your requests', () => {
  it('counts what is waiting and downloading', () => {
    expect(requestsSummary([{ status: 'pending' }, { status: 'approved', download: { state: 'downloading', progress: 0.4 } as never }, { status: 'declined' }]))
      .toBe('1 is waiting for approval and 1 is downloading.')
    expect(requestsSummary([{ status: 'pending' }, { status: 'pending' }])).toBe('2 are waiting for approval.')
    expect(requestsSummary([{ status: 'available' }])).toBe('')
  })
  it('says why requests are off, and who can change it (loose end 10)', () => {
    expect(requestsOff({ backend: 'arr', movies: true, shows: false }, false)).toBeNull()
    expect(requestsOff({ backend: 'none', movies: false, shows: false }, false)).toMatchObject({ title: 'Requests aren’t on for this server', setUp: false })
    expect(requestsOff({ backend: 'seerr', movies: false, shows: false }, false)).toMatchObject({ title: 'Your account isn’t linked to Seerr', setUp: false })
    expect(requestsOff({ backend: 'none', movies: false, shows: false }, true)).toMatchObject({ title: 'Requests aren’t on', setUp: true })
  })
  it('says what cancelling does with and without Seerr', () => {
    expect(cancelLine('seerr')).toContain('deleted in Seerr too')
    expect(cancelLine('arr')).toContain('admins’ Inbox')
  })
  it('words a failed cancel for 404, 422 and 502', () => {
    expect(cancelError(refused(404), 'arr')).toEqual({ gone: true, text: 'This request is already gone: it was cancelled or removed elsewhere. It’s left the list.' })
    expect(cancelError(refused(422), 'seerr', 'approved').text).toBe('It can’t be cancelled any more: it was approved a moment ago. It’s shown as Approved now.')
    expect(cancelError(refused(502), 'seerr').text).toBe('Couldn’t reach Seerr, so the request is still there. Try again in a minute.')
    expect(cancelError(refused(502), 'arr').text).toContain('Radarr or Sonarr')
    expect(cancelError(offline, 'arr').gone).toBe(false)
  })
})

describe('About', () => {
  it('notices the server was updated while the page was open', () => {
    expect(versionChanged('1.4.4', '1.4.5')).toBe(true)
    expect(versionChanged('1.4.4', '1.4.4')).toBe(false)
    expect(versionChanged(undefined, '1.4.5')).toBe(false)
  })
  it('joins names for a line', () => {
    expect(andList(['English', 'Spanish', 'Urdu'])).toBe('English, Spanish and Urdu')
  })
})

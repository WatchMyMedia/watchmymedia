import { describe, expect, it } from 'vitest'
import { addressCandidates, displayAddress, isNotEncrypted, isPrivateNetworkHost } from './address'

describe('typed server addresses', () => {
  it('reads addresses the way the Android app does', () => {
    expect(addressCandidates('192.168.1.20')).toEqual(['http://192.168.1.20:27587'])
    expect(addressCandidates('192.168.1.20:8080')).toEqual(['http://192.168.1.20:8080'])
    expect(addressCandidates(' nas.local/ ')).toEqual(['http://nas.local:27587'])
    expect(addressCandidates('home.example.net')).toEqual(['https://home.example.net'])
    expect(addressCandidates('https://home.example.net')).toEqual(['https://home.example.net'])
    expect(addressCandidates('http://home.example.net')).toEqual(['http://home.example.net:27587', 'http://home.example.net'])
    expect(addressCandidates('http://[fd7a:115c::1]:8080')).toEqual(['http://[fd7a:115c::1]:8080'])
  })

  it('refuses anything but an origin', () => {
    for (const bad of ['', 'a b', 'file:///tmp', 'ftp://nas.local', 'https://name:secret@home.example.net',
      'https://home.example.net/api', 'https://home.example.net/?token=x', 'http://nas.local#x']) {
      expect(() => addressCandidates(bad), bad).toThrow('doesn’t look like a server address')
    }
  })

  it('shows addresses without the scheme', () => {
    expect(displayAddress('http://192.168.1.20:8080')).toBe('192.168.1.20:8080')
    expect(displayAddress('https://home.example.net/link?code=KX7P4M')).toBe('home.example.net/link')
  })

  it('knows which hosts stay on the home network', () => {
    for (const host of ['127.0.0.1', '10.1.2.3', '172.20.0.1', '192.168.1.20', '169.254.1.1', '100.100.1.1', 'nas.local', 'nas', 'localhost', '[::1]', '[fd7a:115c::1]', '[fe80::1]', '[::ffff:c0a8:114]']) {
      expect(isPrivateNetworkHost(host), host).toBe(true)
    }
    for (const host of ['203.0.113.20', '8.8.8.8', 'home.example.net', '[2001:db8::1]', '172.32.0.1', '999.1.1.1']) {
      expect(isPrivateNetworkHost(host), host).toBe(false)
    }
  })

  it('marks plain http beyond the home network as not encrypted', () => {
    expect(isNotEncrypted('http://203.0.113.20:27587')).toBe(true)
    expect(isNotEncrypted('203.0.113.20')).toBe(true)
    expect(isNotEncrypted('http://home.example.net')).toBe(true)
    expect(isNotEncrypted('home.example.net')).toBe(false)
    expect(isNotEncrypted('192.168.1.20:8080')).toBe(false)
    expect(isNotEncrypted('https://203.0.113.20')).toBe(false)
    expect(isNotEncrypted('not an address')).toBe(false)
  })
})

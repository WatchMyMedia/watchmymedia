// Server addresses as people type them on the TV, and what each one means.
// The rules follow the Android app's (core/data ServerAddress and
// core/model Servers.kt), so the two read an address the same way.

/** The server's own port, for an address typed without one. */
export const DEFAULT_PORT = 27587

/** An address the TV can't use; its message is ours, safe to show. */
export class AddressError extends Error {
  constructor() {
    super('That doesn’t look like a server address. Try one like 192.168.1.20:27587 or https://home.example.net.')
    this.name = 'AddressError'
  }
}

const IPV4 = /^\d{1,3}(\.\d{1,3}){3}$/
const PORT = /:\d{1,5}$/

/**
 * What someone typed → the base URLs (origins) to try, in order. A port or a
 * scheme typed is kept; "192.168.1.20" and "nas.local" mean the server's own
 * port over http; a domain name without either means https (a reverse
 * proxy); plain http to a name is tried at the server's port first. Only an
 * origin is accepted: no user name, path, query or fragment.
 */
export function addressCandidates(input: string): string[] {
  const text = input.trim().replace(/\/+$/, '')
  if (!text || /\s/.test(text)) throw new AddressError()
  const schemeGiven = text.includes('://')
  let url: URL
  try { url = new URL(schemeGiven ? text : `http://${text}`) } catch { throw new AddressError() }
  if ((url.protocol !== 'http:' && url.protocol !== 'https:') || url.username || url.password
    || url.pathname !== '/' || url.search || url.hash || !url.hostname) throw new AddressError()
  const authority = text.slice(schemeGiven ? text.indexOf('://') + 3 : 0).split('/')[0]
  const portGiven = PORT.test(authority) && !authority.endsWith(']')
  const withPort = (u: URL, port: number) => { const c = new URL(u.href); c.port = String(port); return c.origin }
  let out: string[]
  if (portGiven || url.protocol === 'https:') out = [url.origin]
  else if (schemeGiven) out = [withPort(url, DEFAULT_PORT), url.origin]
  else if (isDomain(url.hostname)) out = [`https://${url.host}`]
  else out = [withPort(url, DEFAULT_PORT)]
  return [...new Set(out)]
}

/** A name with dots that is not an address or an mDNS name: probably reached through a proxy. */
function isDomain(host: string) {
  return host.includes('.') && !host.endsWith('.local') && !IPV4.test(host) && !host.startsWith('[')
}

/** An address as people read it: "192.168.1.20:27587", "home.example.net/link". */
export function displayAddress(url: string): string {
  return url.replace(/^[a-z]+:\/\//i, '').replace(/[?#].*$/, '').replace(/\/+$/, '')
}

/** A base URL as servers are remembered and compared: its origin. */
export function originOf(url: string): string | undefined {
  try {
    const u = new URL(url)
    return u.protocol === 'http:' || u.protocol === 'https:' ? u.origin : undefined
  } catch { return undefined }
}

function ipv4(host: string): number[] | undefined {
  if (!IPV4.test(host)) return undefined
  const parts = host.split('.').map(Number)
  return parts.every((p) => p <= 255) ? parts : undefined
}

function privateIpv4([a, b]: number[]) {
  return a === 127 || a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168)
    || (a === 169 && b === 254) || (a === 100 && b >= 64 && b <= 127)
}

/** An IPv6 address as URL writes it (lowercase, compressed): loopback, unique local, link-local or an IPv4 one. */
function privateIpv6(host: string) {
  if (host === '::1') return true
  const mapped = /^::ffff:([0-9a-f]{1,4}):([0-9a-f]{1,4})$/.exec(host)
  if (mapped) {
    const hi = parseInt(mapped[1], 16), lo = parseInt(mapped[2], 16)
    return privateIpv4([hi >> 8, hi & 255, lo >> 8, lo & 255])
  }
  const first = parseInt(host.split(':')[0] || '0', 16)
  return (first & 0xfe00) === 0xfc00 || (first & 0xffc0) === 0xfe80
}

/**
 * A host only this network (or this TV) reaches: loopback, a private,
 * link-local or carrier-grade NAT IPv4 address (Tailscale's 100.64/10), an
 * IPv6 unique local or link-local address, a `.local` name, or a bare name
 * without dots, which the router answers. It reads the text only.
 */
export function isPrivateNetworkHost(hostname: string): boolean {
  const h = hostname.trim().toLowerCase().replace(/\.$/, '')
  if (!h) return false
  if (h.startsWith('[')) return privateIpv6(h.slice(1, -1))
  const v4 = ipv4(h)
  if (v4) return privateIpv4(v4)
  if (/^[\d.]+$/.test(h)) return false
  return h === 'localhost' || h.endsWith('.local') || !h.includes('.')
}

/**
 * Plain http to a host beyond this network, so passwords, tokens and video
 * cross the internet unencrypted: "Not encrypted". Without a scheme an
 * address is read as addressCandidates would try it first.
 */
export function isNotEncrypted(address: string): boolean {
  let first: string
  try { first = addressCandidates(address)[0] } catch { return false }
  const url = new URL(first)
  return url.protocol === 'http:' && !isPrivateNetworkHost(url.hostname)
}

export type AdminLimit = 'network' | 'https'

// The server answers 'https' when it cannot tell the visit is HTTPS. On an
// https:// page that is the reverse proxy's doing, not the address's: the
// proxy is neither trusted nor on a trusted network, or sends no
// X-Forwarded-Proto.
export function adminAwayWording(limit: AdminLimit, pageIsHTTPS = globalThis.location?.protocol === 'https:') {
  if (limit === 'network') return {
    short: 'Server settings are off away from home.',
    long: 'Server settings are off away from your home network or tailnet. An admin there can turn them on in Settings › Security.',
  }
  if (pageIsHTTPS) return {
    short: 'The server can’t tell this visit is over HTTPS.',
    long: 'This address is HTTPS, but the reverse proxy in front of the server isn’t trusted to say so, so server settings stay closed. Add the proxy to trusted_proxies in config.yaml and restart the server, or open the server from your home network or tailnet.',
  }
  return {
    short: 'Away from home, server settings open only over HTTPS.',
    long: 'Away from home, server settings open only over HTTPS. Open this server at its https:// address, or from your home network or tailnet.',
  }
}

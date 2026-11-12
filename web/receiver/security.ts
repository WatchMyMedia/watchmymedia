/** Cast status is visible to every connected sender, including one that joins later.
 * Return a detached copy: CAF still needs the original credentials to fetch media.
 * Applies to media status (including queues) and our custom state/diagnostics.
 */
export function publicCastMessage<T>(message: T): T {
  return JSON.parse(JSON.stringify(message, (key, value: unknown) => {
    if (/^(token|tokenExpiresAt|credentials|credentialsType|authorization|customData)$/i.test(key)) return undefined
    if (typeof value !== 'string') return value
    // Authenticated artwork is optional. Never give a joining sender its bearer.
    if (/^(url|stillUrl)$/i.test(key) && /[?&]access_token=/i.test(value)) return undefined
    return value.replace(/([?&]access_token=)[^&#\s"']+/gi, '$1REDACTED')
      .replace(/\b[cm]1\.[A-Za-z0-9_.-]+/g, 'REDACTED')
  })) as T
}

/** The setting is entered in decimal Mbps; the wire contract uses integer bps. */
export function parseUploadMbps(raw: string): number | undefined {
  const value = raw.trim()
  if (!/^(?:0|[1-9]\d*)(?:\.\d{1,3})?$/.test(value)) return undefined
  const bps = Number(value) * 1_000_000
  return Number.isSafeInteger(bps) && bps <= 1_000_000_000_000 ? bps : undefined
}

export function uploadMbps(bps: number): string {
  return String(bps / 1_000_000)
}

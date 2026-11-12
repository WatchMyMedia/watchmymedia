import { QUALITIES } from '../quality'

/** A completed media transfer, not encoder wait or time spent in the buffer. */
export class TransferMeter {
  private sample?: { bps: number; at: number }

  record(bytes: number, firstByteMs: number, endMs: number) {
    const elapsed = endMs - firstByteMs
    if (!Number.isFinite(bytes) || !Number.isFinite(elapsed) || bytes <= 0 || !(firstByteMs > 0) || elapsed <= 0 || endMs < 0) return
    // Tiny/near-instant bodies cannot establish an arbitrarily fast link.
    // Keep timer resolution or a buffered first chunk from inflating evidence.
    this.sample = { bps: Math.round(bytes * 8_000 / Math.max(elapsed, 50)), at: endMs }
  }

  read(nowMs: number): { throughputBps: number; throughputAgeMs: number } | undefined {
    if (!this.sample || nowMs < this.sample.at) return undefined
    return { throughputBps: this.sample.bps, throughputAgeMs: Math.round(nowMs - this.sample.at) }
  }

  reset() { this.sample = undefined }
}

export function higherStep(from: string, to: string): boolean {
  if (from === 'auto' || to === 'auto') return false
  const current = QUALITIES.findIndex((q) => q.id === from)
  const target = QUALITIES.findIndex((q) => q.id === to)
  return current >= 0 && target >= 0 && target < current
}

/** A negotiated directive must name a new rung and a positive generation. */
export function switchGeneration(autoStep: string | undefined, quality: string, step: string | undefined, generation: number | undefined): number | undefined {
  if (quality !== 'auto' || !autoStep || !step || !generation || !Number.isSafeInteger(generation) || generation <= 0 || step === autoStep) return undefined
  return generation
}

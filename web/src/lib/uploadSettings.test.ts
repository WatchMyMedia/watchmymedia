import { describe, expect, it } from 'vitest'
import { parseUploadMbps, uploadMbps } from './uploadSettings'

describe('remote upload setting', () => {
  it('accepts zero as unlimited and exact decimal Mbps', () => {
    expect(parseUploadMbps('0')).toBe(0)
    expect(parseUploadMbps(' 12.125 ')).toBe(12_125_000)
    expect(uploadMbps(12_125_000)).toBe('12.125')
  })
  it('rejects invalid and out of range values before saving', () => {
    for (const value of ['', '-1', '1e3', '.5', '01', '1.2345', '1000001', 'Infinity']) {
      expect(parseUploadMbps(value)).toBeUndefined()
    }
  })
})

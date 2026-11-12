import type { MediaFile } from './types'

export function fileTitle(file: MediaFile) {
  return file.relativePath.split(/[\\/]/).pop()?.replace(/\.[^.]+$/, '').replace(/ \(\d{4}\)$/, '') || `File ${file.id}`
}
export function clockTime(ms: number) {
  const seconds = Math.max(0, Math.floor(ms / 1000))
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}
export function fileMeta(file: MediaFile) {
  const codec = (value?: string) => value ? ({ h264: 'H.264', hevc: 'HEVC', eac3: 'E-AC-3', ac3: 'AC-3', aac: 'AAC' } as Record<string, string>)[value] ?? value.toUpperCase() : undefined
  return [file.durationMs ? clockTime(file.durationMs) : undefined, file.height ? `${file.height}p` : undefined,
    codec(file.videoCodec), codec(file.audioCodec)].filter(Boolean).join(' · ')
}

import type { components } from '../api/schema'
import { resolveStep, type FlowStep } from './flowSteps'
import { adapterChoices, adapterNamespace, hardwareState } from './transcoder'
import { formatBytes } from './format'

// Settings › Server › Transcoding (PLAN-ADMIN-SETTINGS.md §5.7): which
// encoder is in use and whether it passed its test, the Choose an encoder
// flow, the remote upload limit and the seek thumbnails dialog.

type Capabilities = components['schemas']['Capabilities']
type Diagnostic = components['schemas']['HardwareDiagnostic']
type Inventory = components['schemas']['Inventory']
type Snapshot = components['schemas']['Snapshot']
export type Backend = components['schemas']['Settings']['hwaccel']

const LABEL: Record<Backend, string> = {
  auto: 'Automatic', software: 'Software', videotoolbox: 'VideoToolbox', nvenc: 'NVENC', qsv: 'Quick Sync', vaapi: 'VAAPI', amf: 'AMF',
}

export function encoderLabel(backend: string): string {
  return LABEL[backend as Backend] ?? backend
}

/** Where each encoder works, for the line that names the ones this machine can't have. */
const WHERE: Record<Backend, string> = {
  auto: '', software: '', videotoolbox: 'Macs', nvenc: 'Windows and Linux', qsv: 'Windows and Linux', vaapi: 'Linux', amf: 'Windows',
}

const ON: Record<string, Backend[]> = {
  darwin: ['videotoolbox'],
  windows: ['nvenc', 'qsv', 'amf'],
  linux: ['nvenc', 'qsv', 'vaapi'],
}

const ALL: Backend[] = ['auto', 'videotoolbox', 'nvenc', 'qsv', 'vaapi', 'amf', 'software']

const OS_NAME: Record<string, string> = { darwin: 'a Mac', windows: 'Windows', linux: 'Linux' }

export type EncoderChoice = { backend: Backend; label: string; description: string; recommended?: boolean }

function then(list: string[]) {
  return list.join(', then ')
}

/** What a choice says under its name. */
export function encoderDescription(backend: Backend, platform?: string): string {
  switch (backend) {
    case 'auto': {
      const order = platform ? ON[platform] : undefined
      if (!order) return 'Tries each hardware encoder this machine can have, and uses the first that passes a test.'
      if (order.length === 1) return `Tries ${LABEL[order[0]]}, and uses software if it doesn’t pass a test.`
      return `Tries ${then(order.map((b) => LABEL[b]))}, and uses the first that passes a test.`
    }
    case 'software': return 'No GPU. Works everywhere, slow, up to 1080p.'
    case 'videotoolbox': return 'Apple’s encoder, on Macs.'
    case 'nvenc': return 'NVIDIA graphics cards.'
    case 'qsv': return 'Intel processors with built-in graphics.'
    case 'vaapi': return 'Intel and AMD graphics on Linux.'
    case 'amf': return 'AMD graphics cards.'
  }
}

/**
 * The choices on Choose an encoder: every encoder this machine can have,
 * Automatic first and Software last, and a line naming the ones it can't
 * ("Not on Windows: VideoToolbox (Macs), VAAPI (Linux)"). The saved choice
 * is always listed, even when it is one this machine can't have. Without a
 * platform (an older server) all seven are choices.
 */
export function encoderChoices(platform: string | undefined, saved?: Backend): { choices: EncoderChoice[]; notHere: string } {
  const here = platform ? ON[platform] : undefined
  const shown = ALL.filter((b) => !here || b === 'auto' || b === 'software' || here.includes(b) || b === saved)
  const missing = here ? ALL.filter((b) => !shown.includes(b)) : []
  return {
    choices: shown.map((backend) => ({ backend, label: LABEL[backend], description: encoderDescription(backend, platform), recommended: backend === 'auto' })),
    notHere: missing.length && platform ? `Not on ${OS_NAME[platform] ?? platform}: ${missing.map((b) => `${LABEL[b]} (${WHERE[b]})`).join(', ')}.` : '',
  }
}

/** Whether an encoder takes a GPU, which then shows beside it. */
export function takesGpu(backend: string, platform?: string): boolean {
  return !!adapterNamespace(backend, platform)
}

export const ENCODER_STEPS: readonly FlowStep[] = [
  { key: 'choose', label: 'Choose' },
  { key: 'test', label: 'Test' },
  { key: 'in-use', label: 'In use' },
]

export type EncoderTest = 'testing' | 'passed' | 'failed'

/** How the saved choice's test stands: still running, passed, or failed (the server fell back). */
export function encoderTest(preference: string, caps?: Capabilities): EncoderTest {
  const state = hardwareState(preference, caps)
  return state === 'fallback' ? 'failed' : state === 'ready' ? 'passed' : 'testing'
}

/**
 * The step an address opens. The flow starts on Choose, since there is
 * always a saved choice to change; Test can be opened at any time; In use
 * only once the test has passed, else Test.
 */
export function encoderStep(requested: string | undefined, test: EncoderTest): FlowStep {
  return resolveStep(ENCODER_STEPS, requested ?? 'choose', (key) => key === 'choose' || (key === 'test' && test === 'passed'))
}

/** The GPU's name for an index, when the server knows the names. */
export function gpuName(backend: string, device: number | undefined, platform?: string, inventory?: Inventory): string | undefined {
  if (device === undefined || device < 0) return undefined
  return adapterChoices(backend, platform, inventory).find((a) => a.index === device)?.name
}

function belongsTo(d: Diagnostic, backend: string) {
  return d.encoder === backend || d.encoder.endsWith(`_${backend}`)
}

export type TestFailure = { kind: 'gpu-missing' | 'failed'; detail?: string }

/** Why the chosen encoder's test failed: its GPU isn't there, or what ffmpeg said. */
export function testFailure(backend: string, device: number | undefined, diagnostics: readonly Diagnostic[] | null | undefined): TestFailure {
  const failed = (diagnostics ?? []).filter((d) => !d.ok && belongsTo(d, backend))
  const first = failed[0]
  if (first?.stage === 'device' && device !== undefined && device >= 0) return { kind: 'gpu-missing', detail: first.detail }
  return { kind: 'failed', detail: first?.detail }
}

/** What usually fixes a failed test, by encoder. */
export function failureAdvice(backend: string): string {
  switch (backend) {
    case 'nvenc': return 'That usually means the NVIDIA driver is missing or too old. Install the current driver, then test again.'
    case 'qsv': return 'That usually means the Intel graphics driver is missing, or the server can’t reach the GPU. Install the driver, then test again.'
    case 'amf': return 'That usually means the AMD driver is missing or too old. Install the current driver, then test again.'
    case 'vaapi': return 'That usually means the server can’t reach the GPU. In Docker, pass /dev/dri to the container, then test again.'
    case 'videotoolbox': return 'That usually means this Mac’s encoder isn’t reachable, as in Docker on a Mac. Use the Mac app instead, then test again.'
    default: return 'Check the GPU’s drivers, then test again.'
  }
}

/** The banner on the page when the chosen encoder didn't pass its test. */
export function fallbackBanner(preference: string, caps: Capabilities): { title: string; body: string } {
  const using = caps.hwaccel === 'software' ? 'software' : encoderLabel(caps.hwaccel)
  return {
    title: `${encoderLabel(preference)} didn’t pass its test, so the server is using ${using}`,
    body: caps.hwaccel === 'software'
      ? 'Software converting is slow and limited to 1080p. Check the GPU’s drivers, then test again, or choose another encoder.'
      : 'Check the GPU’s drivers, then test again, or choose another encoder.',
  }
}

/** The Encoder row's line: how it was chosen, how far it converts, and when it was tested. */
export function encoderLine(preference: string, caps: Capabilities, testedAt: string): string {
  const how = preference === 'auto' ? 'Chosen automatically.' : 'Chosen in Settings.'
  const height = caps.maxHeight >= 2160 ? 'New plays can convert up to 4K.' : 'New plays can convert up to 1080p.'
  return `${how} ${height} Tested ${testedAt}.`
}

/** "today 9:02 AM", "yesterday 6:10 PM", "12 Nov 9:02 AM". */
export function testedAt(iso: string, now = new Date()): string {
  const d = new Date(iso)
  const time = d.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' })
  const day = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime()
  const days = Math.round((day(now) - day(d)) / 86_400_000)
  if (days === 0) return `today ${time}`
  if (days === 1) return `yesterday ${time}`
  return `${d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })} ${time}`
}

/** The In use screen: the state line and What changed / What happens next. */
export function inUseWords(caps: Capabilities, gpu?: string): { title: string; lede: string; changed: string[]; next: string[] } {
  const label = caps.hwaccel === 'software' ? 'Software' : encoderLabel(caps.hwaccel)
  const fps = caps.speed4k ? Math.round(caps.speed4k) : 0
  const passed = fps ? `passed its test at ${fps} frames a second in 4K` : 'passed its test'
  const fourK = caps.maxHeight >= 2160
  return {
    title: `${label} is in use`,
    lede: gpu ? `${gpu} · ${passed}` : passed[0].toUpperCase() + passed.slice(1),
    changed: [
      `New plays that need converting use ${caps.hwaccel === 'software' ? 'software' : label}.`,
      fourK ? '4K converting is on, because the test ran at 48 frames a second or better.' : 'Converting stops at 1080p, because the 4K test didn’t reach 48 frames a second.',
    ],
    next: [
      'Plays already running keep their encoder until they end.',
      'No restart is needed.',
      ...(caps.hwaccel === 'software' ? [] : [`If ${label} ever stops working, the server falls back to software and this page says so.`]),
    ],
  }
}

// ---------------------------------------------------------------- remote upload limit

/** "40 Mbps", from bits a second. */
export function mbps(bps: number): string {
  return `${(bps / 1_000_000).toLocaleString('en-US', { maximumFractionDigits: 3 })} Mbps`
}

/** The Remote upload limit row's title: "40 Mbps, enforced", "40 Mbps, measured only", "No limit". */
export function uploadTitle(u: Pick<Snapshot, 'configuredBps' | 'enforcing'>): string {
  if (u.configuredBps <= 0) return u.enforcing ? 'No limit, enforced' : 'No limit'
  return `${mbps(u.configuredBps)}, ${u.enforcing ? 'enforced' : 'measured only'}`
}

/**
 * The warning on the draft when people away from home are streaming close to
 * the new limit: their quality may step down. Null when it wouldn't bite.
 */
export function uploadWarning(u: Pick<Snapshot, 'activeViews' | 'deliveredBps' | 'reservedBps'>, bps: number | undefined, enforce: boolean): { title: string; body: string } | null {
  if (!enforce || !bps || bps <= 0 || u.activeViews <= 0) return null
  const using = Math.max(u.deliveredBps, u.reservedBps)
  if (using < bps * 0.75) return null
  return {
    title: `${u.activeViews} ${u.activeViews === 1 ? 'stream is' : 'streams are'} going to people away from home, using ${mbps(using)}`,
    body: `At ${mbps(bps)} their quality may step down.`,
  }
}

export type UploadSaveError = { conflict: true } | { conflict: false; message: string }

/** A refused save: changed elsewhere (409 on the revision), or the server's words. */
export function uploadSaveError(error: unknown, words: (e: unknown) => string): UploadSaveError {
  if (error && typeof error === 'object' && 'status' in error && (error as { status: number }).status === 409) return { conflict: true }
  return { conflict: false, message: words(error) }
}

/** The banner on the page after Save: what changed and what happens next, in a sentence. */
export function uploadSavedLine(bps: number, enforce: boolean): string {
  if (bps <= 0) return enforce ? 'Saved. There is no limit, so nothing is paced.' : 'Saved. There is no limit; the server only measures what remote streams use.'
  return enforce
    ? `Saved. Remote streams now share ${mbps(bps)}, paced to fit, from the next segment on.`
    : `Saved. The server measures remote streams against ${mbps(bps)} but doesn’t limit them.`
}

// ---------------------------------------------------------------- seek thumbnails

/** The Delete thumbnails dialog's words. */
export function deleteThumbnailsWords(bytes: number, enabled: boolean): { lede: string; note?: string } {
  return {
    lede: `Frees ${formatBytes(bytes)}. Scrubbing shows no pictures until they’re made again.`,
    note: enabled ? 'Make seek thumbnails is on, so making them starts again straight away and takes a few hours. To keep the space, turn that switch off first.' : undefined,
  }
}

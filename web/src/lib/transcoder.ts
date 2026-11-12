import type { components } from '../api/schema'

type Capabilities = components['schemas']['Capabilities']
type Inventory = components['schemas']['Inventory']
type Adapter = components['schemas']['Adapter']
type Backend = NonNullable<components['schemas']['UpdateSettingsInputBody']['hwaccel']>

/** Ordinals are never translated between CUDA and DXGI or renumbered. */
export function adapterChoices(backend: string, platform?: string, inventory?: Inventory): Adapter[] {
  const namespace = adapterNamespace(backend, platform)?.toLowerCase()
  if (!inventory?.supported || !namespace) return []
  const vendor = backend === 'qsv' ? 0x8086 : backend === 'amf' ? 0x1002 : undefined
  return inventory.adapters.filter((a) => a.namespace === namespace &&
    Number.isInteger(a.index) && a.index >= 0 && a.index <= 2147483647 &&
    (vendor === undefined || a.vendorId === vendor)).sort((a, b) => a.index - b.index)
}

export function adapterChoiceValue(value: string, choices: Adapter[], manual: boolean): string {
  if (manual) return 'manual'
  if (!value.trim()) return ''
  const index = adapterIndex(value)
  return choices.some((a) => a.index === index) ? String(index) : 'manual'
}

/** Bind the index to the backend the user saw, even if another admin changes it. */
export function adapterPreference(backend: Backend, value: string) {
  const device = adapterIndex(value)
  if (!['nvenc', 'qsv', 'vaapi', 'amf'].includes(backend) || device === undefined) return undefined
  return { hwaccel: backend, hwDevice: device }
}

export function adapterDiscoveryDetail(backend: string, platform?: string, inventory?: Inventory): string | undefined {
  const namespace = adapterNamespace(backend, platform)?.toLowerCase()
  if (!inventory?.supported || !namespace) return undefined
  const failed = inventory.diagnostics.find((d) => d.namespace === namespace && !d.ok)
  return failed ? failed.detail || 'Device-name discovery did not complete.' : undefined
}

/** What a device index counts: CUDA adapters, Windows DirectX adapters, or Linux render nodes (/dev/dri/renderD128 is 0). */
export function adapterNamespace(backend: string, platform?: string): 'CUDA' | 'DirectX' | 'render node' | undefined {
  if (backend === 'nvenc') return 'CUDA'
  if (platform === 'windows' && (backend === 'qsv' || backend === 'amf')) return 'DirectX'
  if (platform === 'linux' && (backend === 'qsv' || backend === 'vaapi')) return 'render node'
}

/** The hint under the device field for a namespace. */
export function adapterIndexHint(namespace: 'CUDA' | 'DirectX' | 'render node'): string {
  if (namespace === 'render node') return 'Use the render node number: 0 is /dev/dri/renderD128, 1 is renderD129. Leave blank for the first node the server finds.'
  return `Use the ${namespace} adapter index, starting at 0. Leave blank for the default GPU. CUDA and DirectX use different indices.`
}

/**
 * The note under the transcoder card when the encoder is software: what to do
 * for a GPU on the platform the server runs on (ServerInfo.host for Unraid).
 */
export function softwareEncoderNote(setup: boolean, host?: string): string {
  if (host === 'unraid') {
    return 'On Unraid, give the container your GPU: for an Intel or AMD GPU keep the /dev/dri device in the WatchMyMedia template (Docker › WatchMyMedia › Edit); for NVIDIA install the Nvidia Driver plugin, add --runtime=nvidia to Extra Parameters and put the GPU’s UUID in NVIDIA_VISIBLE_DEVICES. Then press Check again.'
  }
  if (setup) return 'If this server runs in Docker on a Mac, use the native Mac app for faster video conversion. More hardware options are in Settings › Transcoding.'
  return 'Docker Desktop on macOS cannot reach the GPU; use the native app for VideoToolbox. On Linux pass /dev/dri for VAAPI/QSV or use the NVIDIA toolkit for NVENC.'
}

/** Blank means the backend default; reject exponent, decimal and signed input. */
export function adapterIndex(value: string): number | undefined {
  const text = value.trim()
  if (!text) return -1
  if (!/^\d+$/.test(text)) return undefined
  const n = Number(text)
  return Number.isSafeInteger(n) && n <= 2147483647 ? n : undefined
}

export function hardwareState(preference: string, caps?: Capabilities): 'unknown' | 'checking' | 'fallback' | 'ready' {
  if (!caps) return 'unknown'
  if (!caps.detected || caps.detected.startsWith('0001-')) return 'checking'
  return !['auto', 'software'].includes(preference) && preference !== caps.hwaccel ? 'fallback' : 'ready'
}

export function libraryPathExample(type: 'movie' | 'tv', platform?: string): string {
  const folder = type === 'tv' ? 'TV' : 'Movies'
  if (platform === 'windows') return `D:\\${folder}`
  if (platform === 'darwin') return `/Volumes/Media/${folder}`
  return type === 'tv' ? '/tv' : '/movies'
}

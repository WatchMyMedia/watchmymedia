// Settings addresses. Each tab keeps its address
// for its hub; tasks and item pages get addresses under it, so the browser's
// back button is Back, a step can be linked to and a reload stays put.

export type SettingsGroup = 'you' | 'server'

export type SettingsTab =
  | 'playback' | 'subtitles' | 'notifications' | 'account' | 'requests' | 'about'
  | 'general' | 'inbox' | 'libraries' | 'lists' | 'live-tv' | 'users'
  | 'integrations' | 'plex' | 'history' | 'transcoding' | 'security' | 'backups'

/** hub: the tab's page; item: one thing's page under it, with the sidebar;
 * flow: a focused task with Exit, which leaves the sidebar. */
export type SettingsPageKind = 'hub' | 'item' | 'flow'

export const SETTINGS_TABS: readonly { tab: SettingsTab; label: string; group: SettingsGroup }[] = [
  { tab: 'playback', label: 'Playback', group: 'you' },
  { tab: 'subtitles', label: 'Audio & subtitles', group: 'you' },
  { tab: 'notifications', label: 'Notifications', group: 'you' },
  { tab: 'account', label: 'Account & devices', group: 'you' },
  { tab: 'requests', label: 'Your requests', group: 'you' },
  { tab: 'about', label: 'About', group: 'you' },
  { tab: 'general', label: 'General', group: 'server' },
  { tab: 'inbox', label: 'Inbox', group: 'server' },
  { tab: 'libraries', label: 'Libraries', group: 'server' },
  { tab: 'lists', label: 'Lists', group: 'server' },
  { tab: 'live-tv', label: 'Live TV', group: 'server' },
  { tab: 'users', label: 'Users', group: 'server' },
  { tab: 'integrations', label: 'Integrations', group: 'server' },
  { tab: 'plex', label: 'Plex', group: 'server' },
  { tab: 'history', label: 'Watch history', group: 'server' },
  { tab: 'transcoding', label: 'Transcoding', group: 'server' },
  { tab: 'security', label: 'Security', group: 'server' },
  { tab: 'backups', label: 'Backups', group: 'server' },
]

// One pattern per address under a tab. A segment is a literal, `:name` for a
// value, or a trailing `*step` for the step of a flow (optional). The first
// match wins, so literals come before `:id`.
type Pattern = { path: string; kind: Exclude<SettingsPageKind, 'hub'>; task: string }

const PATTERNS: Partial<Record<SettingsTab, Pattern[]>> = {
  plex: [
    { path: 'connect/*step', kind: 'flow', task: 'connect' },
    { path: 'link/*step', kind: 'flow', task: 'link' },
    { path: 'import/*step', kind: 'flow', task: 'import' },
    { path: 'undo/:run/*step', kind: 'flow', task: 'undo' },
    { path: 'restore/*step', kind: 'flow', task: 'restore' },
  ],
  integrations: [
    { path: 'add/:app/*step', kind: 'flow', task: 'add' },
    { path: ':id/folders', kind: 'flow', task: 'folders' },
    { path: ':id/requests', kind: 'flow', task: 'requests' },
    { path: ':id/webhook', kind: 'flow', task: 'webhook' },
    { path: ':id/connection', kind: 'flow', task: 'connection' },
    { path: ':id/people', kind: 'flow', task: 'people' },
    { path: ':id', kind: 'item', task: 'connection-page' },
  ],
  libraries: [
    { path: 'add/*step', kind: 'flow', task: 'add' },
    { path: ':id/edit', kind: 'flow', task: 'edit' },
    { path: ':id/unmatched/*step', kind: 'flow', task: 'unmatched' },
    { path: ':id', kind: 'item', task: 'library' },
  ],
  'live-tv': [
    { path: 'tuners/add/*step', kind: 'flow', task: 'add-tuner' },
    { path: 'tuners/:id/edit', kind: 'flow', task: 'edit-tuner' },
    { path: 'tuners/:id', kind: 'item', task: 'tuner' },
    { path: 'access', kind: 'flow', task: 'access' },
    { path: 'guide/setup/*step', kind: 'flow', task: 'guide-setup' },
    { path: 'guide', kind: 'item', task: 'guide' },
    { path: 'recordings/setup/*step', kind: 'flow', task: 'recordings-setup' },
    { path: 'recordings', kind: 'item', task: 'recordings' },
    { path: 'channels', kind: 'item', task: 'channels' },
  ],
  users: [
    { path: 'add/*step', kind: 'flow', task: 'add' },
    { path: ':id', kind: 'item', task: 'person' },
  ],
  security: [{ path: 'sign-in-protection/*step', kind: 'flow', task: 'sign-in-protection' }],
  transcoding: [
    { path: 'encoder/*step', kind: 'flow', task: 'encoder' },
    { path: 'upload-limit', kind: 'item', task: 'upload-limit' },
  ],
  backups: [{ path: 'restore', kind: 'item', task: 'restore' }],
  general: [{ path: 'tmdb', kind: 'flow', task: 'tmdb' }],
  subtitles: [
    { path: 'opensubtitles', kind: 'flow', task: 'opensubtitles' },
    { path: 'fallback-languages', kind: 'flow', task: 'fallback-languages' },
  ],
  account: [
    { path: 'password', kind: 'flow', task: 'password' },
    { path: 'link/*step', kind: 'flow', task: 'link' },
  ],
}

export type SettingsPage = {
  tab: SettingsTab
  group: SettingsGroup
  label: string
  kind: SettingsPageKind
  /** The task or item page, from the patterns above; '' on the hub. */
  task: string
  /** `:name` values, and `step` for a flow's step when the address has one. */
  params: Record<string, string>
}

export function tabInfo(tab: SettingsTab) {
  return SETTINGS_TABS.find((t) => t.tab === tab)!
}

function matchPattern(pattern: string, segments: string[]): Record<string, string> | null {
  const parts = pattern.split('/')
  const params: Record<string, string> = {}
  for (let i = 0; i < parts.length; i++) {
    const part = parts[i]
    if (part.startsWith('*')) {
      // A step is one segment; anything deeper is a link to a step's own detail
      // (Link a TV's code, a file in the unmatched queue) and stays in `rest`.
      if (segments.length > i) {
        params[part.slice(1)] = segments[i]
        if (segments.length > i + 1) params.rest = segments.slice(i + 1).join('/')
      }
      return params
    }
    const seg = segments[i]
    if (seg === undefined || seg === '') return null
    if (part.startsWith(':')) params[part.slice(1)] = seg
    else if (part !== seg) return null
  }
  return segments.length === parts.length ? params : null
}

function decode(segment: string) {
  try { return decodeURIComponent(segment) } catch { return segment }
}

/**
 * Which Settings page an address is. Null for anything outside /settings and
 * for an address under a tab that names no page (which redirects to the hub).
 * `/settings` itself is Playback, as wide screens show it.
 */
export function parseSettingsPath(pathname: string): SettingsPage | null {
  const clean = pathname.replace(/\/+$/, '')
  if (clean !== '/settings' && !clean.startsWith('/settings/')) return null
  const [tabSegment = 'playback', ...rest] = clean.split('/').slice(2).map(decode)
  const info = SETTINGS_TABS.find((t) => t.tab === tabSegment)
  if (!info) return null
  const base = { tab: info.tab, group: info.group, label: info.label }
  if (!rest.length) return { ...base, kind: 'hub', task: '', params: {} }
  for (const p of PATTERNS[info.tab] ?? []) {
    const params = matchPattern(p.path, rest)
    if (params) return { ...base, kind: p.kind, task: p.task, params }
  }
  return null
}

/** True while a focused flow is open: no app bar and no Settings sidebar. */
export function isSettingsFlow(pathname: string) {
  return parseSettingsPath(pathname)?.kind === 'flow'
}

/** The address of a tab, or of a page under it. Segments are encoded. */
export function settingsHref(tab: SettingsTab, ...segments: (string | number | undefined | null | false)[]) {
  const rest = segments.filter((s): s is string | number => s !== undefined && s !== null && s !== false && s !== '')
  return ['/settings', tab, ...rest.map((s) => encodeURIComponent(String(s)))].join('/')
}

/**
 * Where an address that is no longer a page goes: today's addresses that
 * moved, and anything under a tab that names no page, which opens the tab's
 * hub. Null when the address is a page as it is.
 */
export function settingsRedirect(pathname: string): string | null {
  const clean = pathname.replace(/\/+$/, '')
  if (clean === '/admin' || clean.startsWith('/admin/')) return '/settings'
  if (clean === '/settings/sign-in') return '/settings/security'
  if (!clean.startsWith('/settings/')) return null
  if (parseSettingsPath(clean)) return null
  const tab = clean.split('/')[2]
  return SETTINGS_TABS.some((t) => t.tab === tab) ? `/settings/${tab}` : '/settings'
}

export type Crumb = { label: string; href?: string; wideOnly?: boolean }

/**
 * The breadcrumb for a page: Settings › You or Server › Tab › …parts. Phones
 * leave out the group, as the boards do. The last part is the current page.
 */
export function settingsCrumbs(tab: SettingsTab, ...parts: Crumb[]): Crumb[] {
  const info = tabInfo(tab)
  return [
    { label: 'Settings', href: '/settings' },
    { label: info.group === 'you' ? 'You' : 'Server', href: '/settings', wideOnly: true },
    { label: info.label, href: settingsHref(tab) },
    ...parts,
  ]
}

/** Where Exit and the phone's back link go: one level up from the page. */
export function parentOf(crumbs: Crumb[]): Crumb {
  return crumbs[crumbs.length - 2] ?? crumbs[0]
}

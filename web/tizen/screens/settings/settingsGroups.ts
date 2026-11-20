import type { SettingsGroup } from '../../app/routes'

// Settings' groups down the left (SettingsGroups on the canvas): which show,
// in what order, and how focus ids tie a pane's rows to their group, so
// coming back to Settings opens the pane focus was in.

export const GROUP_TITLES: Record<SettingsGroup, string> = {
  playback: 'Playback',
  languages: 'Audio & subtitles',
  homeRows: 'Home rows',
  notifications: 'Notifications',
  account: 'Account & devices',
  requests: 'Your requests',
  about: 'About',
  inbox: 'Inbox',
  server: 'Server settings',
}

/** The Server heading's groups, for admins. */
export const SERVER_GROUPS: readonly SettingsGroup[] = ['inbox', 'server']

/**
 * The groups this account sees, in order: Your requests where requests can
 * be made (unknown until the options load), the Inbox and Server settings
 * for admins.
 */
export function visibleGroups({ isAdmin, canRequest }: { isAdmin: boolean; canRequest: boolean }): SettingsGroup[] {
  const groups: SettingsGroup[] = ['playback', 'languages', 'homeRows', 'notifications', 'account']
  if (canRequest) groups.push('requests')
  groups.push('about')
  if (isAdmin) groups.push('inbox', 'server')
  return groups
}

/** A group's own focus id at the left. */
export const groupFocusId = (group: SettingsGroup) => `group/${group}`
/** A pane's focus group: it remembers the row left last time. */
export const paneGroupId = (group: SettingsGroup) => `pane/${group}`
/** A row's focus id in a group's pane. */
export const paneRowId = (group: SettingsGroup, row: string) => `pane/${group}/${row}`

/** The group a focus id is in: a group at the left, or a row of its pane. */
export function groupOfFocus(id: string | undefined): SettingsGroup | undefined {
  const m = /^(?:group|pane)\/([a-zA-Z]+)/.exec(id ?? '')
  return m && m[1] in GROUP_TITLES ? m[1] as SettingsGroup : undefined
}

/** The group Settings opens on: the route's, else the one focus was in last time, else Playback. */
export function openingGroup(asked: SettingsGroup | undefined, rememberedFocus: string | undefined): SettingsGroup {
  return asked ?? groupOfFocus(rememberedFocus) ?? 'playback'
}

/** The next item ▲ or ▼ goes to in a column, by index; the same one at either end (focus never wraps or leaves the column). */
export function stepInColumn(count: number, index: number, step: 1 | -1): number {
  if (index < 0) return count ? 0 : -1
  return Math.max(0, Math.min(count - 1, index + step))
}

import type { components } from '../api/schema'

type Install = components['schemas']['UpdateStatus']['install']

/**
 * The download button's label and the line beside it in Settings › General ›
 * Updates, by how the server was installed. Docker has no file: it pulls the
 * image instead (UpdatesSection).
 */
/**
 * How a Docker install updates, by the platform the container runs on
 * (ServerInfo.host): Unraid's Docker page does the pull; elsewhere it is a
 * compose command.
 */
export function dockerUpdateSteps(host?: string): { text: string; command?: string } {
  if (host === 'unraid') {
    return { text: 'On Unraid’s Docker page, click Check for Updates, then apply the update for WatchMyMedia. Your library, settings and artwork stay in its appdata folder.' }
  }
  return { text: 'Pull the new image and restart the container, from the folder with your compose file:', command: 'docker compose pull && docker compose up -d' }
}

export function updateDownload(install: Install): { label: string; hint?: string } {
  switch (install) {
    case 'macos-app':
      return { label: 'Download for Mac', hint: 'or choose Check for Updates… in the WatchMyMedia menu bar item.' }
    case 'windows-app':
      return { label: 'Download for Windows', hint: 'Run it to update in place, or choose Check for updates… in the WatchMyMedia menu by the clock.' }
    case 'binary':
      return { label: 'Download', hint: 'Replace the server binary with the one inside and restart it.' }
    default:
      return { label: 'Download' }
  }
}

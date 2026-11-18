import type { components } from '../api/schema'

type Install = components['schemas']['UpdateStatus']['install']

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

/**
 * How a server installed from the .deb or the .rpm package updates
 * (PLAN-LINUX.md §4): apt or dnf fetches the package, so there is a command
 * and no file, and the line after it says where the repository entry is in
 * case it was turned off. Undefined for every other install.
 */
export function packageUpdateSteps(install: Install): { text: string; command: string; after: string } | undefined {
  const restarts = 'The update restarts the server, which stops anything playing or recording.'
  switch (install) {
    case 'deb':
      return {
        text: 'This server was installed from the .deb package, so apt updates it. Run this on the server:',
        command: 'sudo apt update && sudo apt install --only-upgrade watchmymedia',
        after: `${restarts} If apt finds nothing newer, check that the WatchMyMedia repository is still turned on in /etc/apt/sources.list.d/watchmymedia.sources.`,
      }
    case 'rpm':
      return {
        text: 'This server was installed from the .rpm package, so dnf updates it. Run this on the server:',
        command: 'sudo dnf upgrade --refresh watchmymedia',
        after: `${restarts} If dnf finds nothing newer, check that the WatchMyMedia repository is still turned on in /etc/yum.repos.d/watchmymedia.repo.`,
      }
    default:
      return undefined
  }
}

/**
 * The download button's label and the line beside it in Settings › General ›
 * Updates, by how the server was installed. Docker and the Linux packages
 * have no file: they pull the image or the package instead.
 */
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

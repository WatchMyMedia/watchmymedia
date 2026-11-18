// What the web says differently on a server installed from the Debian and
// Ubuntu package (.deb) or the Fedora package (.rpm): it runs as a systemd
// service under its own watchmymedia user (PLAN-LINUX.md §3, §4). The kind
// is ServerInfo.install, and UpdateStatus.install for admins.

/** The server was installed from the .deb or the .rpm package. */
export function isPackageInstall(install: string | undefined): boolean {
  return install === 'deb' || install === 'rpm'
}

/** A path as one word of a shell command. */
export function shellQuote(path: string): string {
  return `'${path.replace(/'/g, `'\\''`)}'`
}

/**
 * What the folder check says on a package install when the server can't
 * read a folder: who the server runs as, and the command that lets that user
 * in. Undefined for every other install, which keeps its own words.
 */
export function packageFolderAccess(install: string | undefined, path: string): { text: string; command: string; after: string } | undefined {
  if (!isPackageInstall(install)) return undefined
  return {
    text: 'The server runs as the watchmymedia user, which isn’t allowed to read this folder. Give that user access with this command on the server, then check again:',
    command: `sudo setfacl -R -m u:watchmymedia:rX ${shellQuote(path)}`,
    after: 'Folders above it, such as a home folder, have to let that user through as well.',
  }
}

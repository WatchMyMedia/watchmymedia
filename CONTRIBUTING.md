# Contributing

Thanks for looking at WatchMyMedia. This repository holds the server, the web
app and its Cast receiver, and the Android, Apple, Samsung TV, Mac and Windows
apps.

## Licence

WatchMyMedia is licensed under the GNU Affero General Public License,
version 3 ([LICENSE](LICENSE)). By sending a change you agree that it is
licensed under the same terms.

## Before you start

- For anything larger than a small fix, open an issue first and say what you
  want to change. It saves you building something that cannot be merged.
- Report security problems privately, as [SECURITY.md](SECURITY.md) says, not
  in an issue.

## Building and testing

[docs/development.md](docs/development.md) has the prerequisites and the
commands to run the server and the web app; `make help` lists every target. Each folder with
code has its own README.

[AGENTS.md](AGENTS.md) and the `AGENTS.md` in each folder hold the rules the
code follows: which files are generated and how to regenerate them, how the
server stays compatible with older apps, and which checks to run for what you
changed. They are written for coding agents and read just as well by people.
Run the checks in its Validation table before you open a pull request, and
say in the pull request what you could not check, such as a real TV.

## What is not here

The maintainers keep their design notes, the screen designs, the website, the
release, signing and download scripts and the logo's sources outside this
repository. Nothing here cites them: a comment gives its reason itself, and the
code and its tests are the description of what it does. Ask in an issue when a
reason is not clear from them.

## Pull requests

- One change per pull request, with tests for what it changes.
- Commit subjects are imperative and say what changes for people: "Jump to
  any letter in the A–Z bar, not just this page's".
- Keep fixtures, tests and screenshots fictional: no real names, library
  contents or server addresses.

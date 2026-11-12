@AGENTS.md

## Claude Code

The rules are in `AGENTS.md`, shared with other agents; keep them there, and
keep this file to what only Claude Code needs. Each folder's `CLAUDE.md`
imports that folder's `AGENTS.md`.

### Guardrails in `.claude/settings.json`

- Commits and PRs carry no attribution (`attribution`).
- Reading keys and secrets (`android/secrets/`, `*.jks`, `*.keystore`,
  `*.p8`, `*.p12`, `.env`) is denied. Builds can still source them, as the
  signed Android build does.
- Releases, production deploys, pushes to `main`, force-pushes, merges and
  repository secrets always ask first, in every permission mode.
- `.claude/hooks/guard-generated.sh` refuses edits to generated files and
  names the generator to run instead.

Don't loosen these to get a task done; ask the user. Per-person settings go in
`.claude/settings.local.json` (gitignored).

### Tools

- `preview_start` takes the names in `.claude/launch.json` (`web`,
  `receiver`, `server-fixture`, `server-scratch`, `server-fixture-cast`). It
  reads the file in the session's own folder: in a worktree that is the
  worktree's copy (gitignored, copied when the worktree was made), not the
  main checkout's. Add a temporary entry there with absolute paths, and
  remove it when done.
- The built-in browser can't pass the hosted instance's Turnstile check. For
  that instance use Claude in Chrome. Check it is signed in with one `fetch`
  first, and ask the user to sign in rather than signing in yourself.
- In the iOS Simulator tool, create and `attach` your own simulator before
  `launch`; other sessions keep theirs booted. Its taps need a `duration` of
  about 0.2 s to flip a SwiftUI switch, and the first tap after a launch is
  often lost; on Apple TV, `press_key` arrows and `return` drive the remote.
- Do multi-step work in a worktree under `.claude/worktrees/`.
- Memory notes are background and can be stale. When you learn a lasting
  rule or gotcha, put it in the right `AGENTS.md` (or README) in the same
  change, so other agents get it too.

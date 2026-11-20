# Samsung TV prototype

Read the root and `web/AGENTS.md` first. This is the S0 engineering prototype in
`PLAN-SAMSUNG.md`, a separate packaged Tizen entry, not the browser app or Cast
receiver. `README.md` here records the signing and hardware gate.

- Use the generated `../src/api/schema.d.ts` through the typed client in `server.ts`.
  API requests use the selected server, bearer auth and no cookies.
- Keep testable session/player logic outside React. Samsung APIs belong behind
  the adapter in `avplay.ts`; browser preview is explicitly a different backend.
- Use plain CSS with the web brand's tokens for Chromium 108. Tailwind v4's
  runtime CSS is outside this target's support floor.
- Never print session tokens, pairing secrets, full media URLs or certificates.
  Only media-scoped tokens can enter playback URLs, on the selected server.
- Do not change server CORS/CSRF to make preview work. The local Vite proxy is a
  browser development convenience, not evidence of packaged TV networking.
- All package IDs containing `prototype` are development-only. Preserve the
  production app's future identity and author-certificate decision separately.

The user approved repo-only S0 boards in `design/mockups/samsung-tv/`; they are
not on a design canvas. Follow them for prototype UI. Signing and real TV testing
are deferred; do not attempt either as part of browser checks.

From `web/`: `pnpm dev:tizen` previews the app on port 5186, proxying only API
requests to the isolated fixture on port 27686. `pnpm build:tizen` builds unsigned
app contents for Chromium 108, as one classic deferred script without
`crossorigin`: the package loads from `file://`, where module scripts can be
refused. Keep it that way, and keep code free of `import.meta` beyond
`import.meta.env`, which the build replaces. `pnpm build:tizen-core` bundles the core separately.
`pnpm typecheck`, `pnpm lint` and `pnpm test` include this entry. Run both existing
web and receiver builds when shared code changes.

CI checks this entry in its own `samsung` job, and the `web` job leaves it out
(`pnpm lint:web`, `typecheck:web`, `test:web`). The job runs `pnpm lint:tizen`,
`pnpm test:tizen`, `pnpm build:tizen` (types first) and `pnpm check:tizen`, and
keeps the unsigned build as the `samsung-tv-unsigned` artifact for a week. It
runs for this folder, `../vite.tizen.config.ts`, `../vite.tizen-core.config.ts`,
`deploy/samsung/`, the `../src/lib/` and `../src/api/` code this entry may
import, the web's dependencies and configs, and the packed icon (`samsung` in
`deploy/ci/changes.py`). A new file outside this folder that only this entry
reads goes in `SAMSUNG` there, or the web job starts for it and this one doesn't.

`pnpm check:tizen` (`deploy/samsung/bundle.py`) reads `../dist-tizen/`: one
classic script and one stylesheet, `config.xml` and `icon.png`, no module script
or `crossorigin`, and no JS or CSS newer than Chrome 108 that the build leaves
as written (`toSorted` and the other copying array methods, `Object.groupBy`,
`Promise.withResolvers`, `Array.fromAsync`, the new `Set` methods,
`color-mix()`, `oklch()`, `light-dark()`, nested rules). The build lowers
syntax, never the browser's methods, so a library update can break this too.
Add an API to its lists when one newer than Chrome 108 turns up.

import { defineConfig } from 'vite'

// The Google Cast receiver (PLAN-CAST.md §4): a page of its own, hosted at
// cast.watchmy.media for every WatchMyMedia server. `pnpm build:receiver`
// writes dist-receiver/; `pnpm dev:receiver` serves it on the LAN for an
// unpublished development App ID.
export default defineConfig({
  root: 'receiver',
  base: './',
  build: { outDir: '../dist-receiver', emptyOutDir: true, target: 'es2020' },
  server: { host: true, port: 5174 },
})

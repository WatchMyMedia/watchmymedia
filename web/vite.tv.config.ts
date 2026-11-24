import { defineConfig, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

// The TV app, built once for each make of TV: `--mode samsung`. Each build
// resolves tv/platform/current.ts to that make's folder, takes its package
// files from that folder's public/, and writes its own dist folder.
type Make = { outDir: string; icons: Record<string, string> }

const MAKES: Record<string, Make> = {
  // Samsung's signing steps and CI's artifact read dist-tizen/.
  samsung: { outDir: '../dist-tizen', icons: { 'icon.png': '../brand/export/android/play-store-icon-512.png' } },
}

const here = (path: string) => fileURLToPath(new URL(path, import.meta.url))

/** Resolves the shared code's `platform/current` import to this make's platform. */
function tvPlatform(name: string): Plugin {
  const current = here('./tv/platform/current.ts')
  const chosen = here(`./tv/platform/${name}/index.ts`)
  return {
    name: 'tv-platform',
    enforce: 'pre',
    async resolveId(source, importer, options) {
      if (!source.endsWith('platform/current')) return null
      const resolved = await this.resolve(source, importer, { ...options, skipSelf: true })
      return resolved?.id === current ? chosen : null
    },
  }
}

export default defineConfig(({ mode }) => {
  const make = MAKES[mode]
  if (!make) throw new Error(`Build the TV app with --mode and one of: ${Object.keys(MAKES).join(', ')}.`)
  return {
    root: 'tv',
    base: './',
    publicDir: `platform/${mode}/public`,
    plugins: [react(), tvPlatform(mode), {
      name: 'tv-icons',
      generateBundle() {
        for (const [fileName, source] of Object.entries(make.icons)) this.emitFile({ type: 'asset', fileName, source: readFileSync(source) })
      },
    }, {
      // The packaged app loads from file://, where module scripts and crossorigin
      // requests can be refused. Ship one classic, deferred script instead.
      name: 'classic-script',
      apply: 'build',
      transformIndexHtml: { order: 'post', handler: (html) => html.replace(/<script type="module"/g, '<script defer').replace(/ crossorigin(?=[\s>])/g, '') },
    }],
    build: {
      outDir: make.outDir, emptyOutDir: true, target: 'chrome108', cssTarget: 'chrome108', sourcemap: false,
      // One deliberate script (about 650 kB, 200 kB gzipped, read from the TV's own
      // storage): warn only if it grows well past that. Launch time is measured on the TV.
      chunkSizeWarningLimit: 900,
      modulePreload: false, cssCodeSplit: false, rolldownOptions: { output: { format: 'iife', codeSplitting: false } },
    },
    server: {
      host: '127.0.0.1', port: 5186, strictPort: true,
      // Local fixture preview only. This does not prove packaged TV networking.
      proxy: { '/api': { target: 'http://127.0.0.1:27686', changeOrigin: false } },
      fs: { allow: ['..'] },
    },
  }
})

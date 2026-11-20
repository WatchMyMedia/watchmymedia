import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { readFileSync } from 'node:fs'
export default defineConfig({
  root: 'tizen',
  base: './',
  plugins: [react(), {
    name: 'prototype-icon',
    generateBundle() { this.emitFile({ type: 'asset', fileName: 'icon.png', source: readFileSync('../brand/export/android/play-store-icon-512.png') }) },
  }, {
    // The packaged app loads from file://, where module scripts and crossorigin
    // requests can be refused. Ship one classic, deferred script instead.
    name: 'classic-script',
    apply: 'build',
    transformIndexHtml: { order: 'post', handler: (html) => html.replace(/<script type="module"/g, '<script defer').replace(/ crossorigin(?=[\s>])/g, '') },
  }],
  build: {
    outDir: '../dist-tizen', emptyOutDir: true, target: 'chrome108', cssTarget: 'chrome108', sourcemap: false,
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
})

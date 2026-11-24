import { defineConfig } from 'vite'
export default defineConfig({
  publicDir: false,
  build: { outDir: '../build/samsung/core', emptyOutDir: true, target: 'chrome108', sourcemap: false,
    lib: { entry: 'tv/index.ts', formats: ['es'], fileName: 'tizen-core' },
    // A library of the TV's own code: its dependencies stay imports.
    rolldownOptions: { external: [/^react($|-dom|\/)/, /^@tanstack\//, 'openapi-fetch', 'qrcode-generator', 'hls.js'] } },
})

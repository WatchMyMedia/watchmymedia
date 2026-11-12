import { defineConfig } from 'vite'
export default defineConfig({
  publicDir: false,
  build: { outDir: '../build/samsung/core', emptyOutDir: true, target: 'chrome108', sourcemap: false,
    lib: { entry: 'tizen/index.ts', formats: ['es'], fileName: 'tizen-core' } },
})

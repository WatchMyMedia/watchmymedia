import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// In development the Go server runs on :27587 and Vite proxies API calls to it.
// In production the built assets are embedded in the Go binary (make web).
export default defineConfig(({ mode }) => ({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: loadEnv(mode, '.', 'WMM_').WMM_DEV_SERVER || 'http://localhost:27587', changeOrigin: false },
    },
  },
}))

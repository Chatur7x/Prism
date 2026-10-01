import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

/**
 * Dev server proxies /api to the Spring backend so the browser sees a single
 * origin in development. Production uses the nginx reverse proxy instead; see
 * docker/nginx/prism-frontend.conf.
 */
export default defineConfig({
  plugins: [react()],
  css: {
    /**
     * PRISM ships hand-written CSS against its own design tokens. It has no
     * Tailwind, no PostCSS transforms, and no autoprefixer step — `tokens.css`
     * and `components.css` are the source of truth, deliberately, so the visual
     * language is reviewable in one place rather than assembled at build time.
     *
     * Declaring an explicit empty PostCSS config is what makes that true in
     * every environment. Vite otherwise searches parent directories for a
     * `postcss.config.*`, and on a developer machine with another project one
     * level up it will happily load that project's Tailwind plugin and fail the
     * build with a plugin-resolution error that has nothing to do with PRISM.
     * Pinning the config here removes the ambient dependency entirely.
     */
    postcss: { plugins: [] },
  },
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: process.env.VITE_API_PROXY_TARGET || 'http://localhost:8080',
        changeOrigin: true,
      },
      '/actuator': {
        target: process.env.VITE_API_PROXY_TARGET || 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
  },
})

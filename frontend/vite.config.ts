import { defineConfig, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'

/**
 * Dev server proxies /api to the Spring backend so the browser sees a single
 * origin in development. Production uses the nginx reverse proxy instead; see
 * docker/nginx/prism-frontend.conf.
 */
/**
 * Emits a `404.html` whose redirect target is derived from the resolved `base`.
 *
 * A static host with no rewrite rules serves this file for every path it cannot
 * resolve, so it is the only thing standing between a deep link and the app. The
 * base is read from the resolved config rather than written into a checked-in
 * file, because a hardcoded `/Prism/` would silently rot the moment the project
 * is renamed, served from a user page, or moved to a root domain — and it would
 * rot as a redirect to nowhere, which looks like a broken deploy.
 *
 * Deep links land on the app root rather than being restored to their route.
 * That is a deliberate simplification: the static deployment is a preview of
 * the interface, its only cold-reachable route is the welcome screen, and
 * restoring router state would mean persisting a redirect target in
 * `sessionStorage` and reading it back during boot — real complexity bought for
 * a page nobody can sign into on that deployment anyway.
 */
function spaFallback(): Plugin {
  let base = '/'
  return {
    name: 'prism:spa-fallback',
    configResolved(config) {
      base = config.base || '/'
    },
    generateBundle() {
      const target = JSON.stringify(base)
      this.emitFile({
        type: 'asset',
        fileName: '404.html',
        source: `<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <meta name="robots" content="noindex" />
    <title>PRISM</title>
  </head>
  <body>
    <p>Redirecting to <a id="target">PRISM</a>…</p>
    <script>
      // Emitted by frontend/vite.config.ts. The base is interpolated here so this
      // file cannot drift away from the bundle it hands off to.
      var base = ${target}
      document.getElementById('target').href = base
      location.replace(base)
    </script>
  </body>
</html>
`,
      })
    },
  }
}

export default defineConfig({
  plugins: [react(), spaFallback()],
  /**
   * Public path for the built bundle.
   *
   * Empty by default, which is correct for every same-origin deployment: the
   * Vite dev server, and nginx in front of the bundle in
   * `docker/nginx/prism-frontend.conf`. A static host that serves the project
   * from a subdirectory — GitHub Pages serves this repo at `/Prism/` — needs
   * that prefix baked in, or every asset 404s. Set by `VITE_BASE_PATH` in
   * `.github/workflows/pages.yml`; never hardcoded, so the same source tree
   * still builds for a root-served deployment.
   */
  base: process.env.VITE_BASE_PATH ?? '/',
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

/// <reference types="vite/client" />

/**
 * Build-time configuration, declared rather than left to `any`.
 *
 * Both are optional and both default to the same-origin behaviour, so a default
 * build is unchanged. `VITE_BASE_PATH` is consumed by `vite.config.ts`, not by
 * application code, and is declared here only so the whole contract is in one
 * place.
 */
interface ImportMetaEnv {
  /** Origin of the PRISM API when it differs from the bundle's origin. */
  readonly VITE_API_BASE?: string
  /** `true` marks a static build shipped with no backend attached. */
  readonly VITE_STATIC_ONLY?: string
  /** Public path the bundle is served from, e.g. `/Prism/` on GitHub Pages. */
  readonly VITE_BASE_PATH?: string
  /** Dev-server proxy target; consumed by `vite.config.ts`. */
  readonly VITE_API_PROXY_TARGET?: string
}
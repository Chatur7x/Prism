/**
 * The single HTTP chokepoint.
 *
 * <p>Every network call in the application goes through `request`. That is not
 * tidiness for its own sake: it is the only place that can guarantee the
 * Authorization header is attached, that a 401 clears the session exactly once,
 * that a backend error body (which carries the trace id) survives into the UI,
 * and that no component ever sees a raw `Response`.
 *
 * <p>Token storage is `localStorage`, which is a deliberate tradeoff rather than
 * the default choice. It is readable by any script running on the origin, so
 * this deployment assumes the frontend is served from its own origin and no
 * third-party script is trusted there. The alternative — an httpOnly cookie —
 * needs CSRF defence and a same-site deployment story, which is a larger change
 * than this codebase currently justifies. The tradeoff is recorded in
 * `docs/architecture.md` under Security.
 */
import type { AuthResponse, AuthUser } from './types'

const TOKEN_KEY = 'prism.token'
const USER_KEY = 'prism.user'

export interface ApiViolation {
  field: string
  message: string
}

/**
 * A failed request, carrying everything needed to act on it.
 *
 * <p>`traceId` is the important field: it is the join key to the Glass Box, so an
 * error surfaced in the UI can be followed all the way to the engine step that
 * produced it.
 */
export class ApiError extends Error {
  readonly status: number
  readonly traceId: string | null
  readonly violations: ApiViolation[]
  /** 0 means the request never reached the server. */
  readonly body: unknown

  constructor(
    message: string,
    status: number,
    traceId: string | null,
    violations: ApiViolation[] = [],
    body: unknown = null,
  ) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.traceId = traceId
    this.violations = violations
    this.body = body
  }

  /** True when retrying later could plausibly succeed. */
  get retryable(): boolean {
    return this.status === 0 || this.status === 429 || this.status >= 500
  }
}

// ---- session storage ------------------------------------------------------

type AuthListener = (user: AuthUser | null) => void
const listeners = new Set<AuthListener>()

function emit(): void {
  const user = getStoredUser()
  listeners.forEach((listener) => listener(user))
}

export function onAuthChange(listener: AuthListener): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function getStoredUser(): AuthUser | null {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as AuthUser
  } catch {
    // Corrupt storage must not wedge the app in a signed-in-but-broken state.
    localStorage.removeItem(USER_KEY)
    return null
  }
}

export function setSession(token: string, user: AuthUser): void {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(USER_KEY, JSON.stringify(user))
  emit()
}

export function clearSession(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
  emit()
}

// ---- request --------------------------------------------------------------

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PATCH' | 'DELETE'
  body?: unknown
  /** Send no Authorization header. Used by login and registration. */
  anonymous?: boolean
  signal?: AbortSignal
  /** Overrides the default 30s. Needed for long-running verification. */
  timeoutMs?: number
}

const DEFAULT_TIMEOUT_MS = 30_000

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, anonymous = false, signal } = options
  const headers: Record<string, string> = {}

  if (!anonymous) {
    const token = getToken()
    if (token) headers.Authorization = `Bearer ${token}`
  }
  // Only set a content type when there is a body: announcing JSON on a GET makes
  // some proxies reject the request for no reason.
  if (body !== undefined) headers['Content-Type'] = 'application/json'

  const controller = new AbortController()
  const timeout = window.setTimeout(
    () => controller.abort(),
    options.timeoutMs ?? DEFAULT_TIMEOUT_MS,
  )
  // Chain an external signal onto ours so a component unmount can cancel too.
  const onExternalAbort = () => controller.abort()
  signal?.addEventListener('abort', onExternalAbort)

  let response: Response
  try {
    response = await fetch(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
      // Session state lives in localStorage, not cookies, so no credentials flag
      // is needed and none is set.
      credentials: 'omit',
    })
  } catch (cause) {
    if (signal?.aborted) {
      // A cancelled request is not an error worth showing to anyone.
      throw cause
    }
    throw new ApiError(
      controller.signal.aborted
        ? `The request timed out after ${(options.timeoutMs ?? DEFAULT_TIMEOUT_MS) / 1000}s`
        : 'Could not reach the PRISM backend',
      0,
      null,
    )
  } finally {
    window.clearTimeout(timeout)
    signal?.removeEventListener('abort', onExternalAbort)
  }

  if (response.status === 204) return undefined as T

  const text = await response.text()
  let payload: unknown = null
  if (text) {
    try {
      payload = JSON.parse(text)
    } catch {
      // A non-JSON body from a failing request is still worth surfacing, but as
      // text rather than pretending it parsed.
      payload = text
    }
  }

  if (!response.ok) {
    const detail = payload as {
      message?: string
      error?: string
      traceId?: string
      violations?: ApiViolation[]
    } | null

    // 401 means the stored token is no longer usable. Clear it once, here, so
    // no component has to remember to.
    if (response.status === 401 && !anonymous) {
      clearSession()
    }

    throw new ApiError(
      detail?.message ?? detail?.error ?? `Request failed with status ${response.status}`,
      response.status,
      detail?.traceId ?? null,
      detail?.violations ?? [],
      payload,
    )
  }

  return payload as T
}

/** Builds a query string, skipping null/undefined/empty values. */
export function qs(params: Record<string, string | number | boolean | null | undefined>): string {
  const parts: string[] = []
  for (const [key, value] of Object.entries(params)) {
    if (value === null || value === undefined || value === '') continue
    parts.push(`${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
  }
  return parts.length > 0 ? `?${parts.join('&')}` : ''
}

export type { AuthResponse, AuthUser }

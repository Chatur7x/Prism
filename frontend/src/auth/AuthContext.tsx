/**
 * Authentication state.
 *
 * <p>The session is derived once at startup from localStorage and then kept in
 * React state. Role checks in the UI only ever *hide* controls; they are never
 * the authorization boundary — every backend endpoint re-checks independently.
 * Hiding a button is a courtesy to the user, not a control.
 */
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'

import { ApiError, clearSession, getStoredUser, getToken, onAuthChange, setSession } from '../api/client'
import type { AuthUser, Role } from '../api/types'
import { authApi } from '../api/endpoints'

interface AuthState {
  user: AuthUser | null
  ready: boolean
  login: (username: string, password: string) => Promise<AuthUser>
  register: (username: string, email: string, password: string) => Promise<AuthUser>
  logout: () => void
  refresh: () => Promise<void>
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(getStoredUser)
  const [ready, setReady] = useState(false)

  // A 401 anywhere in the app clears the session. Subscribing here means the
  // central client does not need to know about React.
  useEffect(() => onAuthChange((next) => setUser(next)), [])

  // Confirm the stored token is still valid before trusting the cached user.
  // Without this, a stale token would render an authenticated shell whose every
  // request then fails, which reads as a broken backend rather than an expired
  // session.
  useEffect(() => {
    let cancelled = false
    async function verify() {
      if (!getToken()) {
        if (!cancelled) setReady(true)
        return
      }
      try {
        const fresh = await authApi.me()
        if (cancelled) return
        setUser(fresh)
        // Re-store so a role change made since login is reflected.
        setSession(getToken() as string, fresh)
      } catch (error) {
        if (cancelled) return
        // 401 already cleared the session. A network failure is different: the
        // token may be fine, so keep the cached user rather than signing out.
        if (error instanceof ApiError && error.status === 0) {
          setUser(getStoredUser())
        }
      } finally {
        if (!cancelled) setReady(true)
      }
    }
    void verify()
    return () => {
      cancelled = true
    }
  }, [])

  const login = useCallback(async (username: string, password: string) => {
    const response = await authApi.login(username, password)
    setSession(response.accessToken, response.user)
    return response.user
  }, [])

  const register = useCallback(async (username: string, email: string, password: string) => {
    const response = await authApi.register(username, email, password)
    setSession(response.accessToken, response.user)
    return response.user
  }, [])

  const logout = useCallback(() => {
    clearSession()
  }, [])

  const refresh = useCallback(async () => {
    const fresh = await authApi.me()
    setUser(fresh)
  }, [])

  const value = useMemo<AuthState>(
    () => ({ user, ready, login, register, logout, refresh }),
    [user, ready, login, register, logout, refresh],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthState {
  const context = useContext(AuthContext)
  if (!context) {
    throw new Error('useAuth must be used inside an AuthProvider')
  }
  return context
}

/** True when the user holds the VERIFIER or ADMIN role. */
export function useCanVerify(): boolean {
  const { user } = useAuth()
  return user?.role === 'VERIFIER' || user?.role === 'ADMIN'
}

export function useIsAdmin(): boolean {
  return useAuth().user?.role === 'ADMIN'
}

/** Renders children only when the role allows, for hiding rather than gating. */
export function RequireRole({
  roles,
  children,
  fallback = null,
}: {
  roles: Role[]
  children: ReactNode
  fallback?: ReactNode
}) {
  const { user } = useAuth()
  if (!user || !roles.includes(user.role)) return fallback
  return <>{children}</>
}

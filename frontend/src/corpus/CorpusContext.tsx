/**
 * Selected corpus, shared across the app.
 *
 * <p>Corpus isolation is a backend invariant, so this selection is only a UI
 * default. It cannot widen access: every request still names a corpus and the
 * backend independently verifies the caller may use it.
 */
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'

import { ApiError } from '../api/client'
import { corpusApi } from '../api/endpoints'
import type { Corpus } from '../api/types'
import { useAuth } from '../auth/AuthContext'

const STORAGE_KEY = 'prism.corpusId'

interface CorpusState {
  corpora: Corpus[]
  selected: Corpus | null
  loading: boolean
  error: string | null
  select: (id: number) => void
  reload: () => Promise<void>
  /** True when the user may approve, verify, and chair. */
  canVerify: boolean
}

const CorpusContext = createContext<CorpusState | null>(null)

export function CorpusProvider({ children }: { children: ReactNode }) {
  const { user } = useAuth()
  const [corpora, setCorpora] = useState<Corpus[]>([])
  const [selectedId, setSelectedId] = useState<number | null>(() => {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? Number(raw) : null
  })
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const reload = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const list = await corpusApi.list()
      setCorpora(list)
      setSelectedId((current) => {
        if (current && list.some((c) => c.id === current)) return current
        // Fall back to the first corpus so the UI is never in a state with data
        // loaded but nothing selected. `noUncheckedIndexedAccess` makes the
        // length check necessary rather than merely tidy.
        return list[0]?.id ?? null
      })
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : 'Could not load corpora')
      setCorpora([])
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    if (user) void reload()
  }, [user, reload])

  // Persist the selection, and drop it if the user signs out.
  useEffect(() => {
    if (!user) {
      localStorage.removeItem(STORAGE_KEY)
      setCorpora([])
      setSelectedId(null)
    }
  }, [user])

  useEffect(() => {
    if (selectedId == null) localStorage.removeItem(STORAGE_KEY)
    else localStorage.setItem(STORAGE_KEY, String(selectedId))
  }, [selectedId])

  const selected = useMemo(
    () => corpora.find((c) => c.id === selectedId) ?? null,
    [corpora, selectedId],
  )

  const select = useCallback((id: number) => setSelectedId(id), [])

  const canVerify = user?.role === 'VERIFIER' || user?.role === 'ADMIN'

  const value = useMemo<CorpusState>(
    () => ({ corpora, selected, loading, error, select, reload, canVerify }),
    [corpora, selected, loading, error, select, reload, canVerify],
  )

  return <CorpusContext.Provider value={value}>{children}</CorpusContext.Provider>
}

export function useCorpus(): CorpusState {
  const context = useContext(CorpusContext)
  if (!context) {
    throw new Error('useCorpus must be used inside a CorpusProvider')
  }
  return context
}

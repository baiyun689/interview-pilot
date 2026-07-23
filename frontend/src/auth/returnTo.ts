interface ReturnLocation {
  pathname?: unknown
  search?: unknown
  hash?: unknown
}

function asLocation(value: unknown): ReturnLocation | null {
  if (!value || typeof value !== 'object') return null
  const from = (value as { from?: unknown }).from
  return from && typeof from === 'object' ? from as ReturnLocation : null
}

export function safeReturnTo(state: unknown): string {
  const from = asLocation(state)
  if (!from) return '/resumes'
  const pathname = from?.pathname
  if (
    typeof pathname !== 'string'
    || !pathname.startsWith('/')
    || pathname.startsWith('//')
    || pathname.startsWith('/\\')
    || pathname.includes('://')
  ) return '/resumes'

  const search = typeof from.search === 'string' && from.search.startsWith('?') ? from.search : ''
  const hash = typeof from.hash === 'string' && from.hash.startsWith('#') ? from.hash : ''
  return `${pathname}${search}${hash}`
}

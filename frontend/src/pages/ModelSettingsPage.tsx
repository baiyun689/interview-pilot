import { useCallback, useEffect, useRef, useState } from 'react'
import { listProviders, setDefaultProvider, testProvider } from '../api/providers'
import { ApiClientError } from '../api/request'
import type { AiProvider } from '../types/provider'

type TestStatus = 'pending' | 'success' | 'failure'

interface VisibleError {
  message: string
  traceId: string | null
}

function visibleError(error: unknown, fallback: string): VisibleError {
  if (error instanceof ApiClientError) {
    return { message: error.message, traceId: error.traceId }
  }
  return { message: fallback, traceId: null }
}

function ErrorNotice({ error }: { error: VisibleError }) {
  return (
    <div className="error-notice" role="alert">
      <p>{error.message}</p>
      {error.traceId && <small>追踪编号：{error.traceId}</small>}
    </div>
  )
}

export function ModelSettingsPage() {
  const [providers, setProviders] = useState<AiProvider[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<VisibleError | null>(null)
  const [testStatuses, setTestStatuses] = useState<Record<string, TestStatus>>({})
  const [actionErrors, setActionErrors] = useState<Record<string, VisibleError | undefined>>({})
  const [switchingId, setSwitchingId] = useState<string | null>(null)
  const testingIds = useRef(new Set<string>())
  const switching = useRef(false)

  const load = useCallback(async () => {
    setLoading(true)
    setLoadError(null)
    try {
      setProviders(await listProviders())
    } catch (error) {
      setLoadError(visibleError(error, '模型配置加载失败，请稍后重试'))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  async function handleTest(providerId: string) {
    if (testingIds.current.has(providerId)) return
    testingIds.current.add(providerId)
    setActionErrors((current) => ({ ...current, [providerId]: undefined }))
    setTestStatuses((current) => ({ ...current, [providerId]: 'pending' }))
    try {
      const result = await testProvider(providerId)
      setTestStatuses((current) => ({
        ...current,
        [providerId]: result.success ? 'success' : 'failure',
      }))
      if (!result.success) {
        setActionErrors((current) => ({
          ...current,
          [providerId]: { message: '连接失败，请稍后重试', traceId: null },
        }))
      }
    } catch (error) {
      setTestStatuses((current) => ({ ...current, [providerId]: 'failure' }))
      setActionErrors((current) => ({
        ...current,
        [providerId]: visibleError(error, '连接测试失败，请稍后重试'),
      }))
    } finally {
      testingIds.current.delete(providerId)
    }
  }

  async function handleSwitch(providerId: string) {
    if (switching.current) return
    switching.current = true
    setSwitchingId(providerId)
    setActionErrors((current) => ({ ...current, [providerId]: undefined }))
    try {
      const selected = await setDefaultProvider(providerId)
      setProviders((current) => current.map((provider) => (
        provider.id === selected.id
          ? { ...selected, defaultProvider: true }
          : { ...provider, defaultProvider: false }
      )))
    } catch (error) {
      setActionErrors((current) => ({
        ...current,
        [providerId]: visibleError(error, '默认模型切换失败，请稍后重试'),
      }))
    } finally {
      switching.current = false
      setSwitchingId(null)
    }
  }

  if (loading) return <p className="page-status" role="status">正在加载模型配置…</p>

  if (loadError) {
    return (
      <section className="state-card">
        <ErrorNotice error={loadError} />
        <button className="button button-primary" type="button" onClick={() => void load()}>
          重新加载
        </button>
      </section>
    )
  }

  return (
    <section className="settings-page">
      <header className="page-header">
        <p className="eyebrow">模型设置</p>
        <h1>选择面试使用的模型</h1>
        <p>模型已由后端安全配置。你可以测试连接，并选择之后新面试使用的默认模型。</p>
      </header>

      {providers.length === 0 ? (
        <div className="state-card">
          <h2>暂无可用的模型配置</h2>
          <p>请联系管理员在服务端完成模型配置。</p>
        </div>
      ) : (
        <div className="provider-grid">
          {providers.map((provider) => {
            const status = testStatuses[provider.id]
            const actionError = actionErrors[provider.id]
            return (
              <article className="provider-card" key={provider.id} aria-label={provider.displayName}>
                <div className="provider-card-heading">
                  <div>
                    <h2>{provider.displayName}</h2>
                    <p className="provider-id">{provider.id}</p>
                  </div>
                  <span className={`status-chip ${provider.enabled ? 'status-enabled' : 'status-disabled'}`}>
                    {provider.enabled ? '已启用' : '已停用'}
                  </span>
                </div>
                <dl className="provider-details">
                  <div>
                    <dt>模型</dt>
                    <dd>{provider.model}</dd>
                  </div>
                </dl>
                {provider.defaultProvider && <strong className="default-badge">当前默认模型</strong>}
                <div className="provider-actions">
                  <button
                    className="button button-secondary"
                    type="button"
                    disabled={!provider.enabled || status === 'pending' || Boolean(switchingId)}
                    onClick={() => void handleTest(provider.id)}
                  >
                    {status === 'pending' ? '正在测试' : '测试连接'}
                  </button>
                  {provider.enabled && !provider.defaultProvider && (
                    <button
                      className="button button-primary"
                      type="button"
                      disabled={Boolean(switchingId) || status === 'pending'}
                      onClick={() => void handleSwitch(provider.id)}
                    >
                      {switchingId === provider.id ? '正在设置' : '设为默认'}
                    </button>
                  )}
                </div>
                {status === 'success' && <p className="success-notice" role="status">连接成功</p>}
                {actionError && <ErrorNotice error={actionError} />}
              </article>
            )
          })}
        </div>
      )}
    </section>
  )
}

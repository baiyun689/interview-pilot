import { ApiClientError } from '../api/request'

export function ErrorNotice({ error }: { error: unknown }) {
  if (!error) return null
  const api = error instanceof ApiClientError ? error : null
  return <div className="error-notice" role="alert"><p>{api?.message ?? '操作失败，请稍后重试'}</p>{api?.traceId && <small>追踪编号：{api.traceId}</small>}</div>
}

export function providerSnapshot(providerId: string, modelName: string) {
  const names: Record<string, string> = { deepseek: 'DeepSeek', dashscope: 'DashScope', kimi: 'Kimi' }
  return `${names[providerId.toLowerCase()] ?? providerId} · ${modelName}`
}

export const sessionStatusLabel: Record<string, string> = {
  CANCELLED: '已终止',
  PREPARING: '题库准备中', READY: '待开始', INTERVIEWING: '面试中',
  EVALUATING: '报告生成中', COMPLETED: '已完成',
  PREPARATION_FAILED: '题库准备失败', EVALUATION_FAILED: '报告生成失败',
}
export const difficultyLabel: Record<string, string> = { EASY: '简单', MEDIUM: '中等', HARD: '困难' }

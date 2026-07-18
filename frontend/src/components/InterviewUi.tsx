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
  CREATED: '已创建', INTERVIEWING: '面试中', EVALUATING: '报告生成中', COMPLETED: '已完成', FAILED: '失败',
}
export const difficultyLabel: Record<string, string> = { EASY: '简单', MEDIUM: '中等', HARD: '困难' }
export const nextStepLabel: Record<string, string> = { FOLLOW_UP: '追问', NEXT_TOPIC: '下一主题', FINISH: '结束' }
export const adjustmentLabel: Record<string, string> = { INCREASE: '提高', KEEP: '保持', DECREASE: '降低' }

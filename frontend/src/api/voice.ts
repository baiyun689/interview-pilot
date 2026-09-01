import {
  ApiClientError,
  fetchWithAuthRetry,
  getAccessToken,
  request,
  requestWithMeta,
  type ApiResponse,
} from './request'
import type {
  QuestionSpeechView,
  VoiceCapabilities,
  VoiceRecordingUploadReceipt,
  VoiceRecordingView,
} from '../types/voice'

/** 上传进度（仅当浏览器可计算时回调）。 */
export interface UploadProgress {
  loaded: number
  total: number
}

interface ErrorBody {
  code?: unknown
  message?: unknown
  traceId?: unknown
}

function nonBlankString(value: unknown): string | null {
  return typeof value === 'string' && value.trim() ? value.trim() : null
}

function parseBody(text: string): ErrorBody | undefined {
  try {
    const parsed = JSON.parse(text) as unknown
    return parsed && typeof parsed === 'object' ? parsed as ErrorBody : undefined
  } catch {
    return undefined
  }
}

function mediaError(status: number, text: string, getHeader: (name: string) => string | null): ApiClientError {
  const body = parseBody(text)
  const traceId = nonBlankString(getHeader('X-Trace-Id')) ?? nonBlankString(body?.traceId)
  return new ApiClientError(
    status,
    nonBlankString(body?.code) ?? 'HTTP_ERROR',
    nonBlankString(body?.message) ?? '服务暂时不可用，请稍后重试',
    traceId,
  )
}

/** 能力探测接口（计划 §8.1），JWT 保护，与其他接口一致走统一 fetch 封装。 */
export function fetchVoiceCapabilities(signal?: AbortSignal): Promise<VoiceCapabilities> {
  return request('/api/voice/capabilities', { signal })
}

const AUDIO_FILE_EXTENSIONS: Record<string, string> = {
  'audio/webm': 'webm',
  'audio/ogg': 'ogg',
  'audio/mp4': 'm4a',
  'audio/wav': 'wav',
  'audio/mpeg': 'mp3',
}

function audioFileName(blob: Blob, uploadRequestId: string): string {
  const base = blob.type?.split(';')[0].trim()
  const extension = (base && AUDIO_FILE_EXTENSIONS[base]) || 'webm'
  return `voice-${uploadRequestId}.${extension}`
}

/**
 * 上传录音（计划 §8.2）：multipart/form-data，字段 uploadRequestId + 文件 audio。
 * fetch 不暴露上传进度，因此使用 XMLHttpRequest 实现 onProgress；错误处理与
 * 其余 API 保持一致（ApiClientError 携带后端稳定 code，如 VOICE_UPLOAD_TOO_LARGE）。
 */
export function uploadVoiceRecording(
  sessionId: string,
  turnNo: number,
  uploadRequestId: string,
  blob: Blob,
  onProgress?: (progress: UploadProgress) => void,
  signal?: AbortSignal,
): Promise<ApiResponse<VoiceRecordingUploadReceipt>> {
  const url = `/api/interviews/${encodeURIComponent(sessionId)}/turns/${encodeURIComponent(String(turnNo))}/voice-recordings`
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', url)
    const token = getAccessToken()
    if (token) xhr.setRequestHeader('Authorization', `Bearer ${token}`)
    if (onProgress) {
      xhr.upload.onprogress = (event) => {
        if (event.lengthComputable) onProgress({ loaded: event.loaded, total: event.total })
      }
    }
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        const body = parseBody(xhr.responseText) as VoiceRecordingUploadReceipt | undefined
        resolve({
          data: body as VoiceRecordingUploadReceipt,
          status: xhr.status,
          traceId: nonBlankString(xhr.getResponseHeader('X-Trace-Id')),
        })
      } else {
        reject(mediaError(xhr.status, xhr.responseText, (name) => xhr.getResponseHeader(name)))
      }
    }
    xhr.onerror = () => {
      reject(new ApiClientError(0, 'NETWORK_ERROR', '网络连接失败，请检查网络后重试', null))
    }
    const onAbort = () => xhr.abort()
    if (signal) signal.addEventListener('abort', onAbort, { once: true })
    xhr.onabort = () => {
      if (signal) signal.removeEventListener('abort', onAbort)
      reject(new DOMException('The operation was aborted.', 'AbortError'))
    }
    const form = new FormData()
    form.append('uploadRequestId', uploadRequestId)
    form.append('audio', blob, audioFileName(blob, uploadRequestId))
    xhr.send(form)
  })
}

/** 查询录音视图（计划 §8.3）。 */
export function getVoiceRecording(sessionId: string, recordingId: string, signal?: AbortSignal): Promise<VoiceRecordingView> {
  return request(
    `/api/interviews/${encodeURIComponent(sessionId)}/voice-recordings/${encodeURIComponent(recordingId)}`,
    { signal },
  )
}

/** 重试转写（计划 §8.3），202 成功。 */
export function retryVoiceRecording(sessionId: string, recordingId: string, signal?: AbortSignal): Promise<unknown> {
  return request(
    `/api/interviews/${encodeURIComponent(sessionId)}/voice-recordings/${encodeURIComponent(recordingId)}/retry`,
    { method: 'POST', signal },
  )
}

/** 放弃录音（计划 §8.3），204 成功。 */
export function discardVoiceRecording(sessionId: string, recordingId: string, signal?: AbortSignal): Promise<unknown> {
  return request(
    `/api/interviews/${encodeURIComponent(sessionId)}/voice-recordings/${encodeURIComponent(recordingId)}/discard`,
    { method: 'POST', signal },
  )
}

/**
 * 获取问题语音视图（计划 §8.5）。保留 202 状态：调用方凭 status 区分
 * 合成中（202）与就绪/失败（200）。
 */
export function getQuestionSpeech(sessionId: string, turnNo: number, signal?: AbortSignal): Promise<ApiResponse<QuestionSpeechView>> {
  return requestWithMeta(
    `/api/interviews/${encodeURIComponent(sessionId)}/turns/${encodeURIComponent(String(turnNo))}/speech`,
    { signal },
  )
}

/** 重新合成问题语音（计划 §8.5），202 成功。 */
export function retryQuestionSpeech(sessionId: string, turnNo: number, signal?: AbortSignal): Promise<unknown> {
  return request(
    `/api/interviews/${encodeURIComponent(sessionId)}/turns/${encodeURIComponent(String(turnNo))}/speech/retry`,
    { method: 'POST', signal },
  )
}

/**
 * 拉取语音媒体二进制（计划 §8.5 媒体端点）。后端只接受 Authorization 头
 * （见 JwtAuthenticationFilter），`<audio src>` 无法携带该头，因此这里先带
 * 认证头 fetch 成 Blob，再由调用方以 Object URL 播放（范围请求不跨网络，短音频无影响）。
 */
export async function fetchVoiceMediaBlob(mediaUrl: string, signal?: AbortSignal): Promise<Blob> {
  let response: Response
  try {
    response = await fetchWithAuthRetry(mediaUrl, { signal })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new ApiClientError(0, 'NETWORK_ERROR', '网络连接失败，请检查网络后重试', null)
  }
  if (!response.ok) {
    throw mediaError(response.status, await safeText(response), (name) => response.headers.get(name))
  }
  return response.blob()
}

async function safeText(response: Response): Promise<string> {
  try {
    return await response.text()
  } catch {
    return ''
  }
}

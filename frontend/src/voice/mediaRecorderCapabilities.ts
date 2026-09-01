/**
 * 浏览器录音能力探测（计划 §13.3）：按固定优先级挑选首个被 MediaRecorder 支持的
 * 音频 MIME。纯函数、无副作用，测试通过注入假 isTypeSupported 完成。
 */

/** 首选顺序：Opus/WebM → 裸 WebM → Ogg/Opus → MP4（计划 §2.4，页面默认 Opus/WebM）。 */
export const PREFERRED_MIME_TYPES = [
  'audio/webm;codecs=opus',
  'audio/webm',
  'audio/ogg;codecs=opus',
  'audio/mp4',
] as const

export interface MediaRecorderCapability {
  /** 是否存在可用的录音格式 */
  supported: boolean
  /** 探测到的第一个可用 MIME；不支持时为 null */
  mimeType: string | null
}

export type IsTypeSupported = (mimeType: string) => boolean

/** MediaRecorder 构造函数的探测等价物（允许缺失 isTypeSupported，运行时防御）。 */
export interface MediaRecorderCapabilitySource {
  isTypeSupported?: IsTypeSupported
}

function toIsTypeSupported(
  recorder: MediaRecorderCapabilitySource | null | undefined,
): IsTypeSupported | null {
  if (!recorder || typeof recorder.isTypeSupported !== 'function') return null
  return (mimeType: string) => recorder.isTypeSupported!(mimeType)
}

/**
 * 探测最佳可用录音 MIME。
 *
 * @param recorder 浏览器 MediaRecorder 构造函数（或其 isTypeSupported 等价物）；
 *   缺失或 isTypeSupported 不可用时按不支持处理。
 */
export function detectMediaRecorderCapabilities(
  recorder: MediaRecorderCapabilitySource | null | undefined,
): MediaRecorderCapability {
  const isTypeSupported = toIsTypeSupported(recorder)
  if (!isTypeSupported) return { supported: false, mimeType: null }
  for (const mimeType of PREFERRED_MIME_TYPES) {
    let accepted = false
    try {
      accepted = isTypeSupported(mimeType)
    } catch {
      // 个别浏览器对未知 MIME 字符串抛异常：按不支持处理，继续探测后续格式
      accepted = false
    }
    if (accepted) return { supported: true, mimeType }
  }
  return { supported: false, mimeType: null }
}

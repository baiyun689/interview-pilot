/**
 * 生成客户端请求 ID（UUID v4）。
 *
 * 优先使用 crypto.randomUUID（安全上下文 https / localhost）；非安全上下文
 * （如局域网 http 联调）下 crypto.randomUUID 不可用，降级为随机数拼装的
 * UUID v4 格式字符串。上游以该值做幂等（答案 requestId / 上传 uploadRequestId），
 * 只需保证足够随机即可。
 */
export function createRequestId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    try {
      return crypto.randomUUID()
    } catch {
      // 降级：个别环境访问 randomUUID 可能抛错
    }
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (char) => {
    const random = (Math.random() * 16) | 0
    const value = char === 'x' ? random : (random & 0x3) | 0x8
    return value.toString(16)
  })
}

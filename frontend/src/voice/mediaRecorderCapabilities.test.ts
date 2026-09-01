import { describe, expect, it, vi } from 'vitest'
import { PREFERRED_MIME_TYPES, detectMediaRecorderCapabilities } from './mediaRecorderCapabilities'

describe('detectMediaRecorderCapabilities', () => {
  it('MediaRecorder 缺失或未提供 isTypeSupported 时判定不支持', () => {
    expect(detectMediaRecorderCapabilities(null)).toEqual({ supported: false, mimeType: null })
    expect(detectMediaRecorderCapabilities(undefined)).toEqual({ supported: false, mimeType: null })
    expect(detectMediaRecorderCapabilities({})).toEqual({ supported: false, mimeType: null })
  })

  it('首选 Opus/WebM（audio/webm;codecs=opus）', () => {
    const isTypeSupported = vi.fn((mime: string) => mime === 'audio/webm;codecs=opus')
    expect(detectMediaRecorderCapabilities({ isTypeSupported })).toEqual({
      supported: true,
      mimeType: 'audio/webm;codecs=opus',
    })
    expect(isTypeSupported).toHaveBeenCalledTimes(1)
  })

  it('无 Opus/WebM 时依次回退 webm、ogg/opus、mp4', () => {
    const supported = new Set(['audio/mp4'])
    const isTypeSupported = vi.fn((mime: string) => supported.has(mime))
    expect(detectMediaRecorderCapabilities({ isTypeSupported })).toEqual({
      supported: true,
      mimeType: 'audio/mp4',
    })
    expect(isTypeSupported.mock.calls.map(([mime]) => mime)).toEqual([
      'audio/webm;codecs=opus',
      'audio/webm',
      'audio/ogg;codecs=opus',
      'audio/mp4',
    ])
  })

  it('全部不支持时返回不支持且 mimeType 为 null', () => {
    const isTypeSupported = vi.fn(() => false)
    const result = detectMediaRecorderCapabilities({ isTypeSupported })
    expect(result).toEqual({ supported: false, mimeType: null })
    expect(isTypeSupported).toHaveBeenCalledTimes(PREFERRED_MIME_TYPES.length)
  })

  it('isTypeSupported 抛异常时按不支持处理并继续探测后续格式', () => {
    const isTypeSupported = vi.fn((mime: string) => {
      if (mime === 'audio/webm;codecs=opus') throw new TypeError('bad mime')
      return mime === 'audio/webm'
    })
    expect(detectMediaRecorderCapabilities({ isTypeSupported })).toEqual({
      supported: true,
      mimeType: 'audio/webm',
    })
  })
})

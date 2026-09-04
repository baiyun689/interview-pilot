package interview.pilot.interview.domain;

public enum InputMode {
  /** 键盘文字作答。 */
  TEXT,
  /** 整段录音文件作答：上传录音 → RabbitMQ 异步转写 → 确认提交（需绑定 recordingId）。 */
  VOICE,
  /**
   * 实时语音作答：麦克风 PCM 经 WebSocket 流式上行，服务端 ASR 实时转写，
   * 停顿自动/手动提交，答案文本直接驱动回合，不产生也不绑定录音文件。
   */
  VOICE_REALTIME
}

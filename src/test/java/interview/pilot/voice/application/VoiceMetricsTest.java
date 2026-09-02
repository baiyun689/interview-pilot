package interview.pilot.voice.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class VoiceMetricsTest {

  @Test
  void countsUploadsPerResultAndMime() {
    var meters = new SimpleMeterRegistry();
    var metrics = new VoiceMetrics(meters);

    metrics.upload("accepted", "audio/webm", 2048L, 30_000L);
    metrics.upload("accepted", "audio/webm", 1024L, 15_000L);
    metrics.upload("rejected", "audio/mp4", null, null);

    assertThat(meters.counter("interview_pilot.voice.upload.total",
        "result", "accepted", "mime", "audio/webm").count()).isEqualTo(2);
    assertThat(meters.counter("interview_pilot.voice.upload.total",
        "result", "rejected", "mime", "audio/mp4").count()).isEqualTo(1);
  }

  @Test
  void recordsUploadBytesAndRecordingDuration() {
    var meters = new SimpleMeterRegistry();
    var metrics = new VoiceMetrics(meters);

    metrics.upload("accepted", "audio/webm", 4096L, 60_000L);

    assertThat(meters.counter("interview_pilot.voice.upload.bytes").count()).isEqualTo(4096);
    assertThat(meters.summary("interview_pilot.voice.recording.duration").takeSnapshot()
        .max()).isEqualTo(60_000d);
  }

  @Test
  void timesAsrPerProviderModelResultAndCountsTranscriptCharacters() {
    var meters = new SimpleMeterRegistry();
    var metrics = new VoiceMetrics(meters);

    metrics.asr("dashscope", "fun-asr-flash-2026-06-15", "success",
        Duration.ofSeconds(3), 120);
    metrics.asr("dashscope", "fun-asr-flash-2026-06-15", "failure",
        Duration.ofSeconds(9), null);

    assertThat(meters.timer("interview_pilot.voice.asr.duration",
        "provider", "dashscope", "model", "fun-asr-flash-2026-06-15", "result", "success")
        .count()).isEqualTo(1);
    assertThat(meters.timer("interview_pilot.voice.asr.duration",
        "provider", "dashscope", "model", "fun-asr-flash-2026-06-15", "result", "failure")
        .count()).isEqualTo(1);
    assertThat(meters.counter("interview_pilot.voice.asr.transcript.characters")
        .count()).isEqualTo(120);
  }

  @Test
  void timesTtsAndCountsAudioBytes() {
    var meters = new SimpleMeterRegistry();
    var metrics = new VoiceMetrics(meters);

    metrics.tts("dashscope", "cosyvoice-v3-flash", "success",
        Duration.ofSeconds(2), 88_000L);

    assertThat(meters.timer("interview_pilot.voice.tts.duration",
        "provider", "dashscope", "model", "cosyvoice-v3-flash", "result", "success")
        .count()).isEqualTo(1);
    assertThat(meters.counter("interview_pilot.voice.tts.audio.bytes").count())
        .isEqualTo(88_000);
  }

  @Test
  void countsRetriesPerTaskTypeAndReason() {
    var meters = new SimpleMeterRegistry();
    var metrics = new VoiceMetrics(meters);

    metrics.retry("voice_transcription", "manual");
    metrics.retry("voice_transcription", "manual");
    metrics.retry("question_speech_synthesis", "exhausted");

    assertThat(meters.counter("interview_pilot.voice.retry.total",
        "taskType", "voice_transcription", "reason", "manual").count()).isEqualTo(2);
    assertThat(meters.counter("interview_pilot.voice.retry.total",
        "taskType", "question_speech_synthesis", "reason", "exhausted").count()).isEqualTo(1);
  }

  @Test
  void countsFallbacksPerReason() {
    var meters = new SimpleMeterRegistry();
    var metrics = new VoiceMetrics(meters);

    metrics.fallback("user_discard");
    metrics.fallback("user_discard");

    assertThat(meters.counter("interview_pilot.voice.fallback.total",
        "reason", "user_discard").count()).isEqualTo(2);
  }

  @Test
  void boundsPollutingTagValues() {
    var meters = new SimpleMeterRegistry();
    var metrics = new VoiceMetrics(meters);

    metrics.upload("Rejected!", "audio/../webm", 1L, 1L);
    metrics.asr("dashscope$", "a".repeat(100), "success", Duration.ZERO, 1);
    metrics.retry("", "EXHAUSTED!");
    metrics.fallback(null);

    // strict values outside the enum shape land on "other"; free-form values get normalized
    // (mime keeps its "/", the mime shape in "audio/../webm" survives; provider loses the "$").
    assertThat(meters.counter("interview_pilot.voice.upload.total",
        "result", "other", "mime", "audio/../webm").count()).isEqualTo(1);
    assertThat(meters.timer("interview_pilot.voice.asr.duration",
        "provider", "dashscope_", "model", "other", "result", "success").count()).isEqualTo(1);
    assertThat(meters.counter("interview_pilot.voice.retry.total",
        "taskType", "unknown", "reason", "other").count()).isEqualTo(1);
    assertThat(meters.counter("interview_pilot.voice.fallback.total",
        "reason", "unknown").count()).isEqualTo(1);
  }
}

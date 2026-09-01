package interview.pilot.voice.infrastructure;

import java.nio.file.Path;

import interview.pilot.voice.domain.ProbedAudio;

/**
 * Probe seam that determines the real container media type and duration of an audio file.
 * Rejections are {@code VoiceMediaUnsupportedException} (deterministic) or
 * {@code VoiceMediaProbeException} (operational).
 */
public interface AudioProbe {

  ProbedAudio probe(Path file);
}

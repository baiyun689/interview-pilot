package interview.pilot.voice.domain;

import java.time.Duration;

/** Result of probing an audio file: its real container media type and duration. */
public record ProbedAudio(String mediaType, Duration duration) {}

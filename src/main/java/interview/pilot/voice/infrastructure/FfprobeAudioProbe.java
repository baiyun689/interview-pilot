package interview.pilot.voice.infrastructure;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import interview.pilot.voice.domain.ProbedAudio;
import interview.pilot.voice.domain.VoiceMediaProbeException;
import interview.pilot.voice.domain.VoiceMediaUnsupportedException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link AudioProbe} backed by the fixed {@code ffprobe} executable (installed in the
 * production image) with a fixed argument array, no shell and a 3-second timeout (plan §14).
 * The probed file is passed as a single argument — it is an internal staged file, never a
 * user-controlled string.
 *
 * <p>The JSON output ({@code format_name}, {@code duration}, stream codec types) is mapped to
 * the plan §2.4 whitelist: matroska/webm → audio/webm, ogg → audio/ogg, mp4/m4a → audio/mp4,
 * wav → audio/wav, mp3 → audio/mpeg; anything else, a missing audio stream or a missing
 * duration is unsupported. A non-zero exit code means ffprobe could not parse the file as a
 * supported media type and is likewise treated as unsupported.
 */
public final class FfprobeAudioProbe implements AudioProbe {

  static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);
  private static final String EXECUTABLE = "ffprobe";
  private static final List<String> BASE_ARGS = List.of(
      EXECUTABLE, "-v", "error", "-print_format", "json", "-show_format", "-show_streams");

  private final ProcessRunner runner;
  private final Duration timeout;
  private final ObjectMapper json;

  public FfprobeAudioProbe(ObjectMapper json) {
    this(FfprobeAudioProbe::start, DEFAULT_TIMEOUT, json);
  }

  FfprobeAudioProbe(ProcessRunner runner, Duration timeout, ObjectMapper json) {
    this.runner = Objects.requireNonNull(runner, "runner");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public ProbedAudio probe(Path file) {
    Objects.requireNonNull(file, "file");
    Process process;
    try {
      process = runner.start(command(file));
    } catch (IOException exception) {
      throw new VoiceMediaProbeException("Unable to start ffprobe", exception);
    }
    try {
      boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!finished) {
        process.destroyForcibly();
        throw new VoiceMediaProbeException(
            "ffprobe did not finish within " + timeout.toSeconds() + "s", null);
      }
      if (process.exitValue() != 0) {
        throw new VoiceMediaUnsupportedException();
      }
      return parse(process.getInputStream().readAllBytes());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      throw new VoiceMediaProbeException("ffprobe was interrupted", exception);
    } catch (IOException exception) {
      throw new VoiceMediaProbeException("Unable to read ffprobe output", exception);
    }
  }

  private static List<String> command(Path file) {
    List<String> command = new ArrayList<>(BASE_ARGS.size() + 1);
    command.addAll(BASE_ARGS);
    command.add(file.toString());
    return List.copyOf(command);
  }

  private ProbedAudio parse(byte[] output) {
    JsonNode root;
    try {
      root = json.readTree(output);
    } catch (JacksonException exception) {
      throw new VoiceMediaUnsupportedException();
    }
    if (root == null || root.get("format") == null
        || !hasAudioStream(root.get("streams"))) {
      throw new VoiceMediaUnsupportedException();
    }
    JsonNode format = root.get("format");
    String mediaType = mapToMediaType(format.get("format_name"));
    Duration duration = duration(format.get("duration"));
    if (mediaType == null || duration == null) {
      throw new VoiceMediaUnsupportedException();
    }
    return new ProbedAudio(mediaType, duration);
  }

  private static String mapToMediaType(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    String name = node.asString();
    if (name.contains("webm") || name.contains("matroska")) {
      return "audio/webm";
    }
    if (name.contains("ogg")) {
      return "audio/ogg";
    }
    if (name.contains("mp4") || name.contains("m4a")) {
      return "audio/mp4";
    }
    if (name.contains("wav")) {
      return "audio/wav";
    }
    if (name.contains("mp3")) {
      return "audio/mpeg";
    }
    return null;
  }

  private static boolean hasAudioStream(JsonNode streams) {
    if (streams == null || !streams.isArray()) {
      return false;
    }
    for (JsonNode stream : streams) {
      JsonNode codecType = stream.get("codec_type");
      if (codecType != null && "audio".equals(codecType.asString())) {
        return true;
      }
    }
    return false;
  }

  private static Duration duration(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    try {
      double seconds = Double.parseDouble(node.asString().trim());
      if (!Double.isFinite(seconds) || seconds < 0) {
        return null;
      }
      return Duration.ofMillis(Math.round(seconds * 1000));
    } catch (NumberFormatException exception) {
      return null;
    }
  }

  private static Process start(List<String> command) throws IOException {
    return new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
  }

  @FunctionalInterface
  interface ProcessRunner {
    Process start(List<String> command) throws IOException;
  }
}

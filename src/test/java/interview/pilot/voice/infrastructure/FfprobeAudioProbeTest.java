package interview.pilot.voice.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import interview.pilot.voice.domain.ProbedAudio;
import interview.pilot.voice.domain.VoiceMediaProbeException;
import interview.pilot.voice.domain.VoiceMediaUnsupportedException;
import tools.jackson.databind.ObjectMapper;

class FfprobeAudioProbeTest {
  private final ObjectMapper json = new ObjectMapper();
  private final Duration timeout = Duration.ofMillis(200);

  @Test
  void mapsWebmContainersToAudioWebm() {
    assertThat(probeOutput(ffprobeOutput("matroska,webm", "12.340000")))
        .isEqualTo(new ProbedAudio("audio/webm", Duration.ofMillis(12340)));
  }

  @Test
  void mapsOggContainersToAudioOgg() {
    assertThat(probeOutput(ffprobeOutput("ogg", "3.500000")))
        .isEqualTo(new ProbedAudio("audio/ogg", Duration.ofMillis(3500)));
  }

  @Test
  void mapsMp4AndM4aContainersToAudioMp4() {
    assertThat(probeOutput(ffprobeOutput("mov,mp4,m4a,3gp,3g2,mj2", "4.250000")))
        .isEqualTo(new ProbedAudio("audio/mp4", Duration.ofMillis(4250)));
  }

  @Test
  void mapsWavContainersToAudioWav() {
    assertThat(probeOutput(ffprobeOutput("wav", "0.500000")))
        .isEqualTo(new ProbedAudio("audio/wav", Duration.ofMillis(500)));
  }

  @Test
  void mapsMp3ContainersToAudioMpeg() {
    assertThat(probeOutput(ffprobeOutput("mp3", "180.000000")))
        .isEqualTo(new ProbedAudio("audio/mpeg", Duration.ofSeconds(180)));
  }

  @Test
  void rejectsContainersOutsideTheWhitelist() {
    assertThatThrownBy(() -> probeOutput(ffprobeOutput("flac", "10.000000")))
        .isInstanceOf(VoiceMediaUnsupportedException.class);
  }

  @Test
  void rejectsGarbageOutput() {
    assertThatThrownBy(() -> probeOutput("this is not json"))
        .isInstanceOf(VoiceMediaUnsupportedException.class);
  }

  @Test
  void rejectsJsonWithoutAFormatSection() {
    assertThatThrownBy(() -> probeOutput("{\"streams\":[]}"))
        .isInstanceOf(VoiceMediaUnsupportedException.class);
  }

  @Test
  void rejectsMediaWithoutAnAudioStream() {
    assertThatThrownBy(() -> probeOutput(probeOutput("wav", "0.500000", "{\"codec_type\":\"video\"}")))
        .isInstanceOf(VoiceMediaUnsupportedException.class);
  }

  @Test
  void rejectsMediaWithoutADuration() {
    assertThatThrownBy(() -> probeOutput(probeOutput("wav", "", "{\"codec_type\":\"audio\"}")))
        .isInstanceOf(VoiceMediaUnsupportedException.class);
  }

  @Test
  void rejectsMediaWhenFfprobeFailsToParseIt() {
    var probe = new FfprobeAudioProbe(
        command -> new FakeProcess("{\"streams\":[]}".getBytes(StandardCharsets.UTF_8), true, 1),
        timeout, json);

    assertThatThrownBy(() -> probe.probe(Path.of("media.bin")))
        .isInstanceOf(VoiceMediaUnsupportedException.class);
  }

  @Test
  void failsWithATimeoutErrorWhenFfprobeDoesNotExitAndDestroysTheProcess() {
    FakeProcess process = new FakeProcess(new byte[0], false, 0);
    var probe = new FfprobeAudioProbe(command -> process, timeout, json);

    assertThatThrownBy(() -> probe.probe(Path.of("media.bin")))
        .isInstanceOf(VoiceMediaProbeException.class)
        .hasMessageContaining("did not finish");
    assertThat(process.destroyed()).isTrue();
  }

  @Test
  void failsWithAClearErrorWhenTheFfprobeExecutableCannotBeStarted() {
    var probe = new FfprobeAudioProbe(command -> {
      throw new IOException("cannot run program \"ffprobe\": CreateProcess error=2");
    }, timeout, json);

    assertThatThrownBy(() -> probe.probe(Path.of("media.bin")))
        .isInstanceOf(VoiceMediaProbeException.class)
        .hasMessageContaining("ffprobe");
  }

  @Test
  void passesTheFilePathAsASingleArgumentWithoutAnyShell() {
    Path file = Path.of("recordings", "user supplied name.webm");
    AtomicReference<List<String>> captured = new AtomicReference<>();
    var probe = new FfprobeAudioProbe(command -> {
      captured.set(command);
      return new FakeProcess(
          ffprobeOutput("matroska,webm", "1.000000").getBytes(StandardCharsets.UTF_8), true, 0);
    }, timeout, json);

    probe.probe(file);

    assertThat(captured.get()).containsExactly(
        "ffprobe", "-v", "error", "-print_format", "json", "-show_format", "-show_streams",
        file.toString());
  }

  @Test
  void probesARealWavFileWhenFfprobeIsInstalled() throws Exception {
    Assumptions.assumeTrue(ffprobeAvailable(), "ffprobe is not installed on this machine");
    Path wav = Files.createTempFile("voice-probe-", ".wav");
    try {
      Files.write(wav, wavBytes(8000, 0.5));
      var probe = new FfprobeAudioProbe(new ObjectMapper());

      ProbedAudio probed = probe.probe(wav);

      assertThat(probed.mediaType()).isEqualTo("audio/wav");
      assertThat(probed.duration().toMillis()).isBetween(400L, 600L);
    } finally {
      Files.deleteIfExists(wav);
    }
  }

  private ProbedAudio probeOutput(String output) {
    FakeProcess process = new FakeProcess(output.getBytes(StandardCharsets.UTF_8), true, 0);
    var probe = new FfprobeAudioProbe(command -> process, timeout, json);
    return probe.probe(Path.of("media.bin"));
  }

  private static String ffprobeOutput(String formatName, String duration) {
    return probeOutput(formatName, duration, "{\"codec_type\":\"audio\"}");
  }

  private static String probeOutput(String formatName, String duration, String streams) {
    return "{\"streams\":[" + streams + "],\"format\":{\"format_name\":\""
        + formatName + "\",\"duration\":\"" + duration + "\"}}";
  }

  private static boolean ffprobeAvailable() {
    try {
      Process process = new ProcessBuilder("ffprobe", "-version").start();
      return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
    } catch (IOException | InterruptedException exception) {
      if (exception instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return false;
    }
  }

  /** 16-bit PCM mono RIFF/WAVE with the given sample rate and length. */
  private static byte[] wavBytes(int sampleRate, double seconds) {
    int dataSize = (int) (sampleRate * 2 * seconds);
    byte[] wav = new byte[44 + dataSize];
    writeAscii(wav, 0, "RIFF");
    writeInt(wav, 4, 36 + dataSize);
    writeAscii(wav, 8, "WAVEfmt ");
    writeInt(wav, 16, 16);
    writeShort(wav, 20, 1);
    writeShort(wav, 22, 1);
    writeInt(wav, 24, sampleRate);
    writeInt(wav, 28, sampleRate * 2);
    writeShort(wav, 32, 2);
    writeShort(wav, 34, 16);
    writeAscii(wav, 36, "data");
    writeInt(wav, 40, dataSize);
    return wav;
  }

  private static void writeAscii(byte[] target, int offset, String value) {
    for (int i = 0; i < value.length(); i++) {
      target[offset + i] = (byte) value.charAt(i);
    }
  }

  private static void writeInt(byte[] target, int offset, int value) {
    target[offset] = (byte) value;
    target[offset + 1] = (byte) (value >>> 8);
    target[offset + 2] = (byte) (value >>> 16);
    target[offset + 3] = (byte) (value >>> 24);
  }

  private static void writeShort(byte[] target, int offset, int value) {
    target[offset] = (byte) value;
    target[offset + 1] = (byte) (value >>> 8);
  }

  private static final class FakeProcess extends Process {
    private final byte[] stdout;
    private final boolean exits;
    private final int exitCode;
    private boolean destroyed;

    FakeProcess(byte[] stdout, boolean exits, int exitCode) {
      this.stdout = stdout;
      this.exits = exits;
      this.exitCode = exitCode;
    }

    @Override
    public OutputStream getOutputStream() {
      return OutputStream.nullOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return new ByteArrayInputStream(stdout);
    }

    @Override
    public InputStream getErrorStream() {
      return InputStream.nullInputStream();
    }

    @Override
    public int waitFor() {
      return exitCode;
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) {
      return exits;
    }

    @Override
    public int exitValue() {
      return exitCode;
    }

    @Override
    public void destroy() {
      destroyed = true;
    }

    @Override
    public boolean isAlive() {
      return false;
    }

    boolean destroyed() {
      return destroyed;
    }
  }
}

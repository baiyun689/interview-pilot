package interview.pilot.voice.realtime.audio;

/**
 * Minimal little-endian WAV (PCM s16le, mono) encoder for realtime TTS bytes.
 *
 * <p>The browser {@code <audio>} element cannot play raw PCM; wrapping the 16-bit mono samples in
 * a 44-byte RIFF/WAVE header yields a data URL that plays everywhere without a codec. Kept
 * dependency-free and allocation-explicit (the TTS output is short, one question at a time).
 */
public final class PcmWavEncoder {

  private static final int BITS_PER_SAMPLE = 16;
  private static final int CHANNELS = 1;

  private PcmWavEncoder() {
  }

  public static byte[] toWav(byte[] pcm, int sampleRate) {
    if (pcm == null || pcm.length == 0) {
      return new byte[0];
    }
    int byteRate = sampleRate * CHANNELS * BITS_PER_SAMPLE / 8;
    int blockAlign = CHANNELS * BITS_PER_SAMPLE / 8;
    byte[] wav = new byte[pcm.length + 44];
    int pos = 0;
    pos = putAscii(wav, pos, "RIFF");
    pos = putIntLe(wav, pos, pcm.length + 36);
    pos = putAscii(wav, pos, "WAVE");
    pos = putAscii(wav, pos, "fmt ");
    pos = putIntLe(wav, pos, 16); // PCM fmt chunk size
    pos = putShortLe(wav, pos, (short) 1); // PCM format tag
    pos = putShortLe(wav, pos, (short) CHANNELS);
    pos = putIntLe(wav, pos, sampleRate);
    pos = putIntLe(wav, pos, byteRate);
    pos = putShortLe(wav, pos, (short) blockAlign);
    pos = putShortLe(wav, pos, (short) BITS_PER_SAMPLE);
    pos = putAscii(wav, pos, "data");
    pos = putIntLe(wav, pos, pcm.length);
    System.arraycopy(pcm, 0, wav, pos, pcm.length);
    return wav;
  }

  private static int putAscii(byte[] buf, int pos, String text) {
    for (int i = 0; i < text.length(); i++) {
      buf[pos + i] = (byte) text.charAt(i);
    }
    return pos + text.length();
  }

  private static int putIntLe(byte[] buf, int pos, int value) {
    buf[pos] = (byte) (value & 0xFF);
    buf[pos + 1] = (byte) ((value >> 8) & 0xFF);
    buf[pos + 2] = (byte) ((value >> 16) & 0xFF);
    buf[pos + 3] = (byte) ((value >> 24) & 0xFF);
    return pos + 4;
  }

  private static int putShortLe(byte[] buf, int pos, short value) {
    buf[pos] = (byte) (value & 0xFF);
    buf[pos + 1] = (byte) ((value >> 8) & 0xFF);
    return pos + 2;
  }
}

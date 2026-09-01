package interview.pilot.voice.storage;

import java.io.InputStream;

import interview.pilot.voice.domain.StoredVoiceMedia;
import interview.pilot.voice.domain.VoiceMediaKey;
import interview.pilot.voice.domain.VoiceMediaResource;
import interview.pilot.voice.domain.VoiceMediaTooLargeException;
import interview.pilot.voice.domain.VoiceMediaUnsupportedException;

/**
 * Secure storage seam for voice media (plan §5.4).
 *
 * <p>{@code store} consumes the source stream completely (and closes it), enforcing
 * {@code maxBytes} while copying and rejecting unsupported media via probing before any file
 * becomes visible; the final file is installed atomically. Storage keys are immutable and are
 * the only way files are addressed — no static or public file paths exist.
 */
public interface VoiceMediaStore {

  StoredVoiceMedia store(VoiceMediaKey key, InputStream source, long maxBytes);

  VoiceMediaResource open(String storageKey);

  void delete(String storageKey);
}

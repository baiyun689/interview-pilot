package interview.pilot.voice.storage;

import java.io.InputStream;
import java.nio.file.Path;

import interview.pilot.voice.domain.ProbedAudio;
import interview.pilot.voice.domain.StoredVoiceMedia;
import interview.pilot.voice.domain.VoiceMediaKey;
import interview.pilot.voice.domain.VoiceMediaNotFoundException;
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
 *
 * <p>Contract for {@code open}/{@code delete}: {@link VoiceMediaNotFoundException} is thrown
 * only when the storage key is well-formed, all security checks (symlink, hard link, private
 * permissions, file kind) passed and the media file is simply absent. Cleanup (Task 11)
 * may therefore treat it as "file already gone" (plan §14). Security rejections surface as
 * {@link IllegalArgumentException} with distinct messages and must never be treated as
 * absence; I/O failures surface as {@link VoiceMediaStorageException}.
 */
public interface VoiceMediaStore {

  StoredVoiceMedia store(VoiceMediaKey key, InputStream source, long maxBytes);

  /**
   * Installs an already-staged file under the immutable key. The caller has already bounded
   * the size (still enforced defensively) and probed the media — this variant performs the
   * key/security validation and the atomic replacement only: no copying into an internal
   * temp file and no probing. This is the upload module's phase-3 path, where a probe inside
   * the database transaction would be slow work (plan §9); the stream variant remains for
   * callers that have not staged anything (Task 7's TTS audio).
   *
   * <p>The staged file is CONSUMED on success (moved into place; the path no longer exists);
   * on rejection the caller retains ownership and must clean it up. Rewrite semantics are
   * identical to the stream variant: a reused key atomically replaces the previous file.
   *
   * @param stagedFile the caller's staged media file (a regular file, never user-controlled)
   * @param probed the media type and duration the caller already verified
   */
  StoredVoiceMedia store(VoiceMediaKey key, Path stagedFile, long maxBytes, ProbedAudio probed);

  VoiceMediaResource open(String storageKey);

  void delete(String storageKey);
}

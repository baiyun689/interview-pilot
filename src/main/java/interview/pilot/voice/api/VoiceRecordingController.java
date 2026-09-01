package interview.pilot.voice.api;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import interview.pilot.auth.application.CurrentUserProvider;
import interview.pilot.voice.application.VoiceAnswerModule;
import interview.pilot.voice.application.VoiceRecordingReceipt;
import interview.pilot.voice.application.VoiceRecordingView;

/**
 * Voice recording endpoints (plan §8.2/§8.3). The module maps every rejection to a stable
 * BusinessException code handled by the global advice; upload limits are enforced by the
 * module and the media store, not by Spring multipart settings (Task 12 owns those).
 */
@RestController
@RequestMapping("/api/interviews")
@ConditionalOnProperty(prefix = "app.voice", name = "enabled", havingValue = "true")
public class VoiceRecordingController {

  private final VoiceAnswerModule module;
  private final CurrentUserProvider currentUser;

  public VoiceRecordingController(VoiceAnswerModule module, CurrentUserProvider currentUser) {
    this.module = module;
    this.currentUser = currentUser;
  }

  @PostMapping(value = "/{sessionId}/turns/{turnNo}/voice-recordings",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.ACCEPTED)
  public VoiceRecordingReceipt accept(
      @PathVariable UUID sessionId,
      @PathVariable int turnNo,
      @RequestParam UUID uploadRequestId,
      @RequestPart("audio") MultipartFile audio) {
    return module.accept(currentUser.require(), sessionId, turnNo, uploadRequestId, audio);
  }

  @GetMapping("/{sessionId}/voice-recordings/{recordingId}")
  public VoiceRecordingView get(
      @PathVariable UUID sessionId, @PathVariable UUID recordingId) {
    return module.get(currentUser.require(), sessionId, recordingId);
  }

  @PostMapping("/{sessionId}/voice-recordings/{recordingId}/retry")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void retry(@PathVariable UUID sessionId, @PathVariable UUID recordingId) {
    module.retry(currentUser.require(), sessionId, recordingId);
  }

  @PostMapping("/{sessionId}/voice-recordings/{recordingId}/discard")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void discard(@PathVariable UUID sessionId, @PathVariable UUID recordingId) {
    module.discard(currentUser.require(), sessionId, recordingId);
  }
}

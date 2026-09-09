package interview.pilot.interview.api;

import java.util.UUID;

import interview.pilot.interview.domain.InputMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SubmitAnswerRequest(
    @NotNull UUID requestId,
    @NotBlank @Size(max = 20_000) String answer,
    InputMode inputMode,
    UUID recordingId,
    Integer expectedTurnNo,
    Long sessionVersion) {

  public SubmitAnswerRequest {
    answer = answer == null ? null : answer.trim();
    inputMode = inputMode == null ? InputMode.TEXT : inputMode;
  }

  public SubmitAnswerRequest(UUID requestId, String answer) {
    this(requestId, answer, null, null);
  }
  public SubmitAnswerRequest(UUID requestId, String answer, InputMode inputMode, UUID recordingId) {
    this(requestId, answer, inputMode, recordingId, null, null);
  }
}

package interview.pilot.interview.api;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SubmitAnswerRequest(
    @NotNull UUID requestId,
    @NotBlank @Size(max = 20_000) String answer) {

  public SubmitAnswerRequest {
    answer = answer == null ? null : answer.trim();
  }
}

package interview.pilot.common.exception;

import java.util.UUID;

import org.springframework.web.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import interview.pilot.common.result.ApiError;
import interview.pilot.ai.AiGatewayException;
import interview.pilot.ai.AiStructuredOutputException;
import interview.pilot.ai.provider.AiProviderException;
import interview.pilot.voice.domain.VoiceMediaProbeException;
import interview.pilot.voice.domain.VoiceMediaStorageException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ApiError> handleBusinessException(
      BusinessException exception,
      HttpServletRequest request) {
    return ResponseEntity.status(exception.status())
        .body(error(exception.code(), exception.getMessage(), request));
  }

  @ExceptionHandler(AiProviderException.class)
  public ResponseEntity<ApiError> handleAiProviderException(
      AiProviderException exception, HttpServletRequest request) {
    return ResponseEntity.badRequest()
        .body(error("AI_PROVIDER_UNAVAILABLE", "AI provider is unavailable", request));
  }

  @ExceptionHandler(AiGatewayException.class)
  public ResponseEntity<ApiError> handleAiGatewayException(
      AiGatewayException exception, HttpServletRequest request) {
    return aiFailure(
        exception, "AI_PROVIDER_CALL_FAILED", "AI provider call failed", request);
  }

  @ExceptionHandler(AiStructuredOutputException.class)
  public ResponseEntity<ApiError> handleAiStructuredOutputException(
      AiStructuredOutputException exception, HttpServletRequest request) {
    return aiFailure(
        exception, "INVALID_AI_OUTPUT", "AI returned an invalid structured response", request);
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<ApiError> handleMaxUploadSizeExceeded(
      MaxUploadSizeExceededException exception,
      HttpServletRequest request) {
    // The servlet multipart guard is 8 MiB (application.yml), aligned with the voice upload
    // cap; the knowledge/resume modules keep their own higher application-level limits.
    return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
        .body(error("FILE_TOO_LARGE", "The uploaded file exceeds 8 MiB", request));
  }

  @ExceptionHandler(VoiceMediaProbeException.class)
  public ResponseEntity<ApiError> handleVoiceMediaProbe(
      VoiceMediaProbeException exception, HttpServletRequest request) {
    log.warn("voice_media_probe_unavailable traceId={}", traceId(request), exception);
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(error("VOICE_MEDIA_PROBE_UNAVAILABLE",
            "Audio validation is temporarily unavailable. Please retry.", request));
  }

  @ExceptionHandler(VoiceMediaStorageException.class)
  public ResponseEntity<ApiError> handleVoiceMediaStorage(
      VoiceMediaStorageException exception, HttpServletRequest request) {
    log.warn("voice_media_storage_unavailable traceId={}", traceId(request), exception);
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(error("VOICE_MEDIA_STORAGE_UNAVAILABLE",
            "Audio storage is temporarily unavailable. Please retry.", request));
  }

  @ExceptionHandler({
      MissingServletRequestPartException.class,
      MethodArgumentTypeMismatchException.class,
      HttpMessageNotReadableException.class
  })
  public ResponseEntity<ApiError> handleBadRequest(Exception exception, HttpServletRequest request) {
    return ResponseEntity.badRequest()
        .body(error("INVALID_REQUEST", "The request is invalid", request));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiError> handleValidation(
      MethodArgumentNotValidException exception, HttpServletRequest request) {
    return ResponseEntity.badRequest()
        .body(error("INVALID_REQUEST", "The request is invalid", request));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
    String traceId = traceId(request);
    log.error("request_failed traceId={} code=INTERNAL_ERROR exceptionType={} message={}",
        traceId, exception.getClass().getName(), exception.getMessage(), exception);
    if (exception instanceof ErrorResponse frameworkError) {
      int statusCode = frameworkError.getStatusCode().value();
      HttpStatus status = HttpStatus.resolve(statusCode);
      String code = status == null ? "HTTP_" + statusCode : status.name();
      String message = status == null ? "Request failed" : status.getReasonPhrase();
      return ResponseEntity.status(frameworkError.getStatusCode())
          .body(error(code, message, request));
    }
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(new ApiError("INTERNAL_ERROR", "An unexpected error occurred", traceId));
  }

  private static ResponseEntity<ApiError> aiFailure(
      RuntimeException exception,
      String code,
      String message,
      HttpServletRequest request) {
    String traceId = traceId(request);
    Throwable cause = exception.getCause();
    log.warn("request_failed traceId={} code={} exceptionType={} causeType={}",
        traceId,
        code,
        exception.getClass().getName(),
        cause == null ? "none" : cause.getClass().getName());
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .body(new ApiError(code, message, traceId));
  }

  private static ApiError error(String code, String message, HttpServletRequest request) {
    return new ApiError(code, message, traceId(request));
  }

  private static String traceId(HttpServletRequest request) {
    Object attribute = request.getAttribute("traceId");
    if (attribute != null) {
      try {
        return UUID.fromString(attribute.toString()).toString();
      } catch (IllegalArgumentException ignored) {
        // Only UUID trace identifiers are safe to reflect to a client.
      }
    }
    return UUID.randomUUID().toString();
  }
}

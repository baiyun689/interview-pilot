package interview.pilot.voice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import interview.pilot.auth.application.CurrentUser;
import interview.pilot.auth.application.CurrentUserProvider;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.common.exception.GlobalExceptionHandler;
import interview.pilot.voice.application.VoiceAnswerModule;
import interview.pilot.voice.application.VoiceRecordingReceipt;
import interview.pilot.voice.application.VoiceRecordingView;
import interview.pilot.voice.domain.VoiceErrorCodes;
import interview.pilot.voice.domain.VoiceRecordingStatus;

class VoiceRecordingControllerTest {
  private VoiceAnswerModule module;
  private CurrentUserProvider currentUser;
  private MockMvc mvc;
  private final CurrentUser user = new CurrentUser(1L, UUID.randomUUID(), "user@example.com", "User");
  private final UUID sessionId = UUID.randomUUID();
  private final UUID recordingId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    module = mock(VoiceAnswerModule.class);
    currentUser = mock(CurrentUserProvider.class);
    when(currentUser.require()).thenReturn(user);
    mvc = MockMvcBuilders
        .standaloneSetup(new VoiceRecordingController(module, currentUser))
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
  }

  @Test
  void uploadReturnsAcceptedWithTheReceiptFields() throws Exception {
    UUID taskId = UUID.randomUUID();
    when(module.accept(org.mockito.ArgumentMatchers.eq(user),
        org.mockito.ArgumentMatchers.eq(sessionId), org.mockito.ArgumentMatchers.eq(1),
        org.mockito.ArgumentMatchers.eq(UUID.fromString("11111111-1111-1111-1111-111111111111")),
        org.mockito.ArgumentMatchers.any()))
        .thenReturn(new VoiceRecordingReceipt(recordingId, VoiceRecordingStatus.UPLOADED, taskId));

    mvc.perform(multipart("/api/interviews/{sessionId}/turns/1/voice-recordings", sessionId)
            .file(audio())
            .param("uploadRequestId", "11111111-1111-1111-1111-111111111111"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.recordingId").value(recordingId.toString()))
        .andExpect(jsonPath("$.status").value("UPLOADED"))
        .andExpect(jsonPath("$.transcriptionTaskId").value(taskId.toString()));
  }

  @Test
  void uploadMapsTheStableRejectionsToTheirHttpStatuses() throws Exception {
    var rejections = List.of(
        rejection(VoiceErrorCodes.VOICE_UPLOAD_TOO_LARGE, HttpStatus.CONTENT_TOO_LARGE),
        rejection(VoiceErrorCodes.VOICE_MEDIA_UNSUPPORTED, HttpStatus.UNSUPPORTED_MEDIA_TYPE),
        rejection(VoiceErrorCodes.VOICE_UPLOAD_IN_PROGRESS, HttpStatus.CONFLICT),
        rejection(VoiceErrorCodes.VOICE_TURN_NOT_CURRENT, HttpStatus.CONFLICT),
        rejection("REQUEST_ID_CONFLICT", HttpStatus.CONFLICT));
    for (BusinessException rejection : rejections) {
      when(module.accept(org.mockito.ArgumentMatchers.eq(user),
          org.mockito.ArgumentMatchers.eq(sessionId), org.mockito.ArgumentMatchers.eq(1),
          org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
          .thenThrow(rejection);
      mvc.perform(upload())
          .andExpect(status().is(rejection.status().value()))
          .andExpect(jsonPath("$.code").value(rejection.code()));
    }
  }

  @Test
  void uploadMapsNotFoundTo404() throws Exception {
    when(module.accept(org.mockito.ArgumentMatchers.eq(user),
        org.mockito.ArgumentMatchers.eq(sessionId), org.mockito.ArgumentMatchers.eq(1),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenThrow(rejection(VoiceErrorCodes.VOICE_RECORDING_NOT_FOUND, HttpStatus.NOT_FOUND));

    mvc.perform(upload())
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(VoiceErrorCodes.VOICE_RECORDING_NOT_FOUND));
  }

  @Test
  void operationalFailuresReachTheClientAsSafeRetryableErrors()
      throws Exception {
    var probeFailure = new interview.pilot.voice.domain.VoiceMediaProbeException(
        "ffprobe timed out", null);
    when(module.accept(org.mockito.ArgumentMatchers.eq(user),
        org.mockito.ArgumentMatchers.eq(sessionId), org.mockito.ArgumentMatchers.eq(1),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenThrow(probeFailure);
    var probeBody = mvc.perform(upload())
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("VOICE_MEDIA_PROBE_UNAVAILABLE"))
        .andReturn().getResponse().getContentAsString();
    assertThat(probeBody)
        .doesNotContain(VoiceErrorCodes.VOICE_MEDIA_PROBE_FAILED)
        .doesNotContain("ffprobe");

    var storageFailure = new interview.pilot.voice.domain.VoiceMediaStorageException(
        "disk full", new java.io.IOException());
    when(module.accept(org.mockito.ArgumentMatchers.eq(user),
        org.mockito.ArgumentMatchers.eq(sessionId), org.mockito.ArgumentMatchers.eq(1),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenThrow(storageFailure);
    var storageBody = mvc.perform(upload())
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("VOICE_MEDIA_STORAGE_UNAVAILABLE"))
        .andReturn().getResponse().getContentAsString();
    assertThat(storageBody)
        .doesNotContain(VoiceErrorCodes.VOICE_MEDIA_STORAGE_FAILED)
        .doesNotContain("disk full");
  }

  @Test
  void getReturnsTheViewAndMapsNotFound() throws Exception {
    when(module.get(user, sessionId, recordingId)).thenReturn(new VoiceRecordingView(
        recordingId, 4, VoiceRecordingStatus.READY, "我在项目中使用了事务消息……",
        48_320L, false, null));

    mvc.perform(get("/api/interviews/{sessionId}/voice-recordings/{recordingId}",
            sessionId, recordingId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.recordingId").value(recordingId.toString()))
        .andExpect(jsonPath("$.turnNo").value(4))
        .andExpect(jsonPath("$.status").value("READY"))
        .andExpect(jsonPath("$.rawTranscript").value("我在项目中使用了事务消息……"))
        .andExpect(jsonPath("$.durationMillis").value(48_320))
        .andExpect(jsonPath("$.retryable").value(false));

    when(module.get(user, sessionId, recordingId))
        .thenThrow(rejection(VoiceErrorCodes.VOICE_RECORDING_NOT_FOUND, HttpStatus.NOT_FOUND));
    mvc.perform(get("/api/interviews/{sessionId}/voice-recordings/{recordingId}",
            sessionId, recordingId))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(VoiceErrorCodes.VOICE_RECORDING_NOT_FOUND));
  }

  @Test
  void retryAndDiscardMapToAcceptedAndNoContent() throws Exception {
    mvc.perform(post("/api/interviews/{sessionId}/voice-recordings/{recordingId}/retry",
            sessionId, recordingId))
        .andExpect(status().isAccepted());
    mvc.perform(post("/api/interviews/{sessionId}/voice-recordings/{recordingId}/discard",
            sessionId, recordingId))
        .andExpect(status().isNoContent());
    verify(module).retry(user, sessionId, recordingId);
    verify(module).discard(user, sessionId, recordingId);
  }

  @Test
  void everyEndpointRequiresAuthenticationThroughTheProvider() throws Exception {
    mvc.perform(upload()).andExpect(status().isAccepted());
    mvc.perform(get("/api/interviews/{sessionId}/voice-recordings/{recordingId}",
            sessionId, recordingId)).andExpect(status().isOk());
    mvc.perform(post("/api/interviews/{sessionId}/voice-recordings/{recordingId}/retry",
            sessionId, recordingId)).andExpect(status().isAccepted());
    mvc.perform(post("/api/interviews/{sessionId}/voice-recordings/{recordingId}/discard",
            sessionId, recordingId)).andExpect(status().isNoContent());

    verify(currentUser, org.mockito.Mockito.times(4)).require();
  }

  @Test
  void missingAuthenticationNeverReachesTheModule() throws Exception {
    when(currentUser.require()).thenThrow(
        new AuthenticationCredentialsNotFoundException("Authentication is required"));

    mvc.perform(upload()).andExpect(status().isInternalServerError());

    verify(module, org.mockito.Mockito.never())
        .accept(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
  }

  private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder upload() {
    return multipart("/api/interviews/{sessionId}/turns/1/voice-recordings", sessionId)
        .file(audio())
        .param("uploadRequestId", UUID.randomUUID().toString());
  }

  private static BusinessException rejection(String code, HttpStatus status) {
    return new BusinessException(code, "rejection for " + code, status);
  }

  private static MockMultipartFile audio() {
    return new MockMultipartFile(
        "audio", "recording.webm", "audio/webm",
        "voice bytes".getBytes(StandardCharsets.UTF_8));
  }
}

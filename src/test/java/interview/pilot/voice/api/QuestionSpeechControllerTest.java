package interview.pilot.voice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import interview.pilot.auth.application.CurrentUser;
import interview.pilot.auth.application.CurrentUserProvider;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.common.exception.GlobalExceptionHandler;
import interview.pilot.voice.application.QuestionSpeechMedia;
import interview.pilot.voice.application.QuestionSpeechModule;
import interview.pilot.voice.application.QuestionSpeechView;
import interview.pilot.voice.application.QuestionSpeechViewStatus;
import interview.pilot.voice.domain.VoiceErrorCodes;
import interview.pilot.voice.domain.VoiceMediaResource;
import interview.pilot.voice.domain.VoiceRangeNotSatisfiableException;

class QuestionSpeechControllerTest {
  private QuestionSpeechModule module;
  private CurrentUserProvider currentUser;
  private MockMvc mvc;
  private final CurrentUser user = new CurrentUser(1L, UUID.randomUUID(), "user@example.com", "User");
  private final UUID sessionId = UUID.randomUUID();
  private final UUID speechId = UUID.randomUUID();
  private static final String MEDIA_URL =
      "/api/interviews/{sessionId}/speech/{speechId}/media";

  @BeforeEach
  void setUp() {
    module = mock(QuestionSpeechModule.class);
    currentUser = mock(CurrentUserProvider.class);
    when(currentUser.require()).thenReturn(user);
    mvc = MockMvcBuilders
        .standaloneSetup(new QuestionSpeechController(module, currentUser))
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
  }

  // ---------------------------------------------------------------- speech view

  @Test
  void speechViewMapsInProgressStatusesTo202AndTerminalOnesTo200() throws Exception {
    for (QuestionSpeechViewStatus inProgress : List.of(
        QuestionSpeechViewStatus.PENDING, QuestionSpeechViewStatus.SYNTHESIZING)) {
      when(module.getOrSchedule(user, sessionId, 1)).thenReturn(
          view(inProgress, speechId, null, false, null));
      mvc.perform(get("/api/interviews/{sessionId}/turns/1/speech", sessionId))
          .andExpect(status().isAccepted())
          .andExpect(jsonPath("$.status").value(inProgress.name()));
    }

    when(module.getOrSchedule(user, sessionId, 1))
        .thenReturn(view(QuestionSpeechViewStatus.READY, speechId, mediaUrl(), false, null));
    mvc.perform(get("/api/interviews/{sessionId}/turns/1/speech", sessionId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.speechId").value(speechId.toString()))
        .andExpect(jsonPath("$.status").value("READY"))
        .andExpect(jsonPath("$.mediaUrl").value(mediaUrl()))
        .andExpect(jsonPath("$.retryable").value(false));

    when(module.getOrSchedule(user, sessionId, 1)).thenReturn(
        view(QuestionSpeechViewStatus.FAILED, speechId, null, true,
            VoiceErrorCodes.VOICE_QUESTION_SPEECH_FAILED));
    mvc.perform(get("/api/interviews/{sessionId}/turns/1/speech", sessionId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FAILED"))
        .andExpect(jsonPath("$.retryable").value(true))
        .andExpect(jsonPath("$.safeError").value(VoiceErrorCodes.VOICE_QUESTION_SPEECH_FAILED));

    when(module.getOrSchedule(user, sessionId, 1)).thenReturn(
        view(QuestionSpeechViewStatus.NOT_AVAILABLE, null, null, false, null));
    mvc.perform(get("/api/interviews/{sessionId}/turns/1/speech", sessionId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.speechId").doesNotExist())
        .andExpect(jsonPath("$.status").value("NOT_AVAILABLE"))
        .andExpect(jsonPath("$.mediaUrl").doesNotExist())
        .andExpect(jsonPath("$.retryable").value(false));
  }

  @Test
  void speechViewMapsTheModuleRejectionsToTheirHttpStatuses() throws Exception {
    var rejections = List.of(
        rejection(VoiceErrorCodes.QUESTION_SPEECH_NOT_FOUND, HttpStatus.NOT_FOUND),
        rejection(VoiceErrorCodes.QUESTION_SPEECH_NOT_READY, HttpStatus.CONFLICT));
    for (BusinessException rejection : rejections) {
      // doThrow (not when+thenThrow): re-stubbing through when() re-executes the prior
      // thenThrow stub on the new stub invocation.
      org.mockito.Mockito.doThrow(rejection)
          .when(module).getOrSchedule(user, sessionId, 1);
      mvc.perform(get("/api/interviews/{sessionId}/turns/1/speech", sessionId))
          .andExpect(status().is(rejection.status().value()))
          .andExpect(jsonPath("$.code").value(rejection.code()));
    }
  }

  // ---------------------------------------------------------------- retry

  @Test
  void retryResolvesTheSpeechThroughTheViewAndReturns202() throws Exception {
    when(module.getOrSchedule(user, sessionId, 1))
        .thenReturn(view(QuestionSpeechViewStatus.FAILED, speechId, null, true, null));

    mvc.perform(post("/api/interviews/{sessionId}/turns/1/speech/retry", sessionId))
        .andExpect(status().isAccepted());

    verify(module).retry(user, sessionId, speechId);
  }

  @Test
  void retryOfATurnWithoutSpeechIsAStableConflictAndNeverReachesRetry() throws Exception {
    when(module.getOrSchedule(user, sessionId, 1))
        .thenReturn(view(QuestionSpeechViewStatus.NOT_AVAILABLE, null, null, false, null));

    mvc.perform(post("/api/interviews/{sessionId}/turns/1/speech/retry", sessionId))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(VoiceErrorCodes.QUESTION_SPEECH_NOT_READY));

    verify(module, never()).retry(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void retryMapsTheModuleStateConflictsTo409WithTheStableCodes() throws Exception {
    when(module.getOrSchedule(user, sessionId, 1))
        .thenReturn(view(QuestionSpeechViewStatus.FAILED, speechId, null, true, null));
    org.mockito.Mockito.doThrow(
        rejection(VoiceErrorCodes.QUESTION_SPEECH_NOT_READY, HttpStatus.CONFLICT))
        .when(module).retry(user, sessionId, speechId);

    mvc.perform(post("/api/interviews/{sessionId}/turns/1/speech/retry", sessionId))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(VoiceErrorCodes.QUESTION_SPEECH_NOT_READY));
  }

  // ---------------------------------------------------------------- media

  @Test
  void mediaServesTheFullBodyWithTheSecurityHeaders() throws Exception {
    when(module.open(user, sessionId, speechId, null))
        .thenReturn(media("0123456789", 10, etag()));

    mvc.perform(get(MEDIA_URL, sessionId, speechId))
        .andExpect(status().isOk())
        .andExpect(content().bytes("0123456789".getBytes(StandardCharsets.UTF_8)))
        .andExpect(header().string("Content-Type", "audio/mpeg"))
        .andExpect(header().string("Content-Disposition", "inline"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("ETag", etag()))
        .andExpect(header().string("Cache-Control", "private, max-age=3600"))
        .andExpect(header().string("Accept-Ranges", "bytes"))
        .andExpect(header().string("Content-Length", "10"));
  }

  @Test
  void mediaServesEverySingleByteRangeFormAs206WithContentRange() throws Exception {
    when(module.open(org.mockito.ArgumentMatchers.eq(user),
        org.mockito.ArgumentMatchers.eq(sessionId), org.mockito.ArgumentMatchers.eq(speechId),
        org.mockito.ArgumentMatchers.any()))
        .thenReturn(media("2345", 10, etag()));
    mvc.perform(get(MEDIA_URL, sessionId, speechId).header("Range", "bytes=2-5"))
        .andExpect(status().isPartialContent())
        .andExpect(header().string("Content-Range", "bytes 2-5/10"))
        .andExpect(header().string("Content-Length", "4"))
        .andExpect(content().bytes("2345".getBytes(StandardCharsets.UTF_8)));

    when(module.open(org.mockito.ArgumentMatchers.eq(user),
        org.mockito.ArgumentMatchers.eq(sessionId), org.mockito.ArgumentMatchers.eq(speechId),
        org.mockito.ArgumentMatchers.any()))
        .thenReturn(media("6789", 10, etag()));
    mvc.perform(get(MEDIA_URL, sessionId, speechId).header("Range", "bytes=6-"))
        .andExpect(status().isPartialContent())
        .andExpect(header().string("Content-Range", "bytes 6-9/10"))
        .andExpect(content().bytes("6789".getBytes(StandardCharsets.UTF_8)));

    when(module.open(org.mockito.ArgumentMatchers.eq(user),
        org.mockito.ArgumentMatchers.eq(sessionId), org.mockito.ArgumentMatchers.eq(speechId),
        org.mockito.ArgumentMatchers.any()))
        .thenReturn(media("789", 10, etag()));
    mvc.perform(get(MEDIA_URL, sessionId, speechId).header("Range", "bytes=-3"))
        .andExpect(status().isPartialContent())
        .andExpect(header().string("Content-Range", "bytes 7-9/10"))
        .andExpect(header().string("Content-Length", "3"))
        .andExpect(content().bytes("789".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void mediaMapsUnsatisfiableRangesTo416WithTheTotalLength() throws Exception {
    org.mockito.Mockito.doThrow(new VoiceRangeNotSatisfiableException(10))
        .when(module).open(
            org.mockito.ArgumentMatchers.eq(user),
            org.mockito.ArgumentMatchers.eq(sessionId),
            org.mockito.ArgumentMatchers.eq(speechId),
            org.mockito.ArgumentMatchers.any());

    mvc.perform(get(MEDIA_URL, sessionId, speechId).header("Range", "bytes=10-20"))
        .andExpect(status().is(416))
        .andExpect(header().string("Content-Range", "bytes */10"));
  }

  @Test
  void mediaMapsMalformedRangeHeadersTo416WithTheTotalLength() throws Exception {
    when(module.open(user, sessionId, speechId, null))
        .thenReturn(media("0123456789", 10, etag()));

    for (String malformed : List.of("bytes=abc", "bytes=5", "garbage", "bytes=-")) {
      MvcResult result = mvc.perform(get(MEDIA_URL, sessionId, speechId)
              .header("Range", malformed))
          .andExpect(status().is(416))
          .andExpect(header().string("Content-Range", "bytes */10"))
          .andReturn();
      MockHttpServletResponse response = result.getResponse();
      assertThat(response.getContentAsByteArray()).isEmpty();
    }
    verify(module, org.mockito.Mockito.times(4))
        .open(user, sessionId, speechId, null);
  }

  @Test
  void mediaIgnoresMultiRangeHeadersAndServesTheFullBody() throws Exception {
    when(module.open(user, sessionId, speechId, null))
        .thenReturn(media("0123456789", 10, etag()));

    mvc.perform(get(MEDIA_URL, sessionId, speechId).header("Range", "bytes=0-1,4-5"))
        .andExpect(status().isOk())
        .andExpect(content().bytes("0123456789".getBytes(StandardCharsets.UTF_8)))
        .andExpect(header().doesNotExist("Content-Range"));
  }

  @Test
  void mediaMapsOwnershipAndStateRejectionsWithTheStableCodes() throws Exception {
    var rejections = List.of(
        rejection(VoiceErrorCodes.QUESTION_SPEECH_NOT_FOUND, HttpStatus.NOT_FOUND),
        rejection(VoiceErrorCodes.QUESTION_SPEECH_NOT_READY, HttpStatus.CONFLICT),
        rejection(VoiceErrorCodes.QUESTION_SPEECH_FAILED, HttpStatus.CONFLICT));
    for (BusinessException rejection : rejections) {
      org.mockito.Mockito.doThrow(rejection)
          .when(module).open(user, sessionId, speechId, null);
      mvc.perform(get(MEDIA_URL, sessionId, speechId))
          .andExpect(status().is(rejection.status().value()))
          .andExpect(jsonPath("$.code").value(rejection.code()));
    }
  }

  @Test
  void everyEndpointRequiresAuthenticationThroughTheProvider() throws Exception {
    when(module.getOrSchedule(user, sessionId, 1))
        .thenReturn(view(QuestionSpeechViewStatus.PENDING, speechId, null, false, null));
    when(module.open(user, sessionId, speechId, null))
        .thenReturn(media("0123456789", 10, etag()));

    mvc.perform(get("/api/interviews/{sessionId}/turns/1/speech", sessionId))
        .andExpect(status().isAccepted());
    mvc.perform(post("/api/interviews/{sessionId}/turns/1/speech/retry", sessionId))
        .andExpect(status().isAccepted());
    mvc.perform(get(MEDIA_URL, sessionId, speechId)).andExpect(status().isOk());

    // The retry endpoint captures the current user once and reuses it for both module calls.
    verify(currentUser, org.mockito.Mockito.times(3)).require();
  }

  @Test
  void missingAuthenticationNeverReachesTheModule() throws Exception {
    when(currentUser.require()).thenThrow(
        new AuthenticationCredentialsNotFoundException("Authentication is required"));

    mvc.perform(get("/api/interviews/{sessionId}/turns/1/speech", sessionId))
        .andExpect(status().isInternalServerError());
    mvc.perform(get(MEDIA_URL, sessionId, speechId))
        .andExpect(status().isInternalServerError());

    verify(module, never()).getOrSchedule(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
    verify(module, never()).open(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any());
  }

  // ---------------------------------------------------------------- helpers

  private String mediaUrl() {
    return "/api/interviews/" + sessionId + "/speech/" + speechId + "/media";
  }

  private static String etag() {
    return "W/\"qs-00000000-0000-0000-0000-000000000000-v3\"";
  }

  private static QuestionSpeechView view(
      QuestionSpeechViewStatus status, UUID speechId, String mediaUrl,
      boolean retryable, String safeError) {
    return new QuestionSpeechView(speechId, status, mediaUrl, retryable, safeError);
  }

  private static QuestionSpeechMedia media(String stream, long totalLength, String etag) {
    byte[] content = stream.getBytes(StandardCharsets.UTF_8);
    return new QuestionSpeechMedia(
        new VoiceMediaResource(
            new ByteArrayInputStream(content), totalLength, "audio/mpeg", null),
        etag);
  }

  private static BusinessException rejection(String code, HttpStatus status) {
    return new BusinessException(code, "rejection for " + code, status);
  }
}

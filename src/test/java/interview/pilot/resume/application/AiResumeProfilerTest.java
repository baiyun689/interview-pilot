package interview.pilot.resume.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;

import interview.pilot.ai.StructuredOutputInvoker;
import interview.pilot.ai.model.AiRequest;
import interview.pilot.ai.provider.AiProviderService;
import interview.pilot.resume.domain.ResumeProfile;

class AiResumeProfilerTest {
  @Test
  void usesSelectedProviderAndGroundedStrictJsonPrompts() {
    StructuredOutputInvoker invoker = org.mockito.Mockito.mock(StructuredOutputInvoker.class);
    AiProviderService providers = org.mockito.Mockito.mock(AiProviderService.class);
    var expected = new ResumeProfile(
        "Java engineer", List.of("Java"), List.of(), List.of(), List.of());
    when(providers.currentDefaultProviderId()).thenReturn("deepseek");
    when(invoker.invoke(org.mockito.ArgumentMatchers.any(), eq(ResumeProfile.class)))
        .thenReturn(expected);
    var profiler = new AiResumeProfiler(
        invoker,
        providers,
        new ClassPathResource("prompts/resume-profile-system.st"),
        new ClassPathResource("prompts/resume-profile-user.st"));

    ResumeProfile actual = profiler.profile(
        "Built the Atlas API with Java and Spring Boot.");

    assertThat(actual).isSameAs(expected);
    var request = ArgumentCaptor.forClass(AiRequest.class);
    verify(invoker).invoke(request.capture(), eq(ResumeProfile.class));
    assertThat(request.getValue().providerId()).isEqualTo("deepseek");
    assertThat(request.getValue().responseType()).isEqualTo(ResumeProfile.class);
    assertThat(request.getValue().systemPrompt())
        .contains("不得编造")
        .contains("summary", "technicalSkills", "projects", "strengths", "risks");
    assertThat(request.getValue().userPrompt())
        .contains("Built the Atlas API with Java and Spring Boot.")
        .contains("简历原文")
        .contains("只依据");
  }
}

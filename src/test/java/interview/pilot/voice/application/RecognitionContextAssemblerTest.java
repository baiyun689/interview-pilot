package interview.pilot.voice.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.InterviewBriefSnapshot;
import interview.pilot.interview.domain.InterviewMode;
import interview.pilot.interview.domain.InterviewSize;
import interview.pilot.interview.domain.JobSourceType;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.resume.domain.ResumeProfile;
import interview.pilot.voice.domain.TranscriptionVocabulary;
import tools.jackson.databind.ObjectMapper;

class RecognitionContextAssemblerTest {
  private final ObjectMapper json = new ObjectMapper();
  private final RecognitionContextAssembler assembler = new RecognitionContextAssembler(json);

  @Test
  void assemblesJobTitleJdTermsResumeSkillsAndTheFixedListInOrder() throws Exception {
    var brief = new InterviewBriefSnapshot(
        JobSourceType.CUSTOM, "", "", "Java 后端工程师",
        "负责 Spring Boot 微服务开发，熟悉 MySQL 与 Redis、RabbitMQ，掌握 JVM 调优",
        1L, resume("MyBatis", "Kafka"), Difficulty.MEDIUM, InterviewSize.STANDARD,
        "dashscope", "qwen", null, 1);
    var session = session(brief);

    RecognitionContext context = assembler.assemble(session);

    assertThat(context.vocabulary()).containsSubsequence(
        "Java 后端工程师", "Spring", "Boot", "MySQL", "Redis", "RabbitMQ",
        "JVM", "MyBatis", "Kafka")
        .containsAll(TranscriptionVocabulary.JAVA_BACKEND_TERMS)
        .doesNotHaveDuplicates();
    assertThat(context.vocabulary().size()).isLessThanOrEqualTo(100);
  }

  @Test
  void degradesToTheFixedListWhenTheBriefHasNoTechnicalSignals() throws Exception {
    var brief = new InterviewBriefSnapshot(
        JobSourceType.CUSTOM, "", "", "Java 后端",
        "构建后端服务，负责日常开发与维护",
        null, null, Difficulty.MEDIUM, InterviewSize.STANDARD,
        "dashscope", "qwen", null, 1);

    RecognitionContext context = assembler.assemble(session(brief));

    // "构建"/"后端"/"服务"/"负责"/"日常"/"开发"/"维护" are not Latin tech tokens and the JD
    // contributes nothing; the job title and the fixed list are the only signals.
    assertThat(context.vocabulary()).containsExactlyElementsOf(
        java.util.stream.Stream.concat(
            java.util.stream.Stream.of("Java 后端"),
            TranscriptionVocabulary.JAVA_BACKEND_TERMS.stream()).toList());
  }

  @Test
  void degradesToTheFixedListWhenTheBriefSnapshotIsUnparseable() {
    var session = InterviewSessionEntity.preparing(
        1L, null, Difficulty.MEDIUM, InterviewSize.STANDARD,
        JobSourceType.CUSTOM, "Java 后端", "dashscope", "qwen",
        "{}", null, InterviewMode.VOICE, "{}");

    RecognitionContext context = assembler.assemble(session);

    assertThat(context.vocabulary()).isEqualTo(TranscriptionVocabulary.JAVA_BACKEND_TERMS);
  }

  private InterviewSessionEntity session(InterviewBriefSnapshot brief) throws Exception {
    return InterviewSessionEntity.preparing(
        1L, null, Difficulty.MEDIUM, InterviewSize.STANDARD,
        JobSourceType.CUSTOM, brief.jobTitle(), "dashscope", "qwen",
        json.writeValueAsString(brief), null, InterviewMode.VOICE, "{}");
  }

  private static ResumeProfile resume(String... skills) {
    return new ResumeProfile(
        "summary", List.of(skills), List.of(), List.of(), List.of());
  }
}

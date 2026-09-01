package interview.pilot.async.messaging;

import java.util.ArrayList;
import java.util.List;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import interview.pilot.async.domain.AsyncTaskType;

@Configuration
@EnableConfigurationProperties(AsyncRabbitProperties.class)
public class RabbitTopologyConfig {
  public static final String RESUME_ANALYSIS_MAIN_EXCHANGE =
      "interview-pilot.resume.analysis";
  public static final String RESUME_ANALYSIS_MAIN_QUEUE =
      "interview-pilot.resume.analysis.main";
  public static final String RESUME_ANALYSIS_DLQ =
      "interview-pilot.resume.analysis.dlq";

  public static final String INTERVIEW_REPORT_MAIN_EXCHANGE =
      "interview-pilot.interview.report";
  public static final String INTERVIEW_REPORT_MAIN_QUEUE =
      "interview-pilot.interview.report.main";
  public static final String INTERVIEW_REPORT_DLQ =
      "interview-pilot.interview.report.dlq";

  public static final String INTERVIEW_PREPARATION_MAIN_EXCHANGE =
      "interview-pilot.interview.preparation";
  public static final String INTERVIEW_PREPARATION_MAIN_QUEUE =
      "interview-pilot.interview.preparation.main";
  public static final String INTERVIEW_PREPARATION_DLQ =
      "interview-pilot.interview.preparation.dlq";

  public static final String KNOWLEDGE_INDEX_MAIN_EXCHANGE =
      "interview-pilot.knowledge.index";
  public static final String KNOWLEDGE_INDEX_MAIN_QUEUE =
      "interview-pilot.knowledge.index.main";
  public static final String KNOWLEDGE_INDEX_DLQ =
      "interview-pilot.knowledge.index.dlq";

  public static final String KNOWLEDGE_DELETE_MAIN_EXCHANGE =
      "interview-pilot.knowledge.delete";
  public static final String KNOWLEDGE_DELETE_MAIN_QUEUE =
      "interview-pilot.knowledge.delete.main";
  public static final String KNOWLEDGE_DELETE_DLQ =
      "interview-pilot.knowledge.delete.dlq";

  public static final String VOICE_TRANSCRIPTION_MAIN_EXCHANGE =
      "interview-pilot.voice.transcription";
  public static final String VOICE_TRANSCRIPTION_MAIN_QUEUE =
      "interview-pilot.voice.transcription.main";
  public static final String VOICE_TRANSCRIPTION_DLQ =
      "interview-pilot.voice.transcription.dlq";

  static final String RESUME_ANALYSIS_ROUTING_KEY = "resume.analysis";
  static final String INTERVIEW_REPORT_ROUTING_KEY = "interview.report";
  static final String INTERVIEW_PREPARATION_ROUTING_KEY = "interview.preparation";
  static final String KNOWLEDGE_INDEX_ROUTING_KEY = "knowledge.index";
  static final String KNOWLEDGE_DELETE_ROUTING_KEY = "knowledge.delete";
  static final String VOICE_TRANSCRIPTION_ROUTING_KEY = "voice.transcription";
  /** Wire-level retry-delay counter; tests outside this package build exhausted-message sources. */
  public static final String RETRY_COUNT_HEADER = "x-retry-count";
  static final int[] RETRY_DELAYS_MILLIS = {5_000, 30_000, 120_000};

  @Bean
  MessageConverter taskMessageConverter() {
    return new JacksonJsonMessageConverter();
  }

  @Bean
  Declarables asyncTaskTopology() {
    List<Declarable> declarations = new ArrayList<>();
    declarations.addAll(pipelineDeclarations(routeFor(AsyncTaskType.RESUME_ANALYSIS)));
    declarations.addAll(pipelineDeclarations(routeFor(AsyncTaskType.INTERVIEW_QUESTION_PREPARATION)));
    declarations.addAll(pipelineDeclarations(routeFor(AsyncTaskType.INTERVIEW_EVALUATION)));
    declarations.addAll(pipelineDeclarations(routeFor(AsyncTaskType.KNOWLEDGE_DOCUMENT_INDEX)));
    declarations.addAll(pipelineDeclarations(routeFor(AsyncTaskType.KNOWLEDGE_DOCUMENT_DELETE)));
    declarations.addAll(pipelineDeclarations(routeFor(AsyncTaskType.VOICE_TRANSCRIPTION)));
    return new Declarables(declarations);
  }

  public static PipelineRoute routeFor(AsyncTaskType taskType) {
    return switch (taskType) {
      case RESUME_ANALYSIS -> new PipelineRoute(
          RESUME_ANALYSIS_MAIN_EXCHANGE,
          RESUME_ANALYSIS_MAIN_QUEUE,
          RESUME_ANALYSIS_ROUTING_KEY,
          "interview-pilot.resume.analysis.dead-letter",
          RESUME_ANALYSIS_DLQ,
          "resume.analysis.dead");
      case INTERVIEW_QUESTION_PREPARATION -> new PipelineRoute(
          INTERVIEW_PREPARATION_MAIN_EXCHANGE,
          INTERVIEW_PREPARATION_MAIN_QUEUE,
          INTERVIEW_PREPARATION_ROUTING_KEY,
          "interview-pilot.interview.preparation.dead-letter",
          INTERVIEW_PREPARATION_DLQ,
          "interview.preparation.dead");
      case INTERVIEW_EVALUATION -> new PipelineRoute(
          INTERVIEW_REPORT_MAIN_EXCHANGE,
          INTERVIEW_REPORT_MAIN_QUEUE,
          INTERVIEW_REPORT_ROUTING_KEY,
          "interview-pilot.interview.report.dead-letter",
          INTERVIEW_REPORT_DLQ,
          "interview.report.dead");
      case KNOWLEDGE_DOCUMENT_INDEX -> new PipelineRoute(
          KNOWLEDGE_INDEX_MAIN_EXCHANGE,
          KNOWLEDGE_INDEX_MAIN_QUEUE,
          KNOWLEDGE_INDEX_ROUTING_KEY,
          "interview-pilot.knowledge.index.dead-letter",
          KNOWLEDGE_INDEX_DLQ,
          "knowledge.index.dead");
      case KNOWLEDGE_DOCUMENT_DELETE -> new PipelineRoute(
          KNOWLEDGE_DELETE_MAIN_EXCHANGE,
          KNOWLEDGE_DELETE_MAIN_QUEUE,
          KNOWLEDGE_DELETE_ROUTING_KEY,
          "interview-pilot.knowledge.delete.dead-letter",
          KNOWLEDGE_DELETE_DLQ,
          "knowledge.delete.dead");
      case VOICE_TRANSCRIPTION -> new PipelineRoute(
          VOICE_TRANSCRIPTION_MAIN_EXCHANGE,
          VOICE_TRANSCRIPTION_MAIN_QUEUE,
          VOICE_TRANSCRIPTION_ROUTING_KEY,
          "interview-pilot.voice.transcription.dead-letter",
          VOICE_TRANSCRIPTION_DLQ,
          "voice.transcription.dead");
    };
  }

  private List<Declarable> pipelineDeclarations(PipelineRoute route) {
    var declarations = new ArrayList<Declarable>();
    var mainExchange = new DirectExchange(route.mainExchange(), true, false);
    var deadLetterExchange = new DirectExchange(route.deadLetterExchange(), true, false);
    Queue mainQueue = QueueBuilder.durable(route.mainQueue()).build();
    Queue deadLetterQueue = QueueBuilder.durable(route.deadLetterQueue()).build();

    declarations.add(mainExchange);
    declarations.add(deadLetterExchange);
    declarations.add(mainQueue);
    declarations.add(deadLetterQueue);
    declarations.add(BindingBuilder.bind(mainQueue)
        .to(mainExchange).with(route.mainRoutingKey()));
    declarations.add(BindingBuilder.bind(deadLetterQueue)
        .to(deadLetterExchange).with(route.deadLetterRoutingKey()));

    for (int index = 0; index < RETRY_DELAYS_MILLIS.length; index++) {
      int retryNumber = index + 1;
      String retryQueueName = route.retryQueue(retryNumber);
      String retryRoutingKey = route.retryRoutingKey(retryNumber);
      Queue retryQueue = QueueBuilder.durable(retryQueueName)
          .ttl(RETRY_DELAYS_MILLIS[index])
          .deadLetterExchange(route.mainExchange())
          .deadLetterRoutingKey(route.mainRoutingKey())
          .build();
      declarations.add(retryQueue);
      declarations.add(BindingBuilder.bind(retryQueue)
          .to(mainExchange).with(retryRoutingKey));
    }
    return declarations;
  }

  public record PipelineRoute(
      String mainExchange,
      String mainQueue,
      String mainRoutingKey,
      String deadLetterExchange,
      String deadLetterQueue,
      String deadLetterRoutingKey) {
    String retryQueue(int retryNumber) {
      return mainQueue + ".retry." + retryNumber;
    }

    String retryRoutingKey(int retryNumber) {
      return mainRoutingKey + ".retry." + retryNumber;
    }
  }
}

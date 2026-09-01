package interview.pilot.interview.application;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import interview.pilot.interview.domain.GroundingMode;
import interview.pilot.interview.domain.InterviewPhase;
import interview.pilot.interview.domain.InterviewSize;
import interview.pilot.interview.domain.PreparedQuestionDeck;
import interview.pilot.interview.rag.RagContextSnapshot;
import interview.pilot.interview.rag.RagStatus;

@Component
public class QuestionDeckValidator {
  private static final Set<InterviewPhase> GENERATED_PHASES = EnumSet.of(
      InterviewPhase.FUNDAMENTALS,
      InterviewPhase.PROJECT_EXPERIENCE,
      InterviewPhase.SCENARIO_TRADEOFF);

  public PreparedQuestionDeck validate(
      QuestionDeckOutput output,
      InterviewSize size,
      Map<InterviewPhase, RagContextSnapshot> ragByPhase) {
    require(output != null, "question deck output is required");
    require(output.schemaVersion() == 1, "schemaVersion must be 1");
    require(size != null, "interviewSize is required");
    require(output.questions() != null, "questions are required");
    var normalized = new ArrayList<PreparedQuestionDeck.PreparedQuestion>();
    var seenText = new HashSet<String>();
    for (InterviewPhase phase : GENERATED_PHASES) {
      List<QuestionDeckOutput.Question> phaseQuestions = output.questions().stream()
          .filter(question -> question != null && question.phase() == phase)
          .sorted(java.util.Comparator.comparingInt(QuestionDeckOutput.Question::sequence))
          .toList();
      int expected = size.mainQuestionCount(phase);
      require(phaseQuestions.size() == expected,
          phase + " requires exactly " + expected + " questions");
      for (int index = 0; index < phaseQuestions.size(); index++) {
        var question = phaseQuestions.get(index);
        require(question.sequence() == index + 1,
            phase + " sequence must start at 1 and be continuous");
        normalized.add(validateQuestion(question, ragByPhase.get(phase), seenText));
      }
    }
    require(output.questions().stream().noneMatch(question ->
        question == null || !GENERATED_PHASES.contains(question.phase())),
        "deck contains an illegal phase or self introduction question");
    require(output.questions().size() == size.generatedQuestionCount(),
        "question deck requires exactly " + size.generatedQuestionCount() + " questions");
    return new PreparedQuestionDeck(normalized);
  }

  private PreparedQuestionDeck.PreparedQuestion validateQuestion(
      QuestionDeckOutput.Question value,
      RagContextSnapshot rag,
      Set<String> seenText) {
    String topic = bounded(value.topic(), 1, 60, "topic");
    String question = bounded(value.question(), 20, 300, "question");
    require(seenText.add(question), "question text must be globally unique");
    require(value.focusPoints() != null, "focusPoints are required");
    require(value.focusPoints().size() >= 2 && value.focusPoints().size() <= 4,
        "focusPoints must contain 2 to 4 items");
    List<String> focusPoints = value.focusPoints().stream()
        .map(point -> bounded(point, 2, 80, "focusPoint"))
        .toList();

    String fallbackFollowUp = bounded(
        value.fallbackFollowUp(), 20, 160, "fallbackFollowUp");

    GroundingMode mode = value.groundingMode();
    require(value.evidenceRefs() != null, "evidenceRefs are required");
    require(mode == GroundingMode.GENERAL || mode == GroundingMode.KNOWLEDGE_ASSISTED,
        "groundingMode must be GENERAL or KNOWLEDGE_ASSISTED");
    if (mode == GroundingMode.GENERAL) {
      require(value.evidenceRefs().isEmpty(), "GENERAL evidenceRefs must be empty");
    } else {
      require(rag != null && rag.status() == RagStatus.RETRIEVED,
          "KNOWLEDGE_ASSISTED requires a retrieved current phase RAG snapshot");
      require(!value.evidenceRefs().isEmpty(),
          "KNOWLEDGE_ASSISTED evidenceRefs are required");
      try {
        rag.requireCurrentSources(value.evidenceRefs());
      } catch (IllegalArgumentException exception) {
        throw invalid("evidenceRefs must belong to the current phase RAG snapshot");
      }
    }

    return new PreparedQuestionDeck.PreparedQuestion(
        value.phase(), value.sequence(), topic, question, focusPoints,
        mode, value.evidenceRefs(), fallbackFollowUp);
  }

  private String bounded(String value, int minimum, int maximum, String field) {
    String normalized = value == null ? "" : value.trim();
    require(normalized.length() >= minimum && normalized.length() <= maximum,
        field + " length must be between " + minimum + " and " + maximum);
    return normalized;
  }

  private void require(boolean condition, String message) {
    if (!condition) throw invalid(message);
  }

  private InvalidQuestionDeckException invalid(String message) {
    return new InvalidQuestionDeckException(message);
  }
}

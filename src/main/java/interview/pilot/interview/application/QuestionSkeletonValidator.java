package interview.pilot.interview.application;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import interview.pilot.interview.domain.InterviewPhase;
import interview.pilot.interview.domain.InterviewSize;

/**
 * Deterministic validation for stage-1 question skeletons. This mirrors the old deck validator but
 * contains no grounding rules: a skeleton is produced before question-scoped retrieval exists.
 * Invalid output is an {@link InvalidQuestionDeckException} (a malformed LLM payload), never a
 * gateway failure.
 */
public class QuestionSkeletonValidator {

  private static final List<InterviewPhase> GENERATED_PHASES = List.of(
      InterviewPhase.FUNDAMENTALS,
      InterviewPhase.PROJECT_EXPERIENCE,
      InterviewPhase.SCENARIO_TRADEOFF);

  public List<QuestionSkeletonOutput.Skeleton> validate(
      QuestionSkeletonOutput output, InterviewSize size) {
    if (output == null || output.questions() == null || output.questions().isEmpty()) {
      throw new InvalidQuestionDeckException("questions must not be empty");
    }

    Map<InterviewPhase, List<QuestionSkeletonOutput.Skeleton>> byPhase = groupByPhase(output);
    List<QuestionSkeletonOutput.Skeleton> ordered = new ArrayList<>();
    Set<String> seenQuestions = new HashSet<>();

    for (InterviewPhase phase : GENERATED_PHASES) {
      List<QuestionSkeletonOutput.Skeleton> questions =
          byPhase.getOrDefault(phase, List.of());
      int expected = size.mainQuestionCount(phase);
      if (questions.size() != expected) {
        throw new InvalidQuestionDeckException(
            phase + " requires exactly " + expected + " questions but got " + questions.size());
      }
      for (int index = 0; index < questions.size(); index++) {
        var question = questions.get(index);
        int expectedSequence = index + 1;
        if (question.sequence() != expectedSequence) {
          throw new InvalidQuestionDeckException(
              phase + " sequence must be continuous starting from 1, expected " + expectedSequence);
        }
        validateSingle(question, seenQuestions);
        ordered.add(question);
      }
    }

    if (ordered.size() != output.questions().size()) {
      throw new InvalidQuestionDeckException("skeletons must only contain generated phases");
    }
    return List.copyOf(ordered);
  }

  private Map<InterviewPhase, List<QuestionSkeletonOutput.Skeleton>> groupByPhase(
      QuestionSkeletonOutput output) {
    var byPhase = new EnumMap<InterviewPhase, List<QuestionSkeletonOutput.Skeleton>>(
        InterviewPhase.class);
    for (var question : output.questions()) {
      if (question == null || question.phase() == null) {
        throw new InvalidQuestionDeckException("question and phase must not be null");
      }
      if (!GENERATED_PHASES.contains(question.phase())) {
        throw new InvalidQuestionDeckException(
            "skeleton phase must be a generated phase but was " + question.phase());
      }
      byPhase.computeIfAbsent(question.phase(), ignored -> new ArrayList<>()).add(question);
    }
    GENERATED_PHASES.forEach(phase -> byPhase
        .computeIfPresent(phase, (ignored, list) -> {
          list.sort(java.util.Comparator.comparingInt(QuestionSkeletonOutput.Skeleton::sequence));
          return list;
        }));
    return byPhase;
  }

  private void validateSingle(
      QuestionSkeletonOutput.Skeleton question, Set<String> seenQuestions) {
    requireLength(question.topic(), 1, 60, "topic");
    requireLength(question.question(), 20, 300, "question");
    requireLength(question.knowledgePoint(), 1, 80, "knowledgePoint");

    if (question.focusPoints() == null
        || question.focusPoints().size() < 2 || question.focusPoints().size() > 4) {
      throw new InvalidQuestionDeckException("focusPoints must contain 2 to 4 items");
    }
    question.focusPoints().forEach(point -> requireLength(point, 2, 80, "focusPoints item"));

    if (question.retrievalKeywords() == null
        || question.retrievalKeywords().isEmpty()
        || question.retrievalKeywords().size() > 5) {
      throw new InvalidQuestionDeckException("retrievalKeywords must contain 1 to 5 items");
    }
    question.retrievalKeywords()
        .forEach(keyword -> requireLength(keyword, 1, 40, "retrievalKeywords item"));

    requireLength(question.fallbackFollowUp(), 20, 160, "fallbackFollowUp");

    String normalized = question.question().replaceAll("\\s+", " ").trim().toLowerCase();
    if (!seenQuestions.add(normalized)) {
      throw new InvalidQuestionDeckException("duplicate question text: " + question.question());
    }
  }

  private void requireLength(String value, int min, int max, String field) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.length() < min || normalized.length() > max) {
      throw new InvalidQuestionDeckException(
          field + " must be " + min + " to " + max + " chars");
    }
  }
}

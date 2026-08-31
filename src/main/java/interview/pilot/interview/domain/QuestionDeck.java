package interview.pilot.interview.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable batch of primary questions prepared before the live interview.
 * Follow-up questions are intentionally not part of this deck; they are generated
 * only for turns where the deterministic interview policy requests a follow-up.
 */
public record QuestionDeck(List<GeneratedQuestion> questions) {
  public QuestionDeck {
    questions = questions == null ? List.of() : List.copyOf(questions);
    if (questions.isEmpty()) throw new IllegalArgumentException("question deck must not be empty");
    Set<String> fingerprints = new HashSet<>();
    for (GeneratedQuestion question : questions) {
      if (question == null) throw new IllegalArgumentException("question deck contains null");
      String fingerprint = question.question().trim().toLowerCase(java.util.Locale.ROOT);
      if (!fingerprints.add(fingerprint)) {
        throw new IllegalArgumentException("question deck contains duplicate questions");
      }
    }
  }

  public static QuestionDeck of(GeneratedQuestion first) {
    return new QuestionDeck(List.of(first));
  }

  public QuestionDeck withFirst(GeneratedQuestion first) {
    List<GeneratedQuestion> result = new ArrayList<>();
    result.add(first);
    for (GeneratedQuestion question : questions) {
      if (!question.question().equals(first.question())) result.add(question);
    }
    return new QuestionDeck(result);
  }

  public GeneratedQuestion next(String competency, Set<String> usedQuestions) {
    String target = competency == null ? "" : competency.trim();
    Set<String> used = usedQuestions == null ? Set.of() : usedQuestions;
    for (GeneratedQuestion question : questions) {
      if (used.contains(question.question())) continue;
      if (target.equalsIgnoreCase(question.targetCompetency())) return question;
    }
    for (GeneratedQuestion question : questions) {
      if (!used.contains(question.question())) return question;
    }
    return null;
  }
}

package interview.pilot.interview.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import interview.pilot.interview.domain.AnswerEvaluation;

/** Prevents model-produced evidence from being accepted without support in the current answer. */
public final class AnswerEvidenceValidator {
  private static final Pattern ASCII_TOKENS = Pattern.compile("[a-z0-9+#]{3,}");
  private static final Pattern CJK_RUNS = Pattern.compile("\\p{IsHan}{3,}");

  public AnswerEvaluation validate(String answer, AnswerEvaluation evaluation) {
    String normalizedAnswer = normalize(answer);
    List<String> accepted = new ArrayList<>();
    List<String> rejected = new ArrayList<>();
    for (String evidence : evaluation.evidence()) {
      if (grounded(evidence, normalizedAnswer)) accepted.add(evidence);
      else rejected.add(evidence);
    }
    if (rejected.isEmpty()) return evaluation;

    Set<String> missing = new LinkedHashSet<>(evaluation.missingPoints());
    rejected.forEach(value -> missing.add("当前回答未提供可核验依据：" + value));
    return new AnswerEvaluation(
        evaluation.score(), evaluation.feedback(), accepted, List.copyOf(missing),
        evaluation.redFlags(), evaluation.suggestedDecision());
  }

  private boolean grounded(String evidence, String answer) {
    String normalizedEvidence = normalize(evidence);
    String[] clauses = normalizedEvidence.split("(?:并且|以及|同时|并|且|\\band\\b)");
    if (clauses.length > 1) {
      return java.util.Arrays.stream(clauses)
          .filter(clause -> !clause.isBlank())
          .allMatch(clause -> groundedClause(clause, answer));
    }
    return groundedClause(normalizedEvidence, answer);
  }

  private boolean groundedClause(String normalizedEvidence, String answer) {
    if (normalizedEvidence.length() >= 4 && answer.contains(normalizedEvidence)) return true;
    int signals = 0;
    int matched = 0;
    var matcher = ASCII_TOKENS.matcher(normalizedEvidence);
    while (matcher.find()) {
      signals++;
      String token = matcher.group();
      if (answer.contains(token)) matched++;
    }
    matcher = CJK_RUNS.matcher(normalizedEvidence);
    while (matcher.find()) {
      String run = matcher.group();
      for (int index = 0; index <= run.length() - 3; index++) {
        signals++;
        if (answer.contains(run.substring(index, index + 3))) matched++;
      }
    }
    return signals > 0 && matched >= Math.min(2, signals);
  }

  private String normalize(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
  }
}

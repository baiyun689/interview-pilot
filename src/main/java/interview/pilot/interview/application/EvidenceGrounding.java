package interview.pilot.interview.application;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 判断一条证据声明是否能从候选人回答文本中找到依据。
 * 由 AnswerEvidenceValidator 与 EvidenceAssessmentValidator 共享，避免两套回溯算法漂移。
 */
final class EvidenceGrounding {
  private static final Pattern ASCII_TOKENS = Pattern.compile("[a-z0-9+#]{3,}");
  private static final Pattern CJK_RUNS = Pattern.compile("\\p{IsHan}{3,}");

  private EvidenceGrounding() {}

  static boolean grounded(String evidence, String answer) {
    String normalizedEvidence = normalize(evidence);
    if (normalizedEvidence.length() >= 4 && answer.contains(normalizedEvidence)) return true;
    String[] clauses = normalizedEvidence.split("(?:并且|以及|同时|并|且|\\band\\b)");
    if (clauses.length > 1) {
      return java.util.Arrays.stream(clauses)
          .filter(clause -> !clause.isBlank())
          .allMatch(clause -> groundedClause(clause, answer));
    }
    return groundedClause(normalizedEvidence, answer);
  }

  static String normalize(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
  }

  private static boolean groundedClause(String normalizedEvidence, String answer) {
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
}

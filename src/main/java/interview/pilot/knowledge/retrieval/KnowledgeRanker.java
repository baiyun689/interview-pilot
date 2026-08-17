package interview.pilot.knowledge.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class KnowledgeRanker {
  public List<KnowledgeChunk> rank(
      List<KnowledgeChunk> candidates, int topK, double minimumScore, int characterBudget) {
    List<KnowledgeChunk> sorted = candidates.stream()
        .filter(chunk -> chunk.score() >= minimumScore)
        .sorted(Comparator.comparingDouble(KnowledgeChunk::score).reversed())
        .toList();
    List<KnowledgeChunk> selected = new ArrayList<>();
    Set<String> fingerprints = new HashSet<>();
    int characters = 0;
    for (KnowledgeChunk candidate : sorted) {
      if (selected.size() >= topK) break;
      String fingerprint = fingerprint(candidate.content());
      if (fingerprints.stream().anyMatch(existing -> nearDuplicate(existing, fingerprint))) continue;
      if (characters + candidate.content().length() > characterBudget) continue;
      selected.add(candidate);
      fingerprints.add(fingerprint);
      characters += candidate.content().length();
    }
    return List.copyOf(selected);
  }

  private String fingerprint(String value) {
    return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
  }

  private boolean nearDuplicate(String left, String right) {
    if (left.equals(right)) return true;
    if (left.length() < 8 || right.length() < 8) return false;
    String shorter = left.length() <= right.length() ? left : right;
    String longer = left.length() > right.length() ? left : right;
    return longer.contains(shorter) && shorter.length() >= longer.length() * 0.8;
  }
}

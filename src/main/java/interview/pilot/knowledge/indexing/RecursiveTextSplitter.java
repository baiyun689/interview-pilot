package interview.pilot.knowledge.indexing;

import java.util.ArrayList;
import java.util.List;

public final class RecursiveTextSplitter {
  private static final int MAX_SIZE = 1_600;
  private static final int OVERLAP_SIZE = 200;
  private static final int MAX_CONTENT_SIZE = MAX_SIZE - OVERLAP_SIZE;
  private static final List<String> SEPARATORS = List.of(
      "\n# ", "\n## ", "\n### ", "\n#### ", "\n\n", "\n",
      "。", "！", "？", "；", ". ", "! ", "? ", "; ", ", ", " ", "");

  public List<String> split(String text) {
    if (text == null || text.isBlank()) {
      return List.of();
    }
    List<String> chunks = new ArrayList<>();
    splitRecursively(normalizeLineEndings(text).strip(), 0, chunks);
    return addOverlap(chunks);
  }

  private void splitRecursively(String text, int separatorIndex, List<String> chunks) {
    String trimmed = text.strip();
    if (trimmed.isBlank()) {
      return;
    }
    if (trimmed.length() <= MAX_CONTENT_SIZE) {
      chunks.add(trimmed);
      return;
    }
    if (separatorIndex >= SEPARATORS.size() || SEPARATORS.get(separatorIndex).isEmpty()) {
      splitByFixedSize(trimmed, chunks);
      return;
    }

    List<String> parts = splitBySeparator(trimmed, SEPARATORS.get(separatorIndex));
    if (parts.size() <= 1) {
      splitRecursively(trimmed, separatorIndex + 1, chunks);
      return;
    }
    mergeOrRecurse(parts, separatorIndex + 1, chunks);
  }

  private void mergeOrRecurse(List<String> parts, int nextSeparatorIndex, List<String> chunks) {
    StringBuilder current = new StringBuilder();
    for (String part : parts) {
      String cleanPart = part.strip();
      if (cleanPart.isBlank()) {
        continue;
      }
      if (cleanPart.length() > MAX_CONTENT_SIZE) {
        flush(current, chunks);
        splitRecursively(cleanPart, nextSeparatorIndex, chunks);
        continue;
      }
      String merged = merge(current.toString(), cleanPart);
      if (merged.length() <= MAX_CONTENT_SIZE) {
        current.setLength(0);
        current.append(merged);
      } else {
        flush(current, chunks);
        current.append(cleanPart);
      }
    }
    flush(current, chunks);
  }

  private static List<String> splitBySeparator(String text, String separator) {
    return separator.stripLeading().startsWith("#")
        ? splitKeepingSeparatorAtStart(text, separator)
        : splitKeepingSeparatorAtEnd(text, separator);
  }

  private static List<String> splitKeepingSeparatorAtStart(String text, String separator) {
    List<Integer> boundaries = new ArrayList<>();
    String startSeparator = separator.startsWith("\n") ? separator.substring(1) : separator;
    if (text.startsWith(startSeparator)) {
      boundaries.add(0);
    }
    int index = text.indexOf(separator);
    while (index >= 0) {
      int boundary = separator.startsWith("\n") ? index + 1 : index;
      if (boundaries.isEmpty() || boundaries.getLast() != boundary) {
        boundaries.add(boundary);
      }
      index = text.indexOf(separator, index + separator.length());
    }
    if (boundaries.isEmpty()) {
      return List.of(text);
    }

    List<String> parts = new ArrayList<>();
    if (boundaries.getFirst() > 0) {
      parts.add(text.substring(0, boundaries.getFirst()));
    }
    for (int indexInList = 0; indexInList < boundaries.size(); indexInList++) {
      int start = boundaries.get(indexInList);
      int end = indexInList + 1 < boundaries.size()
          ? boundaries.get(indexInList + 1) : text.length();
      parts.add(text.substring(start, end));
    }
    return parts;
  }

  private static List<String> splitKeepingSeparatorAtEnd(String text, String separator) {
    List<String> parts = new ArrayList<>();
    int start = 0;
    int index = text.indexOf(separator, start);
    while (index >= 0) {
      int end = index + separator.length();
      parts.add(text.substring(start, end));
      start = end;
      index = text.indexOf(separator, start);
    }
    parts.add(text.substring(start));
    return parts;
  }

  private static String merge(String current, String next) {
    if (current.isBlank()) {
      return next;
    }
    if (current.endsWith("\n") || next.startsWith("\n")) {
      return current + next;
    }
    if (current.contains("\n") || next.startsWith("#")) {
      return current + "\n" + next;
    }
    return current + " " + next;
  }

  private static void flush(StringBuilder current, List<String> chunks) {
    String chunk = current.toString().strip();
    if (!chunk.isBlank()) {
      chunks.add(chunk);
    }
    current.setLength(0);
  }

  private static void splitByFixedSize(String text, List<String> chunks) {
    for (int start = 0; start < text.length();) {
      int end = Math.min(start + MAX_CONTENT_SIZE, text.length());
      if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))
          && Character.isLowSurrogate(text.charAt(end))) {
        end--;
      }
      String chunk = text.substring(start, end).strip();
      if (!chunk.isBlank()) {
        chunks.add(chunk);
      }
      start = end;
    }
  }

  private static List<String> addOverlap(List<String> chunks) {
    if (chunks.size() <= 1) {
      return chunks;
    }
    List<String> result = new ArrayList<>(chunks.size());
    result.add(chunks.getFirst());
    for (int index = 1; index < chunks.size(); index++) {
      String overlap = buildOverlap(chunks.get(index - 1));
      String current = chunks.get(index);
      result.add(overlap.isBlank() ? current : overlap + current);
    }
    return result;
  }

  private static String buildOverlap(String text) {
    int start = Math.max(0, text.length() - OVERLAP_SIZE);
    if (start > 0 && Character.isLowSurrogate(text.charAt(start))
        && Character.isHighSurrogate(text.charAt(start - 1))) {
      start++;
    }
    return text.substring(start);
  }

  private static String normalizeLineEndings(String text) {
    return text.replace("\r\n", "\n").replace('\r', '\n');
  }
}

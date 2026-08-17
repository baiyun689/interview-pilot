package interview.pilot.interview.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class CompetencyMatcher {
  private CompetencyMatcher() {
  }

  public static boolean same(String left, String right) {
    return key(left).equals(key(right));
  }

  public static boolean related(String left, String right) {
    String a = key(left);
    String b = key(right);
    if (a.isEmpty() || b.isEmpty()) return false;
    if (a.equals(b)) return true;
    if (tokens(a).stream().anyMatch(tokens(b)::contains)) return true;
    String shorter = a.length() <= b.length() ? a : b;
    String longer = a.length() <= b.length() ? b : a;
    return containsCjk(shorter) && longer.contains(shorter);
  }

  public static String key(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  private static List<String> tokens(String value) {
    return Arrays.stream(value.split("[^\\p{L}\\p{N}+#]+"))
        .filter(token -> token.length() >= 2)
        .toList();
  }

  private static boolean containsCjk(String value) {
    return value.codePoints().anyMatch(codePoint ->
        Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
  }
}

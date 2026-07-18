package interview.pilot.resume.infrastructure;

import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

@Service
public class TextCleaningService {
  private static final Pattern CONTROL_CHARACTERS =
      Pattern.compile("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]");
  private static final Pattern TRAILING_HORIZONTAL_WHITESPACE = Pattern.compile("(?m)[ \\t]+$");
  private static final Pattern REPEATED_BLANK_LINES = Pattern.compile("\\n{3,}");

  public String cleanText(String text) {
    if (text == null || text.isBlank()) {
      return "";
    }

    String cleaned = CONTROL_CHARACTERS.matcher(text).replaceAll("");
    cleaned = cleaned.replace("\r\n", "\n").replace('\r', '\n');
    cleaned = TRAILING_HORIZONTAL_WHITESPACE.matcher(cleaned).replaceAll("");
    cleaned = REPEATED_BLANK_LINES.matcher(cleaned).replaceAll("\n\n");
    return cleaned.strip();
  }
}

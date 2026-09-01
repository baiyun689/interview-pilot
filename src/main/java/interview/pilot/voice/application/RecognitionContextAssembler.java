package interview.pilot.voice.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import interview.pilot.interview.domain.InterviewBriefSnapshot;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.resume.domain.ResumeProfile;
import interview.pilot.voice.domain.TranscriptionVocabulary;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Builds the recognition context (plan §10) from the session's brief snapshot: job title,
 * Latin technical tokens of the JD (English tech names — Spring, MySQL, JVM — are the terms
 * a recognizer mishears; Chinese backend terms are covered by the fixed word list and the
 * resume skills, which are added verbatim), resume technical skills and project technologies,
 * and the fixed Java-backend word list. Every source degrades to "absent" independently: a
 * missing/unparseable snapshot, a blank JD, a session without a resume — the fixed list always
 * remains. Vocabulary is a hint channel: extraction here is best-effort and bounded.
 */
@Component
public class RecognitionContextAssembler {

  static final int MAX_JD_TERMS = 30;
  static final int MAX_TOTAL_TERMS = 100;
  private static final Pattern LATIN_TOKEN =
      Pattern.compile("[A-Za-z][A-Za-z0-9_+#.-]*");
  private static final Set<String> JD_STOPWORDS = Set.of(
      "the", "and", "for", "are", "with", "your", "from", "have", "our", "will",
      "can", "that", "this", "was", "not", "has", "its", "but", "all", "any",
      "one", "who", "than", "then", "them", "they", "their", "there", "where",
      "when", "what", "which", "while", "into", "over", "also", "more", "most",
      "must", "been", "being", "both", "each", "other", "some", "such", "very",
      "well", "job", "work", "team");

  private final ObjectMapper json;

  public RecognitionContextAssembler(ObjectMapper json) {
    this.json = json;
  }

  public RecognitionContext assemble(InterviewSessionEntity session) {
    InterviewBriefSnapshot brief = decode(session.getBriefSnapshot());
    List<String> vocabulary = new ArrayList<>();
    add(vocabulary, brief == null ? null : brief.jobTitle());
    if (brief != null) {
      addJdTerms(vocabulary, brief.jobDescription());
      addResumeTerms(vocabulary, brief.resume());
    }
    vocabulary.addAll(TranscriptionVocabulary.JAVA_BACKEND_TERMS);
    return new RecognitionContext(dedupe(vocabulary));
  }

  private void addJdTerms(List<String> vocabulary, String jobDescription) {
    if (jobDescription == null || jobDescription.isBlank()) {
      return;
    }
    Matcher latin = LATIN_TOKEN.matcher(jobDescription);
    int added = 0;
    while (latin.find() && added < MAX_JD_TERMS) {
      String token = latin.group();
      if (token.length() >= 2
          && !JD_STOPWORDS.contains(token.toLowerCase(Locale.ROOT))) {
        vocabulary.add(token);
        added++;
      }
    }
  }

  private void addResumeTerms(List<String> vocabulary, ResumeProfile resume) {
    if (resume == null) {
      return;
    }
    resume.technicalSkills().forEach(skill -> add(vocabulary, skill));
    resume.projects().forEach(
        project -> project.technologies().forEach(technology -> add(vocabulary, technology)));
  }

  private static void add(List<String> vocabulary, String term) {
    if (term != null && !term.isBlank()) {
      vocabulary.add(term.trim());
    }
  }

  private InterviewBriefSnapshot decode(String briefSnapshot) {
    if (briefSnapshot == null || briefSnapshot.isBlank()) {
      return null;
    }
    try {
      return json.readValue(briefSnapshot, InterviewBriefSnapshot.class);
    } catch (JacksonException exception) {
      // A hint channel must never break transcription: corrupt snapshot → fixed list only.
      return null;
    }
  }

  private static List<String> dedupe(List<String> vocabulary) {
    var seen = new LinkedHashSet<>(vocabulary);
    if (seen.size() <= MAX_TOTAL_TERMS) {
      return List.copyOf(seen);
    }
    return List.copyOf(seen).subList(0, MAX_TOTAL_TERMS);
  }
}

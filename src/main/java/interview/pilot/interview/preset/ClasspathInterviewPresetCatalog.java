package interview.pilot.interview.preset;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

@Component
public final class ClasspathInterviewPresetCatalog implements InterviewPresetCatalog {
  private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
  private final List<InterviewPreset> presets;
  private final Map<String, InterviewPreset> byId;

  public ClasspathInterviewPresetCatalog() {
    presets = load();
    var indexed = new LinkedHashMap<String, InterviewPreset>();
    for (InterviewPreset preset : presets) {
      if (indexed.putIfAbsent(preset.id(), preset) != null) {
        throw new IllegalStateException("Interview preset ID duplicated: " + preset.id());
      }
    }
    byId = Map.copyOf(indexed);
  }

  @Override
  public List<InterviewPreset> list() {
    return presets;
  }

  @Override
  public InterviewPreset require(String id) {
    InterviewPreset preset = byId.get(id == null ? "" : id.trim());
    if (preset == null) throw new IllegalArgumentException("Interview preset not found: " + id);
    return preset;
  }

  @SuppressWarnings("unchecked")
  private List<InterviewPreset> load() {
    try {
      var resolver = new PathMatchingResourcePatternResolver();
      List<InterviewPreset> result = new ArrayList<>();
      for (Resource resource : resolver.getResources("classpath*:interview-presets/*.yml")) {
        String id = resource.getFilename() == null
            ? "" : resource.getFilename().replaceFirst("\\.yml$", "");
        if (!SAFE_ID.matcher(id).matches()) throw new IllegalStateException("Invalid preset ID: " + id);
        Map<String, Object> yaml = new Yaml().load(
            resource.getContentAsString(StandardCharsets.UTF_8));
        if (yaml == null) throw new IllegalStateException("Empty interview preset: " + id);
        String name = required(yaml, "displayName", id);
        String description = required(yaml, "description", id);
        String jd = required(yaml, "jobDescription", id);
        result.add(new InterviewPreset(id, name, description, jd,
            sha256(String.join("\n", id, name, description, jd))));
      }
      result.sort(Comparator.comparing(InterviewPreset::id));
      return List.copyOf(result);
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to load interview presets", exception);
    }
  }

  private String required(Map<String, Object> yaml, String key, String id) {
    String value = yaml.get(key) == null ? "" : yaml.get(key).toString().trim();
    if (value.isEmpty()) throw new IllegalStateException("Preset " + id + " missing " + key);
    return value;
  }

  private String sha256(String value) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }
}

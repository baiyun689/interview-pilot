package interview.pilot.interview.skill;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

@Component
public class ClasspathInterviewSkillCatalog implements InterviewSkillCatalog {
  private static final Pattern SKILL_PATH =
      Pattern.compile(".*/skills/([^/]+)/skill\\.meta\\.yml$");
  private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
  private static final Pattern SAFE_REFERENCE = Pattern.compile("[a-zA-Z0-9._-]+\\.md");

  private final List<InterviewSkill> skills;
  private final Map<String, InterviewSkill> byId;

  public ClasspathInterviewSkillCatalog() {
    this.skills = loadSkills();
    var indexed = new LinkedHashMap<String, InterviewSkill>();
    for (InterviewSkill skill : skills) {
      if (indexed.putIfAbsent(skill.id(), skill) != null) {
        throw invalid("Skill ID 重复: " + skill.id());
      }
    }
    if (!indexed.containsKey("custom")
        || indexed.get("custom").group() != SkillGroup.CUSTOM) {
      throw invalid("必须配置 CUSTOM 分组的 custom Skill");
    }
    this.byId = Map.copyOf(indexed);
  }

  @Override
  public List<InterviewSkill> list() {
    return skills;
  }

  @Override
  public InterviewSkill require(String skillId) {
    String normalized = skillId == null ? "" : skillId.trim();
    InterviewSkill skill = byId.get(normalized);
    if (skill == null) throw new InvalidInterviewSkillException(normalized);
    return skill;
  }

  private List<InterviewSkill> loadSkills() {
    try {
      Resource[] resources = new PathMatchingResourcePatternResolver()
          .getResources("classpath*:skills/*/skill.meta.yml");
      var loaded = new ArrayList<InterviewSkill>();
      for (Resource resource : resources) loaded.add(loadSkill(resource));
      loaded.sort(Comparator.comparing(InterviewSkill::id));
      return List.copyOf(loaded);
    } catch (IOException exception) {
      throw new IllegalStateException("无法扫描面试 Skill 资源", exception);
    }
  }

  @SuppressWarnings("unchecked")
  private InterviewSkill loadSkill(Resource metaResource) {
    try {
      String id = extractId(metaResource);
      Map<String, Object> meta = new Yaml().load(metaResource.getContentAsString(StandardCharsets.UTF_8));
      if (meta == null) throw invalid("Skill 元数据为空: " + id);
      String name = required(meta.get("displayName"), id, "displayName");
      String description = required(meta.get("description"), id, "description");
      SkillGroup group = parseGroup(required(meta.get("group"), id, "group"), id);
      Map<String, Object> displayMap = meta.get("display") instanceof Map<?, ?> map
          ? (Map<String, Object>) map : Map.of();
      String icon = required(displayMap.get("icon"), id, "display.icon");
      List<String> competencies = strings(meta.get("defaultCompetencies"), id, true);
      List<String> references = strings(meta.get("references"), id, false);
      references.forEach(reference -> {
        if (!SAFE_REFERENCE.matcher(reference).matches()) {
          throw invalid("Skill reference 路径不安全: " + id + "/" + reference);
        }
      });
      String persona = readRequired(id, "SKILL.md");
      String rubric = readRequired(id, "rubric.md");
      String version = sha256(String.join("\n",
          id, name, description, group.name(), icon,
          String.join("|", competencies), persona, rubric, String.join("|", references)));
      return new InterviewSkill(
          id, name, description, group, new InterviewSkill.Display(icon),
          competencies, persona, rubric, references, version);
    } catch (IOException exception) {
      throw new IllegalStateException("无法读取面试 Skill 资源: " + metaResource, exception);
    }
  }

  private String extractId(Resource resource) throws IOException {
    String normalized = resource.getURL().toString().replace('\\', '/');
    Matcher matcher = SKILL_PATH.matcher(normalized);
    if (!matcher.matches() || !SAFE_ID.matcher(matcher.group(1)).matches()) {
      throw invalid("Skill 资源路径无效: " + normalized);
    }
    return matcher.group(1);
  }

  private List<String> strings(Object value, String id, boolean required) {
    if (!(value instanceof List<?> list)) {
      if (required) throw invalid("Skill 缺少列表字段: " + id);
      return List.of();
    }
    List<String> values = list.stream()
        .map(item -> item == null ? "" : item.toString().trim())
        .filter(item -> !item.isEmpty())
        .distinct()
        .toList();
    if (required && values.isEmpty()) throw invalid("Skill 默认能力为空: " + id);
    return values;
  }

  private String readRequired(String id, String filename) throws IOException {
    Resource resource = new ClassPathResource("skills/" + id + "/" + filename);
    if (!resource.exists()) throw invalid("Skill 缺少文件: " + id + "/" + filename);
    String value = resource.getContentAsString(StandardCharsets.UTF_8).trim();
    if (value.isEmpty()) throw invalid("Skill 文件为空: " + id + "/" + filename);
    return value;
  }

  private String required(Object value, String id, String field) {
    String text = value == null ? "" : value.toString().trim();
    if (text.isEmpty()) throw invalid("Skill 缺少字段: " + id + "/" + field);
    return text;
  }

  private SkillGroup parseGroup(String value, String id) {
    try {
      return SkillGroup.valueOf(value);
    } catch (IllegalArgumentException exception) {
      throw invalid("Skill 分组无效: " + id + "/" + value);
    }
  }

  private String sha256(String value) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 不可用", exception);
    }
  }

  private IllegalStateException invalid(String message) {
    return new IllegalStateException(message);
  }
}

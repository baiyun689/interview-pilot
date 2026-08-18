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
  private static final Pattern SKILL_PATH_V5 =
      Pattern.compile(".*/skills/([^/]+)/skill\\.yml$");
  private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
  private static final Pattern SAFE_RESOURCE = Pattern.compile("[a-zA-Z0-9._-]+\\.(?:md|yml)");

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
      var resolver = new PathMatchingResourcePatternResolver();
      List<Resource> resources = new ArrayList<>();
      resources.addAll(List.of(resolver.getResources("classpath*:skills/*/skill.meta.yml")));
      resources.addAll(List.of(resolver.getResources("classpath*:skills/*/skill.yml")));
      var loaded = new ArrayList<InterviewSkill>();
      var seenIds = new java.util.HashSet<String>();
      for (Resource resource : resources) {
        String id = extractId(resource);
        if (!seenIds.add(id)) {
          throw invalid("Skill 同时存在 skill.yml 与 skill.meta.yml: " + id);
        }
        loaded.add(loadSkill(resource));
      }
      loaded.sort(Comparator.comparing(InterviewSkill::id));
      return List.copyOf(loaded);
    } catch (IOException exception) {
      throw new IllegalStateException("无法扫描面试 Skill 资源", exception);
    }
  }

  private InterviewSkill loadSkill(Resource metaResource) {
    try {
      String id = extractId(metaResource);
      String path = metaResource.getURL().toString().replace('\\', '/');
      if (path.endsWith("/skill.yml")) return loadV5Skill(metaResource, id);
      return loadLegacySkill(metaResource, id);
    } catch (IOException exception) {
      throw new IllegalStateException("无法读取面试 Skill 资源: " + metaResource, exception);
    }
  }

  @SuppressWarnings("unchecked")
  private InterviewSkill loadLegacySkill(Resource metaResource, String id) {
    try {
      Map<String, Object> meta = new Yaml().load(metaResource.getContentAsString(StandardCharsets.UTF_8));
      if (meta == null) throw invalid("Skill 元数据为空: " + id);
      int schemaVersion = integer(meta.get("schemaVersion"), 1, id, "schemaVersion");
      if (schemaVersion > 4) throw invalid("Skill schemaVersion 不受支持: " + id);
      String name = required(meta.get("displayName"), id, "displayName");
      String description = required(meta.get("description"), id, "description");
      SkillGroup group = parseGroup(required(meta.get("group"), id, "group"), id);
      Map<String, Object> displayMap = meta.get("display") instanceof Map<?, ?> map
          ? (Map<String, Object>) map : Map.of();
      String icon = schemaVersion >= 4
          ? required(meta.get("icon"), id, "icon")
          : required(displayMap.get("icon"), id, "display.icon");
      Map<String, Object> stageSource = meta;
      Map<String, Object> competencySource = meta;
      String personaFile = "SKILL.md";
      String rubricFile = "rubric.md";
      if (schemaVersion == 3) {
        Map<String, Object> resources = map(meta.get("resources"), id, "resources");
        String stagesFile = resourceFile(resources, id, "stages", ".yml");
        String competenciesFile = resourceFile(resources, id, "competencies", ".yml");
        personaFile = resourceFile(resources, id, "persona", ".md");
        rubricFile = resourceFile(resources, id, "rubric", ".md");
        stageSource = readRequiredYaml(id, stagesFile);
        competencySource = readRequiredYaml(id, competenciesFile);
      } else if (schemaVersion >= 4) {
        stageSource = readRequiredYaml(id, "stages.yml");
        competencySource = readRequiredYaml(id, "competencies.yml");
      }
      List<String> competencies = schemaVersion >= 4 ? List.of() : strings(
          competencySource.get("defaultCompetencies"), id, true);
      List<SkillStageSpec> stages = stages(stageSource, id);
      List<CompetencySpec> competencySpecs = competencies(
          competencySource, id, competencies, schemaVersion);
      SkillRetrievalPolicy retrievalPolicy = schemaVersion >= 4
          ? SkillRetrievalPolicy.disabled() : retrievalPolicy(meta);
      validateModel(id, competencies, stages, competencySpecs, schemaVersion);
      String persona = readRequired(id, personaFile);
      String rubric = readRequired(id, rubricFile);
      String version = sha256(String.join("\n",
          id, name, description, group.name(), icon,
          String.join("|", competencies), stages.toString(), competencySpecs.toString(),
          retrievalPolicy.toString(), persona, rubric));
      return new InterviewSkill(
          id, name, description, group, new InterviewSkill.Display(icon),
          competencies, stages, competencySpecs, retrievalPolicy,
          persona, rubric, List.of(), version, schemaVersion);
    } catch (IOException exception) {
      throw new IllegalStateException("无法读取面试 Skill 资源: " + metaResource, exception);
    }
  }

  @SuppressWarnings("unchecked")
  private InterviewSkill loadV5Skill(Resource metaResource, String id) {
    try {
      Map<String, Object> meta =
          new Yaml().load(metaResource.getContentAsString(StandardCharsets.UTF_8));
      if (meta == null) throw invalid("Skill 元数据为空: " + id);
      String name = required(meta.get("displayName"), id, "displayName");
      String description = required(meta.get("description"), id, "description");
      SkillGroup group = parseGroup(required(meta.get("group"), id, "group"), id);
      String icon = required(meta.get("icon"), id, "icon");
      List<String> redFlags = strings(meta.get("redFlags"), id, false);

      List<SkillStageSpec> stages;
      if (meta.get("stages") instanceof List<?> list && !list.isEmpty()) {
        stages = stages(meta, id);
        validateStageUniqueness(id, stages);
      } else {
        stages = SkillStageSpec.DEFAULT_STAGES;
      }

      List<CompetencySpec> specs = v5Competencies(meta, id);
      validateModelV5(id, stages, specs);

      String handbook = readRequired(id, "SKILL.md");
      String version = sha256(String.join("\n",
          id, name, description, group.name(), icon,
          String.join("|", redFlags), stages.toString(), specs.toString(), handbook));
      return new InterviewSkill(
          id, name, description, group, new InterviewSkill.Display(icon),
          List.of(), stages, specs, SkillRetrievalPolicy.disabled(),
          handbook, handbook, redFlags, version, 5);
    } catch (IOException exception) {
      throw new IllegalStateException("无法读取面试 Skill 资源: " + metaResource, exception);
    }
  }

  @SuppressWarnings("unchecked")
  private List<CompetencySpec> v5Competencies(Map<String, Object> meta, String skillId) {
    Object configured = meta.get("competencies");
    if (!(configured instanceof List<?> list) || list.isEmpty()) {
      throw invalid("Skill 缺少 competencies: " + skillId);
    }
    List<CompetencySpec> result = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> raw)) {
        throw invalid("Skill competency 格式无效: " + skillId);
      }
      Map<String, Object> competency = (Map<String, Object>) raw;
      List<InterviewQuestionMode> modes = strings(competency.get("modes"), skillId, false).stream()
          .map(value -> parseQuestionMode(value, skillId))
          .toList();
      List<String> scopes = strings(competency.get("ragScopes"), skillId, false);
      KnowledgeDomains.requireRegistered(skillId, scopes);
      SkillRetrievalPolicy policy = scopes.isEmpty()
          ? SkillRetrievalPolicy.disabled()
          : new SkillRetrievalPolicy(true, scopes,
              List.of(GroundingUse.GENERATE_SCENARIO, GroundingUse.VERIFY_FACT));
      result.add(new CompetencySpec(
          required(competency.get("id"), skillId, "competencies.id"),
          required(competency.get("name"), skillId, "competencies.name"),
          required(competency.get("objective"), skillId, "competencies.objective"),
          strings(competency.get("evidence"), skillId, true),
          modes,
          strings(competency.get("probes"), skillId, false),
          List.of(), 2, policy,
          optional(competency.get("stage"))));
    }
    return List.copyOf(result);
  }

  private void validateModelV5(String skillId, List<SkillStageSpec> stages,
      List<CompetencySpec> competencies) {
    if (competencies.stream().map(CompetencySpec::id).map(String::toLowerCase).distinct().count()
        != competencies.size()) {
      throw invalid("Skill competency ID 重复: " + skillId);
    }
    java.util.Set<String> stageIds = stages.stream()
        .map(SkillStageSpec::id)
        .map(String::toLowerCase)
        .collect(java.util.stream.Collectors.toSet());
    for (CompetencySpec competency : competencies) {
      if (!competency.stageId().isBlank()
          && !stageIds.contains(competency.stageId().toLowerCase())) {
        throw invalid("Skill competency 引用了不存在的 stage: "
            + skillId + "/" + competency.id() + "/" + competency.stageId());
      }
    }
  }

  private String extractId(Resource resource) throws IOException {
    String normalized = resource.getURL().toString().replace('\\', '/');
    Matcher matcher = SKILL_PATH.matcher(normalized);
    if (!matcher.matches()) matcher = SKILL_PATH_V5.matcher(normalized);
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

  @SuppressWarnings("unchecked")
  private List<SkillStageSpec> stages(Map<String, Object> meta, String skillId) {
    Object configured = meta.get("stages");
    if (configured instanceof List<?> list && !list.isEmpty()) {
      List<SkillStageSpec> result = new ArrayList<>();
      for (Object item : list) {
        if (!(item instanceof Map<?, ?> raw)) {
          throw invalid("Skill stage 格式无效: " + skillId);
        }
        Map<String, Object> stage = (Map<String, Object>) raw;
        result.add(new SkillStageSpec(
            required(stage.get("id"), skillId, "stages.id"),
            required(stage.get("purpose"), skillId, "stages.purpose"),
            integer(stage.get("order"), (result.size() + 1) * 10, skillId, "stages.order")));
      }
      return List.copyOf(result);
    }
    List<String> legacy = strings(meta.get("defaultStages"), skillId, false);
    if (legacy.isEmpty()) {
      return List.of(new SkillStageSpec("technical_depth", "验证核心技术能力"));
    }
    return legacy.stream()
        .map(stage -> new SkillStageSpec(stage, legacyStagePurpose(stage)))
        .toList();
  }

  @SuppressWarnings("unchecked")
  private List<CompetencySpec> competencies(
      Map<String, Object> meta, String skillId, List<String> defaults, int schemaVersion) {
    Object configured = meta.get("competencies");
    if (!(configured instanceof List<?> list) || list.isEmpty()) {
      List<CompetencySpec> result = new ArrayList<>();
      for (int index = 0; index < defaults.size(); index++) {
        result.add(CompetencySpec.legacy("legacy-" + (index + 1), defaults.get(index)));
      }
      return List.copyOf(result);
    }

    List<CompetencySpec> result = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> raw)) {
        throw invalid("Skill competency 格式无效: " + skillId);
      }
      Map<String, Object> competency = (Map<String, Object>) raw;
      String modesField = schemaVersion >= 4 ? "modes" : "questionModes";
      List<InterviewQuestionMode> modes = strings(
          competency.get(modesField), skillId, false).stream()
          .map(value -> parseQuestionMode(value, skillId))
          .toList();
      SkillRetrievalPolicy policy;
      if (schemaVersion >= 4) {
        List<String> scopes = strings(competency.get("ragScopes"), skillId, false);
        KnowledgeDomains.requireRegistered(skillId, scopes);
        policy = scopes.isEmpty() ? SkillRetrievalPolicy.disabled() : new SkillRetrievalPolicy(
            true, scopes,
            List.of(GroundingUse.GENERATE_SCENARIO, GroundingUse.VERIFY_FACT));
      } else {
        policy = competency.get("rag") instanceof Map<?, ?> rag
            ? retrievalPolicy((Map<String, Object>) rag, List.of())
            : SkillRetrievalPolicy.disabled();
      }
      result.add(new CompetencySpec(
          required(competency.get("id"), skillId, "competencies.id"),
          required(competency.get("name"), skillId, "competencies.name"),
          optional(competency.get("objective")),
          strings(competency.get(schemaVersion >= 4 ? "evidence" : "requiredEvidence"),
              skillId, true),
          modes,
          strings(competency.get(schemaVersion >= 4 ? "probes" : "followUpAxes"),
              skillId, false),
          schemaVersion >= 4 ? List.of()
              : strings(competency.get("redFlags"), skillId, false),
          schemaVersion >= 4 ? 2
              : integer(competency.get("followUpLimit"), 2, skillId, "followUpLimit"),
          policy,
          optional(competency.get(schemaVersion >= 4 ? "stage" : "stageId"))));
    }
    return List.copyOf(result);
  }

  @SuppressWarnings("unchecked")
  private SkillRetrievalPolicy retrievalPolicy(Map<String, Object> meta) {
    List<String> scopes = strings(meta.get("retrievalScopes"), "retrieval", false);
    if (meta.get("retrieval") instanceof Map<?, ?> retrieval) {
      return retrievalPolicy((Map<String, Object>) retrieval, scopes);
    }
    boolean enabled = false;
    if (meta.get("runtime") instanceof Map<?, ?> runtime) {
      enabled = Boolean.TRUE.equals(((Map<String, Object>) runtime).get("ragEnabled"));
    }
    return new SkillRetrievalPolicy(
        enabled, scopes,
        enabled ? List.of(GroundingUse.GENERATE_SCENARIO, GroundingUse.VERIFY_FACT) : List.of());
  }

  private SkillRetrievalPolicy retrievalPolicy(
      Map<String, Object> configured, List<String> fallbackScopes) {
    boolean enabled = Boolean.TRUE.equals(configured.get("enabled"));
    List<String> scopes = strings(configured.get("scopes"), "retrieval", false);
    if (scopes.isEmpty()) scopes = fallbackScopes;
    return new SkillRetrievalPolicy(
        enabled, scopes,
        groundingUses(configured.get("allowedUses"), "retrieval"),
        optionalInteger(configured.get("topK"), "retrieval.topK"),
        optionalInteger(configured.get("candidateCount"), "retrieval.candidateCount"),
        optionalDouble(configured.get("minimumScore"), "retrieval.minimumScore"),
        optionalInteger(configured.get("contextCharacterBudget"),
            "retrieval.contextCharacterBudget"));
  }

  private List<GroundingUse> groundingUses(Object value, String skillId) {
    return strings(value, skillId, false).stream().map(item -> {
      try {
        return GroundingUse.valueOf(item);
      } catch (IllegalArgumentException exception) {
        throw invalid("Skill RAG 用途无效: " + skillId + "/" + item);
      }
    }).toList();
  }

  private Integer optionalInteger(Object value, String field) {
    return value == null ? null : integer(value, 0, "retrieval", field);
  }

  private Double optionalDouble(Object value, String field) {
    if (value == null) return null;
    try {
      return Double.valueOf(value.toString());
    } catch (NumberFormatException exception) {
      throw invalid("Skill 数字字段无效: retrieval/" + field);
    }
  }

  private InterviewQuestionMode parseQuestionMode(String value, String skillId) {
    try {
      return InterviewQuestionMode.valueOf(value);
    } catch (IllegalArgumentException exception) {
      throw invalid("Skill question mode 无效: " + skillId + "/" + value);
    }
  }

  private int integer(Object value, int fallback, String skillId, String field) {
    if (value == null) return fallback;
    if (value instanceof Number number) return number.intValue();
    try {
      return Integer.parseInt(value.toString());
    } catch (NumberFormatException exception) {
      throw invalid("Skill 数字字段无效: " + skillId + "/" + field);
    }
  }

  private String optional(Object value) {
    return value == null ? "" : value.toString().trim();
  }

  private String legacyStagePurpose(String stage) {
    return switch (stage) {
      case "project_deep_dive" -> "获取真实项目、个人贡献和结果证据";
      case "architecture", "architecture_design", "agent_architecture" -> "验证架构设计和机制取舍";
      case "failure_analysis", "reliability" -> "验证失败处理和生产可靠性";
      case "requirement_clarification" -> "验证需求澄清和约束识别";
      default -> "验证 " + stage + " 阶段的能力证据";
    };
  }

  private void validateModel(
      String skillId,
      List<String> defaults,
      List<SkillStageSpec> stages,
      List<CompetencySpec> competencies,
      int schemaVersion) {
    boolean requireStageReferences = schemaVersion >= 3;
    validateStageUniqueness(skillId, stages);
    if (requireStageReferences
        && stages.stream().map(SkillStageSpec::order).distinct().count() != stages.size()) {
      throw invalid("Skill stage order 重复: " + skillId);
    }
    if (competencies.stream().map(CompetencySpec::id).map(String::toLowerCase).distinct().count()
        != competencies.size()) {
      throw invalid("Skill competency ID 重复: " + skillId);
    }
    for (String name : defaults) {
      if (competencies.stream().noneMatch(spec -> spec.name().equalsIgnoreCase(name))) {
        throw invalid("Skill 默认能力缺少结构化定义: " + skillId + "/" + name);
      }
    }
    java.util.Set<String> stageIds = stages.stream()
        .map(SkillStageSpec::id)
        .map(String::toLowerCase)
        .collect(java.util.stream.Collectors.toSet());
    for (CompetencySpec competency : competencies) {
      if (requireStageReferences && competency.stageId().isBlank()) {
        throw invalid("Skill competency 缺少 stageId: " + skillId + "/" + competency.id());
      }
      if (!competency.stageId().isBlank()
          && !stageIds.contains(competency.stageId().toLowerCase())) {
        throw invalid("Skill competency 引用了不存在的 stage: "
            + skillId + "/" + competency.id() + "/" + competency.stageId());
      }
    }
  }

  private void validateStageUniqueness(String skillId, List<SkillStageSpec> stages) {
    if (stages.stream().map(SkillStageSpec::id).map(String::toLowerCase).distinct().count()
        != stages.size()) {
      throw invalid("Skill stage ID 重复: " + skillId);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> map(Object value, String id, String field) {
    if (!(value instanceof Map<?, ?> raw)) {
      throw invalid("Skill 缺少对象字段: " + id + "/" + field);
    }
    return (Map<String, Object>) raw;
  }

  private String resourceFile(
      Map<String, Object> resources, String id, String key, String requiredSuffix) {
    String filename = required(resources.get(key), id, "resources." + key);
    if (!SAFE_RESOURCE.matcher(filename).matches() || !filename.endsWith(requiredSuffix)) {
      throw invalid("Skill resource 路径不安全: " + id + "/" + filename);
    }
    return filename;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> readRequiredYaml(String id, String filename) throws IOException {
    Object value = new Yaml().load(readRequired(id, filename));
    if (!(value instanceof Map<?, ?> raw)) {
      throw invalid("Skill YAML 格式无效: " + id + "/" + filename);
    }
    return (Map<String, Object>) raw;
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

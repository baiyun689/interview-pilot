package interview.pilot.interview.skill;

import java.util.List;

public record SkillStageSpec(String id, String purpose, int order) {
  /** 内置默认面试阶段：不写 stages 的 skill 使用。 */
  public static final List<SkillStageSpec> DEFAULT_STAGES = List.of(
      new SkillStageSpec("project_deep_dive", "验证真实项目和个人贡献", 10),
      new SkillStageSpec("technical_depth", "验证核心机制、实现边界和取舍", 20),
      new SkillStageSpec("reliability", "验证故障处理、恢复、容量和演进", 30));

  public SkillStageSpec {
    id = required(id, "stage id", 64);
    purpose = required(purpose, "stage purpose", 500);
    if (order < 0 || order > 1_000) {
      throw new IllegalArgumentException("stage order is invalid");
    }
  }

  public SkillStageSpec(String id, String purpose) {
    this(id, purpose, 0);
  }

  private static String required(String value, String name, int max) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > max) {
      throw new IllegalArgumentException(name + " is invalid");
    }
    return normalized;
  }
}

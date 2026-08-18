package interview.pilot.interview.skill;

import java.util.List;
import java.util.Set;

/**
 * 已注册知识域注册表。Skill 的 ragScopes 必须引用注册域，
 * 自由文本 scope 会在加载期被拒绝，避免检索范围静默失配。
 */
public final class KnowledgeDomains {
  private static final Set<String> REGISTERED = Set.of(
      "ai-agent", "tool-use", "rag", "mcp", "system-design",
      "algorithm-data-structures", "complexity", "edge-cases",
      "javascript", "react-vue", "browser", "css", "frontend-performance",
      "java", "concurrency", "spring", "transaction", "mysql",
      "database", "redis", "cache", "distributed", "high-availability", "mq",
      "python", "django-flask",
      "system-design-scenarios",
      "test-development", "automation", "quality", "ci");

  private KnowledgeDomains() {}

  public static void requireRegistered(String skillId, List<String> scopes) {
    for (String scope : scopes) {
      if (!REGISTERED.contains(scope)) {
        throw new IllegalArgumentException(
            "Skill " + skillId + " 引用了未注册的知识域: " + scope);
      }
    }
  }
}

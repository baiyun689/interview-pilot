package interview.pilot.interview.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.CompetencyMatcher;
import interview.pilot.interview.domain.InterviewPlan;
import interview.pilot.interview.domain.InterviewPlanItem;
import interview.pilot.interview.domain.JobRequirements;
import interview.pilot.interview.domain.PlanPriority;
import interview.pilot.interview.domain.PlanOmission;
import interview.pilot.interview.skill.CompetencySpec;
import interview.pilot.interview.skill.InterviewQuestionMode;
import interview.pilot.interview.skill.SkillSnapshot;
import interview.pilot.resume.domain.ResumeProfile;

/** Compiles model suggestions and hard interview constraints into an executable plan. */
@Component
public final class InterviewPlanCompiler {

  public InterviewPlan compile(
      InterviewPlan proposal,
      ResumeProfile resume,
      JobRequirements job,
      Difficulty difficulty,
      int totalTurnBudget,
      SkillSnapshot skill) {
    PlanProposal adapted = new PlanProposal(proposal.competencies().stream()
        .map(value -> new PlanProposal.Item(value, 50, "", "旧规划器建议"))
        .toList());
    return compile(adapted, resume, job, difficulty, totalTurnBudget, skill);
  }

  public InterviewPlan compile(
      PlanProposal proposal,
      ResumeProfile resume,
      JobRequirements job,
      Difficulty difficulty,
      int totalTurnBudget,
      SkillSnapshot skill) {
    if (skill == null) return new InterviewPlan(proposal.competencies(), totalTurnBudget);
    if (job.competencies().size() > totalTurnBudget) {
      throw new IllegalArgumentException(
          "turn budget cannot cover every required competency");
    }

    Map<String, Candidate> selected = new LinkedHashMap<>();
    Set<String> requiredKeys = new LinkedHashSet<>();
    List<String> resumeTerms = resumeTerms(resume);
    for (String required : job.competencies()) {
      requiredKeys.add(key(required));
      CompetencySpec spec = bestSpec(required, skill.competencySpecs());
      PlanProposal.Item proposed = proposal == null ? null : proposal.itemFor(required);
      add(selected, candidate(required, spec, PlanPriority.REQUIRED,
          10_000, "JD 明确要求的必考能力",
          validatedEntryPoint(proposed, resumeTerms)));
    }

    int targetCount = Math.max(requiredKeys.size(), Math.max(2, totalTurnBudget / 2));
    targetCount = Math.min(totalTurnBudget, targetCount);

    List<String> proposalOrder = proposal == null ? List.of() : proposal.competencies();
    List<Candidate> optional = new ArrayList<>();
    for (int index = 0; index < skill.competencySpecs().size(); index++) {
      CompetencySpec spec = skill.competencySpecs().get(index);
      if (selected.values().stream().anyMatch(value ->
          same(value.spec().id(), spec.id()) || related(value.name(), spec.name()))) continue;
      int resumeScore = relevance(spec, resumeTerms);
      int jobScore = relevance(spec, job.preferredSkills());
      int proposalScore = proposalPosition(spec, proposalOrder);
      PlanProposal.Item proposed = proposal == null ? null : proposal.itemFor(spec.name());
      int score = resumeScore * 100 + jobScore * 50 + proposalScore * 10
          + (proposed == null ? 0 : proposed.priorityScore())
          + (skill.competencySpecs().size() - index);
      PlanPriority priority = resumeScore > 0
          ? PlanPriority.RESUME_RELEVANT : PlanPriority.SKILL_BASELINE;
      String rationale = resumeScore > 0
          ? "候选人简历存在相关技术或项目证据"
          : jobScore > 0 ? "JD 加分项与该能力相关"
              : proposed != null ? proposed.rationale() : "Skill 基线能力补充";
      optional.add(candidate(
          spec.name(), spec, priority, score, rationale,
          validatedEntryPoint(proposed, resumeTerms)));
    }
    optional.sort(Comparator.comparingInt(Candidate::score).reversed());

    boolean hasProjectEvidence = !resume.projects().isEmpty();
    if (hasProjectEvidence && selected.size() < targetCount) {
      optional.stream()
          .filter(value -> value.spec().id().contains("project"))
          .findFirst()
          .ifPresent(value -> add(selected, value.withScore(9_000)
              .withRationale("有简历项目时优先验证个人贡献和项目真实性")
              .withResumeEntryPoint(resume.projects().getFirst().name())));
    }
    for (Candidate candidate : optional) {
      if (!hasProjectEvidence && candidate.spec().id().contains("project")) continue;
      if (selected.size() >= targetCount) break;
      add(selected, candidate);
    }

    if (selected.isEmpty()) {
      CompetencySpec fallback = skill.competencySpecs().getFirst();
      add(selected, candidate(fallback.name(), fallback,
          PlanPriority.SKILL_BASELINE, 1, "Skill 首要基线能力"));
    }

    Comparator<Candidate> ordering =
        Comparator.comparingInt((Candidate value) -> stageOrder(value.spec(), skill))
            .thenComparingInt(value -> priorityOrder(value.priority()));
    List<Candidate> ordered = selected.values().stream()
        .sorted(ordering.thenComparing(
            Comparator.comparingInt(Candidate::score).reversed()))
        .toList();
    int[] budgets = allocateBudgets(ordered.size(), totalTurnBudget);
    List<InterviewPlanItem> items = new ArrayList<>();
    for (int index = 0; index < ordered.size(); index++) {
      Candidate candidate = ordered.get(index);
      items.add(new InterviewPlanItem(
          stageFor(candidate.spec(), skill), candidate.spec().id(), candidate.name(),
          candidate.priority(), budgets[index], candidate.spec().requiredEvidence(),
          modesFor(candidate.spec(), difficulty), candidate.rationale(),
          candidate.spec().retrievalPolicy().enabled(), candidate.spec().followUpAxes(),
          Math.min(candidate.spec().followUpLimit(), Math.max(0, budgets[index] - 1)),
          candidate.resumeEntryPoint(), candidate.spec().retrievalPolicy()));
    }

    List<PlanOmission> omitted = skill.competencySpecs().stream()
        .map(CompetencySpec::name)
        .filter(name -> items.stream().noneMatch(item -> same(item.competency(), name)))
        .map(name -> new PlanOmission(name, "轮次预算优先保留 JD 必考、简历相关和高优先级能力"))
        .toList();
    return InterviewPlan.execution(items, totalTurnBudget, omitted);
  }

  private Candidate candidate(
      String name, CompetencySpec spec, PlanPriority priority, int score, String rationale) {
    return candidate(name, spec, priority, score, rationale, "");
  }

  private Candidate candidate(
      String name, CompetencySpec spec, PlanPriority priority, int score,
      String rationale, String resumeEntryPoint) {
    CompetencySpec effective = spec != null ? spec : CompetencySpec.legacy(
        "jd-" + Integer.toUnsignedString(key(name).hashCode(), 36), name);
    return new Candidate(name, effective, priority, score, rationale, resumeEntryPoint);
  }

  private void add(Map<String, Candidate> selected, Candidate candidate) {
    selected.putIfAbsent(key(candidate.name()), candidate);
  }

  private CompetencySpec bestSpec(String name, List<CompetencySpec> specs) {
    return specs.stream().filter(spec -> related(name, spec.name())).findFirst().orElse(null);
  }

  private int relevance(CompetencySpec spec, List<String> terms) {
    int score = 0;
    for (String term : terms) {
      if (related(spec.name(), term)
          || related(spec.objective(), term)
          || spec.requiredEvidence().stream().anyMatch(value -> related(value, term))) {
        score++;
      }
    }
    return score;
  }

  private int proposalPosition(CompetencySpec spec, List<String> proposal) {
    for (int index = 0; index < proposal.size(); index++) {
      if (related(spec.name(), proposal.get(index))) return proposal.size() - index;
    }
    return 0;
  }

  private String validatedEntryPoint(PlanProposal.Item proposed, List<String> resumeTerms) {
    if (proposed == null || proposed.resumeEntryPoint().isBlank()) return "";
    return resumeTerms.stream()
        .filter(term -> related(proposed.resumeEntryPoint(), term))
        .findFirst()
        .orElse("");
  }

  private List<String> resumeTerms(ResumeProfile resume) {
    List<String> terms = new ArrayList<>(resume.technicalSkills());
    for (ResumeProfile.ProjectEvidence project : resume.projects()) {
      terms.add(project.name());
      terms.add(project.description());
      terms.addAll(project.technologies());
    }
    return List.copyOf(terms);
  }

  private int[] allocateBudgets(int count, int total) {
    int[] budgets = new int[count];
    for (int index = 0; index < count; index++) budgets[index] = 1;
    for (int remaining = total - count, index = 0; remaining > 0; remaining--, index++) {
      budgets[index % count]++;
    }
    return budgets;
  }

  private String stageFor(CompetencySpec spec, SkillSnapshot skill) {
    if (!spec.stageId().isBlank()) return spec.stageId();
    String id = key(spec.id());
    if (id.contains("project")) return stage(skill, "project", 0);
    if (id.contains("reliab") || id.contains("observ") || id.contains("distributed")) {
      return stage(skill, "reliab", skill.stages().size() - 1);
    }
    return stage(skill, "architect", Math.min(1, skill.stages().size() - 1));
  }

  private String stage(SkillSnapshot skill, String hint, int fallbackIndex) {
    return skill.stages().stream()
        .filter(stage -> key(stage.id()).contains(hint))
        .map(stage -> stage.id())
        .findFirst()
        .orElse(skill.stages().get(Math.max(0, fallbackIndex)).id());
  }

  private List<InterviewQuestionMode> modesFor(CompetencySpec spec, Difficulty difficulty) {
    if (difficulty == Difficulty.EASY) {
      List<InterviewQuestionMode> easy = spec.questionModes().stream()
          .filter(mode -> mode == InterviewQuestionMode.PROJECT
              || mode == InterviewQuestionMode.CONCEPT
              || mode == InterviewQuestionMode.MECHANISM)
          .toList();
      if (!easy.isEmpty()) return easy;
    }
    return spec.questionModes();
  }

  private int priorityOrder(PlanPriority priority) {
    return switch (priority) {
      case REQUIRED -> 0;
      case RESUME_RELEVANT -> 1;
      case SKILL_BASELINE -> 2;
    };
  }

  private int stageOrder(CompetencySpec spec, SkillSnapshot skill) {
    String stageId = stageFor(spec, skill);
    return skill.stages().stream()
        .filter(stage -> stage.id().equalsIgnoreCase(stageId))
        .mapToInt(interview.pilot.interview.skill.SkillStageSpec::order)
        .findFirst()
        .orElse(Integer.MAX_VALUE);
  }

  private boolean related(String left, String right) {
    return CompetencyMatcher.related(left, right);
  }

  private boolean same(String left, String right) {
    return CompetencyMatcher.same(left, right);
  }

  private String key(String value) {
    return CompetencyMatcher.key(value);
  }

  private record Candidate(
      String name, CompetencySpec spec, PlanPriority priority, int score, String rationale,
      String resumeEntryPoint) {
    Candidate withScore(int value) {
      return new Candidate(name, spec, priority, value, rationale, resumeEntryPoint);
    }

    Candidate withRationale(String value) {
      return new Candidate(name, spec, priority, score, value, resumeEntryPoint);
    }

    Candidate withResumeEntryPoint(String value) {
      return new Candidate(name, spec, priority, score, rationale, value);
    }
  }
}

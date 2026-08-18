package interview.pilot.interview.strategy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import interview.pilot.interview.domain.AnswerEvaluation;
import interview.pilot.interview.domain.InterviewPlan;
import interview.pilot.interview.domain.InterviewPlanItem;
import interview.pilot.interview.domain.PlanPriority;

/**
 * 本场面试的证据进度快照，按能力聚合已观察、缺失和冲突状态。
 * 不可变：每轮由旧快照派生新快照；可由历史轮次重建（重放/审计），也可由本轮评估增量应用。
 */
public record InterviewProgress(
    Map<String, CompetencyProgress> byCompetencyId,
    String lastCompetencyId) {

  public InterviewProgress {
    byCompetencyId = Collections.unmodifiableMap(new LinkedHashMap<>(byCompetencyId));
    lastCompetencyId = lastCompetencyId == null ? "" : lastCompetencyId.trim();
  }

  /** 从历史已完成轮次重建进度；轮次按 turnNo 升序应用。 */
  public static InterviewProgress from(InterviewPlan plan, List<CompletedTurn> turns) {
    InterviewProgress progress = empty(plan);
    List<CompletedTurn> sorted = turns.stream()
        .sorted(Comparator.comparingInt(CompletedTurn::turnNo))
        .toList();
    for (CompletedTurn turn : sorted) {
      progress = progress.apply(TurnAssessment.of(turn.evaluation(), turn.item(), turn.turnNo()),
          turn.item());
    }
    return progress;
  }

  /** 应用本轮评估，返回新的不可变快照。计划外的能力条目被忽略。 */
  public InterviewProgress apply(TurnAssessment assessment, InterviewPlanItem item) {
    CompetencyProgress current = byCompetencyId.get(item.competencyId());
    if (current == null) return this;
    boolean continuing = item.competencyId().equals(lastCompetencyId);
    int followUps = continuing ? current.followUpCount() + 1 : 1;
    List<ObservedEvidence> observed = mergeObserved(current.observedEvidence(), assessment.newEvidence());
    List<String> redFlags = mergeRedFlags(current.redFlags(), assessment.redFlags());
    // 结构化评估一旦出现即以结构化为准；旧格式轮次才沿用 legacy 覆盖口径。
    boolean legacy = assessment.legacyTurn()
        ? (current.legacyCoverage() || assessment.legacyCoverage())
        : false;
    CompetencyProgress updated = CompetencyProgress.create(
        current.competencyId(), current.competency(), current.stageId(),
        current.requiredEvidence(), observed, redFlags, followUps,
        current.followUpLimit(), current.turnCount() + 1, legacy);
    Map<String, CompetencyProgress> next = new LinkedHashMap<>(byCompetencyId);
    next.put(item.competencyId(), updated);
    return new InterviewProgress(next, item.competencyId());
  }

  public CompetencyProgress progressOf(String competencyId) {
    return byCompetencyId.get(competencyId);
  }

  /** 阶段内所有必选能力均已充分。 */
  public boolean requiredSufficient(String stageId, InterviewPlan plan) {
    List<InterviewPlanItem> required = stageItems(stageId, plan).stream()
        .filter(item -> item.priority() == PlanPriority.REQUIRED)
        .toList();
    return !required.isEmpty() && required.stream().allMatch(item -> {
      CompetencyProgress progress = byCompetencyId.get(item.competencyId());
      return progress != null && progress.status() == CompetencyStatus.SUFFICIENT;
    });
  }

  /** 阶段轮次预算已耗尽（阶段内各能力累计轮数达到预算之和）。 */
  public boolean stageBudgetExhausted(String stageId, InterviewPlan plan) {
    List<InterviewPlanItem> items = stageItems(stageId, plan);
    int budget = items.stream().mapToInt(InterviewPlanItem::turnBudget).sum();
    int used = items.stream()
        .mapToInt(item -> byCompetencyId.get(item.competencyId()).turnCount())
        .sum();
    return used >= budget;
  }

  /** 阶段内所有能力均已充分或耗尽，不再产生有效追问。 */
  public boolean stageFullySettled(String stageId, InterviewPlan plan) {
    List<InterviewPlanItem> items = stageItems(stageId, plan);
    return !items.isEmpty() && items.stream().allMatch(item -> {
      CompetencyProgress progress = byCompetencyId.get(item.competencyId());
      return progress != null && progress.status() != CompetencyStatus.OPEN;
    });
  }

  /** 阶段完成：必选能力全充分，或阶段预算耗尽，或无法再产生有效证据。 */
  public boolean stageComplete(String stageId, InterviewPlan plan) {
    return requiredSufficient(stageId, plan)
        || stageBudgetExhausted(stageId, plan)
        || stageFullySettled(stageId, plan);
  }

  /** 未充分能力及其证据缺口的可读摘要，用于 FINISH 指令与报告。 */
  public String unfinishedSummary(InterviewPlan plan) {
    StringBuilder summary = new StringBuilder();
    for (InterviewPlanItem item : plan.items()) {
      CompetencyProgress progress = byCompetencyId.get(item.competencyId());
      if (progress == null || progress.status() == CompetencyStatus.SUFFICIENT) continue;
      List<String> missing = progress.missingEvidence().isEmpty()
          ? List.of("未覆盖") : progress.missingEvidence();
      if (!summary.isEmpty()) summary.append("；");
      summary.append(item.competency()).append("（缺：").append(String.join("、", missing)).append("）");
    }
    return summary.toString();
  }

  private List<InterviewPlanItem> stageItems(String stageId, InterviewPlan plan) {
    return plan.items().stream()
        .filter(item -> item.stageId().equals(stageId))
        .toList();
  }

  /** 已完成的候选轮次，用于重建进度。 */
  public record CompletedTurn(InterviewPlanItem item, AnswerEvaluation evaluation, int turnNo) {
    public CompletedTurn {
      if (item == null) throw new IllegalArgumentException("item must not be null");
      if (evaluation == null) throw new IllegalArgumentException("evaluation must not be null");
      if (turnNo < 0) throw new IllegalArgumentException("turnNo must not be negative");
    }
  }

  private static InterviewProgress empty(InterviewPlan plan) {
    Map<String, CompetencyProgress> initial = new LinkedHashMap<>();
    for (InterviewPlanItem item : plan.items()) {
      initial.put(item.competencyId(), CompetencyProgress.create(
          item.competencyId(), item.competency(), item.stageId(),
          item.evidenceTargets(), List.of(), List.of(), 0, item.followUpLimit(), 0, false));
    }
    return new InterviewProgress(initial, "");
  }

  private static List<ObservedEvidence> mergeObserved(
      List<ObservedEvidence> existing, List<ObservedEvidence> additions) {
    Map<String, ObservedEvidence> merged = new LinkedHashMap<>();
    existing.forEach(evidence -> merged.put(evidence.evidenceId(), evidence));
    additions.forEach(evidence -> merged.put(evidence.evidenceId(), evidence));
    return List.copyOf(merged.values());
  }

  private static List<String> mergeRedFlags(List<String> existing, List<String> additions) {
    Set<String> merged = new LinkedHashSet<>(existing);
    merged.addAll(additions);
    return List.copyOf(new ArrayList<>(merged));
  }
}

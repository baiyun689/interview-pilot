package interview.pilot.resume.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** 单次简历分析调用的复合产物:面试事实档案 + 候选人自评的评测报告。 */
public record ResumeAnalysisResult(
    @NotNull @Valid ResumeProfile profile,
    @NotNull @Valid ResumeEvaluation evaluation) {}

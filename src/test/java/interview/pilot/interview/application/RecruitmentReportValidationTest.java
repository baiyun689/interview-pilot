package interview.pilot.interview.application;

import static org.assertj.core.api.Assertions.*;
import interview.pilot.interview.domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RecruitmentReportValidationTest {
  @Test void acceptsOnlyActuallyAssessedRecruitmentPhasesAndPreservesPracticeContract() {
    var brief=new InterviewBriefSnapshot(JobSourceType.CUSTOM,null,null,"Java","JD",null,null,Difficulty.MEDIUM,
        InterviewSize.STANDARD,"test","model",null,2);
    var input=new FixedReportInput(brief,List.of(new FixedReportInput.TurnEvidence(1,"FUNDAMENTALS","MAIN","题目","回答",null,List.of(),null)),
        true,2,List.of(new FixedReportInput.UnassessedQuestion("项目题目","NOT_ANSWERED")));
    var report=new FixedInterviewReport(70,Map.of(InterviewPhase.FUNDAMENTALS,70),List.of("已回答基础题"),List.of("需补充项目评估"),List.of(),List.of(),"仅评估基础题",Map.of());
    assertThatCode(()->FixedInterviewReportHandler.validate(report,Set.of(),Map.of(),input)).doesNotThrowAnyException();
    var invented=new FixedInterviewReport(70,Map.of(InterviewPhase.FUNDAMENTALS,70,InterviewPhase.PROJECT_EXPERIENCE,0),
        report.strengths(),report.improvements(),List.of(),List.of(),report.summary(),Map.of());
    assertThatThrownBy(()->FixedInterviewReportHandler.validate(invented,Set.of(),Map.of(),input)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->FixedInterviewReportHandler.validate(report,Set.of(),Map.of(),new FixedReportInput(brief,input.completedTurns())))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

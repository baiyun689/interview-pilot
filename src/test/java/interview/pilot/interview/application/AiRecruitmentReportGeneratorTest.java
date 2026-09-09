package interview.pilot.interview.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import interview.pilot.ai.StructuredOutputInvoker;
import interview.pilot.interview.domain.*;
import interview.pilot.interview.rag.RagContextSnapshot;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import tools.jackson.databind.ObjectMapper;

class AiRecruitmentReportGeneratorTest {
  @Test void derivesAvailabilityFromFrozenEvidenceInsteadOfModelInventedMetadata() {
    var output=mock(StructuredOutputInvoker.class);
    var modelReport=new FixedInterviewReport(80,Map.of(InterviewPhase.FUNDAMENTALS,80),List.of("优点"),List.of("待补充"),List.of(),List.of(),"总结",
        Map.of(InterviewPhase.FUNDAMENTALS,"NOT_REQUESTED"));
    when(output.invoke(any(),eq(FixedInterviewReport.class))).thenReturn(modelReport);
    var generator=new AiFixedReportGenerator(output,new PromptJsonEncoder(new ObjectMapper()),new org.springframework.core.io.ClassPathResource("prompts/fixed-interview-report-system.st"),new ByteArrayResource("{{CONTEXT_JSON}}".getBytes()));
    var brief=new InterviewBriefSnapshot(JobSourceType.CUSTOM,null,null,"Java","JD",null,null,Difficulty.MEDIUM,InterviewSize.STANDARD,"test","model",null,2);
    var input=new FixedReportInput(brief,List.of(new FixedReportInput.TurnEvidence(1,"FUNDAMENTALS","MAIN","题目","回答",RagContextSnapshot.notConfigured(),List.of(),null)),true,2,List.of());
    var result=generator.generate("test","model",input);
    var request=org.mockito.ArgumentCaptor.forClass(interview.pilot.ai.model.AiRequest.class);
    verify(output).invoke(request.capture(),eq(FixedInterviewReport.class));
    assertThat(request.getValue().systemPrompt()).contains("\"phaseScores\": {\"FUNDAMENTALS\": 0到100整数}")
        .doesNotContain("{{PHASE_SCORE_SCHEMA}}", "\"phaseScores\": {\"SELF_INTRODUCTION\"");
    assertThat(result.ragAvailability()).containsExactlyEntriesOf(Map.of(InterviewPhase.FUNDAMENTALS,"NOT_CONFIGURED"));
    assertThatCode(()->FixedInterviewReportHandler.validate(result,Set.of(),Map.of(InterviewPhase.FUNDAMENTALS,"NOT_CONFIGURED"),input)).doesNotThrowAnyException();
  }
}

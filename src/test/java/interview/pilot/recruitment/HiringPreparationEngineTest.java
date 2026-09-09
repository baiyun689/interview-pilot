package interview.pilot.recruitment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static interview.pilot.recruitment.application.AssessmentModels.*;
import static interview.pilot.recruitment.application.CampaignModels.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import interview.pilot.ai.StructuredOutputInvoker;
import interview.pilot.interview.application.QuestionRagRetriever;
import interview.pilot.interview.domain.*;
import interview.pilot.interview.rag.*;
import interview.pilot.knowledge.retrieval.ValidatedKnowledgeScope;
import interview.pilot.recruitment.application.HiringPreparationEngine;
import jakarta.validation.Validation;
import tools.jackson.databind.ObjectMapper;

class HiringPreparationEngineTest {
  @Test void frozenOrganizationEvidenceReachesModelAndOnlyKnownSourcesAreAccepted() {
    var ai=mock(StructuredOutputInvoker.class);var retrieval=mock(QuestionRagRetriever.class);var json=new ObjectMapper();
    var doc=UUID.randomUUID();var scope=new ValidatedKnowledgeScope(null,List.of(UUID.randomUUID()),List.of(new ValidatedKnowledgeScope.DocumentRevision(doc,3)),"v1",7L);
    var evidence=new RagContextSnapshot(RagStatus.RETRIEVED,"缓存","v1",List.of(new RagContextSnapshot.Chunk("point-1",doc,"企业缓存规范",3,0,null,"",null,0.9,"企业采用先更新数据库后失效缓存")),null);
    when(retrieval.retrieve(eq(scope),any())).thenReturn(evidence);
    var common=new CommonQuestion("common",InterviewPhase.FUNDAMENTALS,"解释缓存一致性",List.of(new RubricItem("base","一致性","说明更新顺序")));
    var definition=new Definition(Difficulty.MEDIUM,InterviewMode.TEXT,30,List.of(new Stage(InterviewPhase.FUNDAMENTALS,2,0)),List.of(common),"test");
    var input=new PreparationInput("test","model","v1",definition,List.of(new Fragment("j1","缓存一致性")),List.of(new Fragment("r1","项目使用缓存")),scope);
    var questions=List.of(new PreparedQuestion(common.id(),common.phase(),true,common.question(),common.rubric(),List.of(),List.of("point-1")),
        new PreparedQuestion("personal",InterviewPhase.FUNDAMENTALS,false,"如何更新项目缓存",List.of(new RubricItem("p1","实现","描述项目更新顺序")),List.of("r1"),List.of("point-1")));
    when(ai.invoke(any(),eq(HiringPreparationEngine.GeneratedDeck.class))).thenReturn(new HiringPreparationEngine.GeneratedDeck(List.of(
        new HiringPreparationEngine.GeneratedQuestion(InterviewPhase.FUNDAMENTALS,"如何更新项目缓存",List.of(new HiringPreparationEngine.GeneratedRubric("实现","描述项目更新顺序")),List.of("r1"),List.of("point-1")))));
    try(var factory=Validation.buildDefaultValidatorFactory()) {
      var engine=new HiringPreparationEngine(ai,json,factory.getValidator(),retrieval);
      var result=(PreparedDeck)engine.process(json.writeValueAsString(input));
      assertThat(result.knowledgeEvidence()).containsExactly(evidence);
      assertThat(result.questions().getFirst().question()).isEqualTo(common.question());
      assertThat(result.questions().getFirst().rubric()).isEqualTo(common.rubric());
      verify(ai).invoke(argThat(request->request.toString().contains("企业采用先更新数据库后失效缓存")),eq(HiringPreparationEngine.GeneratedDeck.class));
      assertThatThrownBy(()->engine.validate(input,new PreparedDeck(questions))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("知识引用");
      assertThatThrownBy(()->engine.validate(input,new PreparedDeck(Arrays.asList((PreparedQuestion)null)))).isInstanceOf(IllegalArgumentException.class);
    }
  }
}

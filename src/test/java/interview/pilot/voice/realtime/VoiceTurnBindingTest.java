package interview.pilot.voice.realtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.interview.api.SubmitAnswerRequest;
import interview.pilot.interview.application.*;
import interview.pilot.interview.domain.*;
import interview.pilot.interview.infrastructure.*;
import interview.pilot.voice.realtime.orchestrator.VoiceTurnOrchestrator;
import interview.pilot.voice.realtime.tts.RealtimeTtsClient;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class VoiceTurnBindingTest {
  @Test void providerGenerationIsCheckedAgainWhenTheActorActuallyAppends() throws Exception {
    var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var drained=new java.util.concurrent.CountDownLatch(1);
    var generation=new java.util.concurrent.atomic.AtomicInteger(1);var subtitles=new java.util.concurrent.atomic.AtomicInteger();
    var voice=new interview.pilot.voice.realtime.session.RealtimeVoiceSession(mock(org.springframework.web.socket.WebSocketSession.class),null,UUID.randomUUID(),
        new interview.pilot.voice.realtime.config.RealtimeVoiceProperties.Conversation(false,10,0,null,null,null),text->{});
    try {
      voice.runOpening(()->{entered.countDown();try{release.await(5,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException ex){Thread.currentThread().interrupt();}});
      assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      voice.appendFinalSegment("已经通过入口检查的旧转写",()->generation.get()==1,subtitles::incrementAndGet);
      generation.incrementAndGet();release.countDown();voice.runOpening(drained::countDown);
      assertThat(drained.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThat(voice.mergePreview()).isEmpty();assertThat(subtitles.get()).isZero();
    } finally {voice.close();}
  }
  @Test void previousAsrConnectionCannotAppendLateResultsAfterNextQuestion() throws Exception {
    var socket=mock(org.springframework.web.socket.WebSocketSession.class);var id=UUID.randomUUID();
    var user=new CurrentUser(1L,UUID.randomUUID(),"voice@example.test","Voice");
    when(socket.getId()).thenReturn("voice-test");when(socket.isOpen()).thenReturn(true);
    when(socket.getAttributes()).thenReturn(Map.of("realtime.currentUser",user,"realtime.sessionId",id));
    var asr=mock(interview.pilot.voice.realtime.asr.StreamingAsrClient.class);
    var orchestrator=mock(VoiceTurnOrchestrator.class);
    var first=new VoiceTurnOrchestrator.VoiceTurnOutcome("",false,SessionStatus.INTERVIEWING,1,1,"第一题",new byte[0],false,false);
    var next=new VoiceTurnOrchestrator.VoiceTurnOutcome("回答",false,SessionStatus.INTERVIEWING,1,2,"第二题",new byte[0],false,false);
    when(orchestrator.speakCurrentTurn(user,id)).thenReturn(first);
    when(orchestrator.bind(user,id,1)).thenReturn(new VoiceTurnOrchestrator.TurnBinding(1,1,true));
    when(orchestrator.bind(user,id,2)).thenReturn(new VoiceTurnOrchestrator.TurnBinding(2,2,true));
    when(orchestrator.submitAnswer(eq(user),eq(id),anyString(),any())).thenReturn(next);
    var oldCallback=new java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<String>>();
    doAnswer(call->{oldCallback.set(call.getArgument(1));return null;}).when(asr).start(anyString(),any(),any(),any());
    var properties=new interview.pilot.voice.realtime.config.RealtimeVoiceProperties(true,null,null,null,
        new interview.pilot.voice.realtime.config.RealtimeVoiceProperties.Conversation(false,10,0,null,null,null));
    var handler=new interview.pilot.voice.realtime.handler.RealtimeVoiceWebSocketHandler(properties,asr,orchestrator,new tools.jackson.databind.ObjectMapper(),new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    var messages=new java.util.concurrent.CopyOnWriteArrayList<String>();
    doAnswer(call->{messages.add(((org.springframework.web.socket.TextMessage)call.getArgument(0)).getPayload());return null;}).when(socket).sendMessage(any());
    try {
      handler.afterConnectionEstablished(socket);
      org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(()->messages.stream().anyMatch(m->m.contains("第一题")));
      handler.handleMessage(socket,new org.springframework.web.socket.TextMessage("{\"type\":\"control\",\"action\":\"submit\",\"text\":\"回答\",\"turnNo\":1}"));
      org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(()->messages.stream().anyMatch(m->m.contains("第二题")));
      oldCallback.get().accept("不应附着到第二题的旧转写");
      assertThat(messages).noneMatch(m->m.contains("不应附着"));verify(asr).restart(eq("voice-test"),any(),any(),any());
      handler.handleMessage(socket,new org.springframework.web.socket.TextMessage("{\"type\":\"control\",\"action\":\"submit\",\"text\":\"延迟重发的旧回答\",\"turnNo\":1}"));
      assertThat(messages).anyMatch(m->m.contains("ANSWER_VERSION_CONFLICT"));
      verify(orchestrator,times(1)).submitAnswer(eq(user),eq(id),anyString(),any());
    } finally {handler.afterConnectionClosed(socket,org.springframework.web.socket.CloseStatus.NORMAL);}
  }
  @Test void answerUsesTheQuestionVersionDeliveredToThisConnection() {
    var answers=mock(FixedAnswerService.class);var sessions=mock(InterviewSessionRepository.class);
    var orchestrator=new VoiceTurnOrchestrator(answers,sessions,mock(InterviewTurnRepository.class),mock(RealtimeTtsClient.class));
    var user=new CurrentUser(1L,UUID.randomUUID(),"voice@example.test","Voice");var id=UUID.randomUUID();
    var result=new FixedAnswerResult(id,UUID.randomUUID(),2,SessionStatus.EVALUATING,null,false);
    when(answers.claim(any(),any(),any())).thenReturn(new FixedAnswerClaim(2,false,result,null));when(answers.process(any())).thenReturn(result);
    orchestrator.submitAnswer(user,id,"回答",new VoiceTurnOrchestrator.TurnBinding(2,8));
    var request=ArgumentCaptor.forClass(SubmitAnswerRequest.class);verify(answers).claim(eq(user),eq(id),request.capture());
    assertThat(request.getValue().expectedTurnNo()).isEqualTo(2);assertThat(request.getValue().sessionVersion()).isEqualTo(8);
    verifyNoInteractions(sessions);
  }
  @Test void queuedDuplicateSubmitAndLateAsrCannotAdvanceTheNextQuestion() throws Exception {
    var started=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var drained=new java.util.concurrent.CountDownLatch(1);
    var calls=new java.util.concurrent.CopyOnWriteArrayList<String>();
    var voice=new interview.pilot.voice.realtime.session.RealtimeVoiceSession(mock(org.springframework.web.socket.WebSocketSession.class),null,UUID.randomUUID(),
      new interview.pilot.voice.realtime.config.RealtimeVoiceProperties.Conversation(false,10,0,null,null,null),text->{calls.add(text);started.countDown();try{release.await(5,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException ex){Thread.currentThread().interrupt();}});
    try {voice.requestSubmit("原回答");assertThat(started.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      voice.requestSubmit("重复点击");voice.appendFinalSegment("旧识别结果");release.countDown();voice.runOpening(drained::countDown);
      assertThat(drained.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThat(calls).containsExactly("原回答");assertThat(voice.mergePreview()).isEmpty();
    } finally {voice.close();}
  }
}

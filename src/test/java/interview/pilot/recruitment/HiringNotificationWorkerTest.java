package interview.pilot.recruitment;

import static org.mockito.Mockito.*;
import interview.pilot.recruitment.application.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class HiringNotificationWorkerTest {
  @Test void smtpTimeoutIsUnknownAndExplicitConnectFailureCanRetry() {
    var service=mock(HiringNotificationService.class);var mail=mock(HiringMailSender.class);
    var delivery=new HiringNotificationService.Delivery(1L,"lease","candidate@example.test","邀请","安排");
    when(mail.enabled()).thenReturn(true);when(service.claim(1L,true)).thenReturn(delivery);
    var worker=new HiringNotificationWorker(service,mail,new SimpleMeterRegistry());
    doThrow(new org.springframework.mail.MailSendException("timeout",new java.net.SocketTimeoutException())).when(mail).send(delivery);
    worker.deliver(1L);verify(service).finish(eq(delivery),eq("UNKNOWN"),anyString());
    doThrow(new org.springframework.mail.MailSendException("connection refused",new java.net.ConnectException())).when(mail).send(delivery);
    worker.deliver(1L);verify(service).finish(eq(delivery),eq("FAILED"),anyString());worker.close();
  }
}

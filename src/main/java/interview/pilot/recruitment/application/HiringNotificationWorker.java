package interview.pilot.recruitment.application;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class HiringNotificationWorker {
  private final HiringNotificationService service;
  private final HiringMailSender mail;
  private final MeterRegistry metrics;
  private final java.util.concurrent.atomic.AtomicBoolean running=new java.util.concurrent.atomic.AtomicBoolean();
  private final java.util.concurrent.ExecutorService executor=java.util.concurrent.Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"hiring-mail-worker");t.setDaemon(true);return t;});
  public HiringNotificationWorker(HiringNotificationService service,HiringMailSender mail,MeterRegistry metrics) {this.service=service;this.mail=mail;this.metrics=metrics;}
  @Scheduled(fixedDelayString="${app.hiring.notification-scan-ms:10000}")
  public void scan() {
    if(!running.compareAndSet(false,true)) return;
    executor.execute(()->{try {for(Long id:service.due()) {
      try {deliver(id);} catch(RuntimeException ex) {metrics.counter("hiring.notification.worker.failure").increment();}
    }} finally {running.set(false);}});
  }
  @jakarta.annotation.PreDestroy public void close() {executor.shutdownNow();}
  public void deliver(Long id) {
    var delivery=service.claim(id,mail.enabled());if(delivery==null) return;
    String result="ACCEPTED_BY_PROVIDER",error=null;
    try {mail.send(delivery);}
    catch(RuntimeException ex) {
      // Only failures before any SMTP handoff are safe to automatically retry.
      boolean beforeConnect=false;
      for(Throwable cause=ex;cause!=null;cause=cause.getCause()) {
        if(cause instanceof java.net.ConnectException || cause instanceof java.net.UnknownHostException
            || cause instanceof org.springframework.mail.MailAuthenticationException) beforeConnect=true;
      }
      result=beforeConnect?"FAILED":"UNKNOWN";
      error=beforeConnect?"邮件服务连接或认证失败":"服务商接收情况未知，请核实后手动重发";
    }
    service.finish(delivery,result,error);metrics.counter("hiring.notification.delivery","result",result).increment();
  }
}

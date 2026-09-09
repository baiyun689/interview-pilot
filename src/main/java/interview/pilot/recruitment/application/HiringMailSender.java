package interview.pilot.recruitment.application;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class HiringMailSender {
  private final ObjectProvider<JavaMailSender> sender;
  private final boolean enabled;
  private final String from;
  private final String baseUrl;
  public HiringMailSender(ObjectProvider<JavaMailSender> sender,
      @Value("${app.hiring.mail-enabled:false}") boolean enabled,
      @Value("${app.hiring.mail-from:interview-pilot@example.test}") String from,
      @Value("${app.hiring.public-base-url:http://localhost:5173}") String baseUrl) {
    this.sender=sender;this.enabled=enabled;this.from=from;this.baseUrl=baseUrl;
  }
  public boolean enabled() {return enabled && sender.getIfAvailable()!=null;}
  public void send(HiringNotificationService.Delivery delivery) {
    var mail=new SimpleMailMessage();mail.setFrom(from);mail.setTo(delivery.email());
    mail.setSubject(delivery.title());mail.setText(delivery.message()+"\n\n"+baseUrl+"/candidate/invitations");
    sender.getObject().send(mail);
  }
}

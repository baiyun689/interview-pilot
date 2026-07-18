package interview.pilot.async.messaging;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.async.rabbit")
public class AsyncRabbitProperties {
  private Duration republishAfter = Duration.ofSeconds(30);
  private Duration confirmTimeout = Duration.ofSeconds(5);

  public Duration getRepublishAfter() {
    return republishAfter;
  }

  public void setRepublishAfter(Duration republishAfter) {
    this.republishAfter = republishAfter;
  }

  public Duration getConfirmTimeout() {
    return confirmTimeout;
  }

  public void setConfirmTimeout(Duration confirmTimeout) {
    this.confirmTimeout = confirmTimeout;
  }
}

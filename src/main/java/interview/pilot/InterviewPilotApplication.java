package interview.pilot;

import interview.pilot.auth.jwt.JwtProperties;
import interview.pilot.interview.voice.VoiceInterviewProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.ai.vectorstore.qdrant.autoconfigure.QdrantVectorStoreAutoConfiguration;

@EnableScheduling
@EnableConfigurationProperties({JwtProperties.class, VoiceInterviewProperties.class})
@SpringBootApplication(exclude = QdrantVectorStoreAutoConfiguration.class)
public class InterviewPilotApplication {
  public static void main(String[] args) {
    SpringApplication.run(InterviewPilotApplication.class, args);
  }
}

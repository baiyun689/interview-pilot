package interview.pilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.ai.vectorstore.qdrant.autoconfigure.QdrantVectorStoreAutoConfiguration;

@EnableScheduling
@SpringBootApplication(exclude = QdrantVectorStoreAutoConfiguration.class)
public class InterviewPilotApplication {
  public static void main(String[] args) {
    SpringApplication.run(InterviewPilotApplication.class, args);
  }
}

package interview.pilot.interview.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
public class InterviewAnswerExecutorConfig {
  @Bean(name = "interviewAnswerExecutor")
  ThreadPoolTaskExecutor interviewAnswerExecutor() {
    var executor = new ThreadPoolTaskExecutor();
    executor.setThreadNamePrefix("interview-answer-");
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(8);
    executor.setQueueCapacity(100);
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(10);
    return executor;
  }
}

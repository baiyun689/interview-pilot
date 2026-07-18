package interview.pilot.ai.model;

import java.time.Duration;

public record AiResponse(String content, String model, Duration latency) {}

package interview.pilot.ai.provider;

public record AiProviderDescriptor(
    String id,
    String displayName,
    String model,
    boolean enabled,
    boolean defaultProvider) {}

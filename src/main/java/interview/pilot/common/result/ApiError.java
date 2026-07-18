package interview.pilot.common.result;

public record ApiError(String code, String message, String traceId) {}

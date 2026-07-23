package interview.pilot.auth.application;

import java.util.UUID;

public record CurrentUser(Long databaseId, UUID userId, String email, String displayName) {}

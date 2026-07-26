package interview.pilot.auth.jwt;

import interview.pilot.auth.application.CurrentUser;

public record TokenPair(
    String accessToken,
    RefreshToken refreshToken,
    CurrentUser user) {
}

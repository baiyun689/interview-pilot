package interview.pilot.auth.jwt;

import java.util.Optional;
import interview.pilot.auth.application.CurrentUser;

public interface JwtTokenService {
  String issueAccessToken(CurrentUser user);
  RefreshToken issueRefreshToken(CurrentUser user);
  Optional<CurrentUser> verifyAccessToken(String token);
  TokenPair refresh(String rawRefreshToken);
  void revoke(String rawToken);
}

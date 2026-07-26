package interview.pilot.auth.application;

import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import interview.pilot.auth.api.RegisterRequest;
import interview.pilot.auth.domain.UserStatus;
import interview.pilot.auth.infrastructure.UserAccountEntity;
import interview.pilot.auth.infrastructure.UserAccountRepository;
import interview.pilot.common.exception.BusinessException;

@Service
public class AuthService {
  private final UserAccountRepository accounts;
  private final PasswordEncoder passwordEncoder;

  public AuthService(UserAccountRepository accounts, PasswordEncoder passwordEncoder) {
    this.accounts = accounts;
    this.passwordEncoder = passwordEncoder;
  }

  public CurrentUser authenticate(String email, String password) {
    String normalized = normalizeEmail(email);
    UserAccountEntity account = accounts.findByEmail(normalized)
        .orElseThrow(() -> authFailed());
    if (account.getStatus() != UserStatus.ACTIVE) {
      throw authFailed();
    }
    if (!passwordEncoder.matches(password, account.getPasswordHash())) {
      throw authFailed();
    }
    return new CurrentUser(
        account.getId(), account.getUserId(),
        account.getEmail(), account.getDisplayName());
  }

  @Transactional
  public CurrentUser register(RegisterRequest request) {
    String email = normalizeEmail(request.email());
    if (accounts.findByEmail(email).isPresent()) {
      throw emailAlreadyExists();
    }
    try {
      UserAccountEntity account = accounts.save(UserAccountEntity.register(
          email, passwordEncoder.encode(request.password()), request.displayName().trim()));
      return new CurrentUser(
          account.getId(), account.getUserId(),
          account.getEmail(), account.getDisplayName());
    } catch (DataIntegrityViolationException exception) {
      throw emailAlreadyExists();
    }
  }

  public String normalizeEmail(String email) {
    return email.trim().toLowerCase(Locale.ROOT);
  }

  private BusinessException authFailed() {
    return new BusinessException(
        "AUTHENTICATION_FAILED", "Email or password is incorrect", HttpStatus.UNAUTHORIZED);
  }

  private BusinessException emailAlreadyExists() {
    return new BusinessException(
        "EMAIL_ALREADY_EXISTS", "An account with this email already exists", HttpStatus.CONFLICT);
  }
}

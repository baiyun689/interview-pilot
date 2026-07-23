package interview.pilot.auth.application;

import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import interview.pilot.auth.api.RegisterRequest;
import interview.pilot.auth.domain.UserStatus;
import interview.pilot.auth.infrastructure.UserAccountEntity;
import interview.pilot.auth.infrastructure.UserAccountRepository;
import interview.pilot.common.exception.BusinessException;

@Service
public class AuthService implements UserDetailsService {
  private final UserAccountRepository accounts;
  private final PasswordEncoder passwordEncoder;

  public AuthService(UserAccountRepository accounts, PasswordEncoder passwordEncoder) {
    this.accounts = accounts;
    this.passwordEncoder = passwordEncoder;
  }

  @Transactional
  public AuthenticatedUser register(RegisterRequest request) {
    String email = normalizeEmail(request.email());
    if (accounts.findByEmail(email).isPresent()) {
      throw emailAlreadyExists();
    }
    try {
      UserAccountEntity account = accounts.save(UserAccountEntity.register(
          email, passwordEncoder.encode(request.password()), request.displayName().trim()));
      return authenticated(account);
    } catch (DataIntegrityViolationException exception) {
      throw emailAlreadyExists();
    }
  }

  @Override
  @Transactional(readOnly = true)
  public AuthenticatedUser loadUserByUsername(String email) {
    return accounts.findByEmail(normalizeEmail(email))
        .map(this::authenticated)
        .orElseThrow(() -> new UsernameNotFoundException("Account not found"));
  }

  public String normalizeEmail(String email) {
    return email.trim().toLowerCase(Locale.ROOT);
  }

  private AuthenticatedUser authenticated(UserAccountEntity account) {
    return new AuthenticatedUser(
        account.getId(),
        account.getUserId(),
        account.getEmail(),
        account.getDisplayName(),
        account.getPasswordHash(),
        account.getStatus() == UserStatus.ACTIVE);
  }

  private static BusinessException emailAlreadyExists() {
    return new BusinessException(
        "EMAIL_ALREADY_EXISTS", "An account with this email already exists", HttpStatus.CONFLICT);
  }
}

package interview.pilot.async.policy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import interview.pilot.async.domain.AsyncTaskType;

/**
 * Routes manual-retry behavior by {@link AsyncTaskType}. Spring collects every
 * {@link RetryableTaskPolicy} bean, so a new task type needs exactly one new policy class
 * and nothing else — the registry maps it here with no further wiring.
 *
 * <p>A type with no registered policy is a programming error, not a routing default: the
 * current code's implicit "else" branch silently treated unknown types as interview
 * evaluations, which would have mis-reset any future type. {@link #forType} fails loudly
 * instead; a test also enforces that every {@link AsyncTaskType} constant is covered.
 */
@Component
public class RetryableTaskPolicyRegistry {
  private final Map<AsyncTaskType, RetryableTaskPolicy> byType;

  public RetryableTaskPolicyRegistry(List<RetryableTaskPolicy> policies) {
    Map<AsyncTaskType, RetryableTaskPolicy> index = new HashMap<>();
    for (RetryableTaskPolicy policy : policies) {
      RetryableTaskPolicy duplicate = index.put(policy.type(), policy);
      if (duplicate != null) {
        throw new IllegalStateException(
            "Duplicate RetryableTaskPolicy for " + policy.type());
      }
    }
    this.byType = Map.copyOf(index);
  }

  public RetryableTaskPolicy forType(AsyncTaskType type) {
    java.util.Objects.requireNonNull(type, "type");
    RetryableTaskPolicy policy = byType.get(type);
    if (policy == null) {
      throw new IllegalStateException(
          "No RetryableTaskPolicy registered for " + type
              + "; add a policy component for the new task type");
    }
    return policy;
  }

  public Set<AsyncTaskType> registeredTypes() {
    return byType.keySet();
  }
}

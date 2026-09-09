package interview.pilot.recruitment.infrastructure;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;

/** Shared persistence mechanics; transactions and authorization belong to the application module. */
@Repository
public class HiringStore {
  private final EntityManager em;

  public HiringStore(EntityManagerFactory factory) {
    this.em = SharedEntityManagerCreator.createSharedEntityManager(factory);
  }

  public <T> T add(T entity) { em.persist(entity); return entity; }

  public <T> Optional<T> find(Class<T> type, Long id, boolean lock) {
    T entity = em.find(type, id);
    if (lock && entity != null) em.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
    return Optional.ofNullable(entity);
  }

  public <T> List<T> list(Class<T> type, String jpql, int offset, int limit, Object... parameters) {
    return query(type, jpql, parameters).setFirstResult(offset).setMaxResults(limit).getResultList();
  }

  public <T> Optional<T> one(Class<T> type, String jpql, Object... parameters) {
    return list(type, jpql, 0, 1, parameters).stream().findFirst();
  }

  public void remove(Object entity) { em.remove(entity); }
  public void flush() { em.flush(); }

  private <T> TypedQuery<T> query(Class<T> type, String jpql, Object... parameters) {
    var query = em.createQuery(jpql, type);
    for (int i = 0; i < parameters.length; i++) query.setParameter(i + 1, parameters[i]);
    return query;
  }
}

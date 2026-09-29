package com.taskflow.calendar.domain.user;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    // 추후 확장: provider별 조회
    Optional<User> findByEmailAndProvider(String email, Provider provider);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    List<User> findTop100ByProviderAndExpiresAtLessThanEqualOrderByExpiresAtAsc(Provider provider, Instant expiresAt);
}

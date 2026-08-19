package com.taskflow.calendar.domain.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;
import jakarta.persistence.LockModeType;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);

    // 추후 확장: provider별 조회
    Optional<User> findByEmailAndProvider(String email, Provider provider);

    @Query("select count(u) > 0 from User u where u.id = :id and (" +
            "u.provider = com.taskflow.calendar.domain.user.Provider.GOOGLE or " +
            "(u.provider = com.taskflow.calendar.domain.user.Provider.DEMO and u.expiresAt > :now))")
    boolean isSessionActive(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    List<User> findTop100ByProviderAndExpiresAtLessThanEqualOrderByExpiresAtAsc(
            Provider provider, LocalDateTime expiresAt);
}

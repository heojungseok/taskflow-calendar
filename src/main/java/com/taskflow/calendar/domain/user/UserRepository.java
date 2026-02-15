package com.taskflow.calendar.domain.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);

    // 추후 확장: provider별 조회
    Optional<User> findByEmailAndProvider(String email, Provider provider);
}

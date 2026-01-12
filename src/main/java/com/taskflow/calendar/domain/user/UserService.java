package com.taskflow.calendar.domain.user;

import com.taskflow.calendar.domain.user.dto.CreateUserRequest;
import com.taskflow.calendar.domain.user.dto.UserResponse;
import com.taskflow.calendar.domain.user.exception.DuplicateEmailException;
import com.taskflow.calendar.domain.user.exception.UserNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        // 1. 이메일 중복 체크
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateEmailException(request.getEmail());
        }

        // 2. User 엔티티 생성
        User user = User.of(request.getEmail(), request.getName());

        // 3. 저장
        User savedUser = userRepository.save(user);

        // 4. DTO 변환 후 반환
        return UserResponse.from(savedUser);
    }

    public List<UserResponse> getAllUsers() {
        return userRepository.findAll()
                .stream()
                .map(UserResponse::from)
                .collect(Collectors.toList());
    }

    public UserResponse getUserById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));

        return UserResponse.from(user);
    }
}
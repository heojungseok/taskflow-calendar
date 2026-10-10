package com.taskflow.identity.user;

import com.taskflow.common.ErrorCode;
import com.taskflow.common.exception.BusinessException;
import com.taskflow.identity.user.exception.UserNotFoundException;
import java.util.function.LongSupplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공개 데모 사용자의 생성 한도와 변경 횟수 한도.
 *
 * <p>사용자 행을 잠근 채로 세어 동시 요청에도 한도를 넘지 않는다. 무엇을 몇 개까지 만들 수 있는지는 호출하는 컨텍스트가 정한다. 데모 사용자가 아니면
 * 아무것도 세지 않는다.
 */
@Service
@RequiredArgsConstructor
public class DemoUsageService {

    private static final int MUTATION_LIMIT = 500;

    private final UserRepository userRepository;

    /**
     * 리소스를 하나 더 만들기 전에 호출한다. ownedCount는 사용자 행을 잠근 뒤에 읽는다.
     *
     * @return 데모 사용자 여부
     */
    @Transactional
    public boolean beforeCreate(Long userId, LongSupplier ownedCount, int limit) {
        User user = lockDemoUser(userId);
        if (user != null && ownedCount.getAsLong() >= limit) {
            throw new BusinessException(ErrorCode.DEMO_RESOURCE_LIMIT);
        }
        increment(user);
        return user != null;
    }

    @Transactional
    public void beforeMutation(Long userId) {
        increment(lockDemoUser(userId));
    }

    private User lockDemoUser(Long userId) {
        User user = userRepository.findByIdForUpdate(userId).orElseThrow(() -> new UserNotFoundException(userId));
        return user.getProvider() == Provider.DEMO ? user : null;
    }

    private void increment(User user) {
        if (user == null) {
            return;
        }
        if (user.getDemoMutationCount() >= MUTATION_LIMIT) {
            throw new BusinessException(ErrorCode.DEMO_MUTATION_LIMIT);
        }
        user.incrementDemoMutationCount();
    }
}

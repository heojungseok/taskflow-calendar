package com.taskflow.identity.user.exception;

import com.taskflow.common.ErrorCode;
import com.taskflow.common.exception.ResourceNotFoundException;

public class UserNotFoundException extends ResourceNotFoundException {
    public UserNotFoundException(Long userId) {
        super(ErrorCode.USER_NOT_FOUND, "User not found with id: " + userId);
    }

    public UserNotFoundException(String email) {
        super(ErrorCode.USER_NOT_FOUND, "User not found with email: " + email);
    }
}

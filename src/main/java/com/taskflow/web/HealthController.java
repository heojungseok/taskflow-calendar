package com.taskflow.web;

import com.taskflow.common.ApiResponse;
import com.taskflow.common.exception.ValidationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    @GetMapping
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> data = new HashMap<>();
        data.put("status", "ok");
        data.put("timestamp", LocalDateTime.now());
        return ApiResponse.success(data);
    }

    @GetMapping("/error-test")
    public ApiResponse<Void> errorTest(@RequestParam(required = false) String type) {
        if ("validation".equals(type)) {
            throw new ValidationException("This is a test validation error");
        }
        throw new RuntimeException("This is a test unexpected error");
    }
}
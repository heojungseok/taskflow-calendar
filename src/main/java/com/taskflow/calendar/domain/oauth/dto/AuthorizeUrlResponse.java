package com.taskflow.calendar.domain.oauth.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AuthorizeUrlResponse {
    private String authorizeUrl;
}
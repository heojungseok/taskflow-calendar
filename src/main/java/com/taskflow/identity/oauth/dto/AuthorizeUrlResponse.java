package com.taskflow.identity.oauth.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AuthorizeUrlResponse {
    private String authorizeUrl;
}

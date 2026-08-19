package com.taskflow.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiRequestSizeFilterTest {

    @Test
    void rejectsOversizedApiMutationBeforeController() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/projects");
        request.setContent(new byte[64 * 1024 + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ApiRequestSizeFilter().doFilter(request, response, new MockFilterChain());

        assertEquals(413, response.getStatus());
    }
}

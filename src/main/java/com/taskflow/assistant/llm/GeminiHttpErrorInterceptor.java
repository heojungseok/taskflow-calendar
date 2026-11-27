package com.taskflow.assistant.llm;

import java.io.IOException;
import okhttp3.Interceptor;
import okhttp3.Response;
import okhttp3.ResponseBody;

final class GeminiHttpErrorInterceptor implements Interceptor {

    @Override
    public Response intercept(Chain chain) throws IOException {
        Response response = chain.proceed(chain.request());
        if (response.code() < 400) {
            return response;
        }
        try (response) {
            ResponseBody body = response.body();
            throw new GeminiHttpException(response.code(), response.header("Retry-After"), body.string());
        }
    }
}

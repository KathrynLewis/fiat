/*
 * Copyright 2016 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.fiat.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.ExponentialBackOff;

/** This package is placed in fiat-core in order to be shared by fiat-web and fiat-shared. */
@Configuration
public class RetrofitConfig {

  @Value("${ok-http-client.retries.max-elapsed-backoff-ms:5000}")
  @Setter
  private long maxElapsedBackoffMs;

  @Bean
  @Primary
  ObjectMapper objectMapper() {
    return new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(SerializationFeature.INDENT_OUTPUT, true)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);
  }

  @Bean
  RetryingInterceptor retryingInterceptor() {
    return new RetryingInterceptor(maxElapsedBackoffMs);
  }

  @Slf4j
  @AllArgsConstructor
  private static class RetryingInterceptor implements Interceptor {

    // http://restcookbook.com/HTTP%20Methods/idempotency/
    private static final List<String> NON_RETRYABLE_METHODS = ImmutableList.of("POST", "PATCH");

    private long maxElapsedBackoffMs;

    @Override
    public Response intercept(Chain chain) throws IOException {
      ExponentialBackOff backoff = new ExponentialBackOff();
      backoff.setMaxElapsedTime(maxElapsedBackoffMs);
      BackOffExecution backOffExec = backoff.start();

      Response response = null;
      long waitTime = 0;
      while (waitTime != BackOffExecution.STOP) {
        Request request = chain.request();
        response = chain.proceed(request);
        if (response.isSuccessful()
            || NON_RETRYABLE_METHODS.contains(request.method())
            || response.code() == 404) {
          return response;
        }

        try {
          waitTime = backOffExec.nextBackOff();
          if (waitTime != BackOffExecution.STOP) {
            if (response.body() != null) {
              response.body().close();
            }
            log.warn(
                "Request for "
                    + request.url().toString()
                    + " failed. Backing off for "
                    + waitTime
                    + "ms");
            Thread.sleep(waitTime);
          }
        } catch (Throwable ignored) {
          break;
        }
      }
      return response;
    }
  }
}

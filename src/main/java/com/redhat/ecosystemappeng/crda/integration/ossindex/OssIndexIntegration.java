/*
 * Copyright 2023 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.redhat.ecosystemappeng.crda.integration.ossindex;

import java.util.Base64;
import java.util.Objects;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.builder.AggregationStrategies;
import org.apache.camel.builder.endpoint.EndpointRouteBuilder;
import org.apache.camel.http.base.HttpOperationFailedException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.redhat.ecosystemappeng.crda.integration.Constants;
import com.redhat.ecosystemappeng.crda.integration.VulnerabilityProvider;
import com.redhat.ecosystemappeng.crda.model.ProviderStatus;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
public class OssIndexIntegration extends EndpointRouteBuilder {

  @ConfigProperty(name = "api.ossindex.timeout", defaultValue = "1000s")
  String timeout;

  @Inject VulnerabilityProvider vulnerabilityProvider;

  @Override
  public void configure() {
    // fmt:off
    from(direct("ossIndexScan"))
        .routeId("ossIndexScan")
        .enrich(direct("ossIndexRequest"), AggregationStrategies.bean(OssIndexAggregationStrategy.class, "aggregate"));

    from(direct("ossIndexRequest"))
        .routeId("ossIndexRequest")
        .circuitBreaker()
          .faultToleranceConfiguration()
            .timeoutEnabled(true)
            .timeoutDuration(timeout)
          .end()
          .to(direct("ossSplitReq"))
            .onFallback()
              .process(this::processResponseError);

    from(direct("ossSplitReq"))
        .routeId("ossSplitReq")
        .transform(method(OssIndexAggregationStrategy.class, "split"))
        .split(body(), AggregationStrategies.bean(OssIndexAggregationStrategy.class, "aggregateSplit"))
        .parallelProcessing()
        .transform().method(OssIndexRequestBuilder.class, "buildRequest")
          .process(this::processComponentRequest)
          .to(vertxHttp("{{api.ossindex.host}}"))
          .transform(method(OssIndexRequestBuilder.class, "responseToIssues"));
    // fmt:on
  }

  private void processComponentRequest(Exchange exchange) {
    Message message = exchange.getMessage();
    message.removeHeader(Exchange.HTTP_PATH);
    message.removeHeader(Exchange.HTTP_QUERY);
    message.removeHeader(Exchange.HTTP_URI);
    message.removeHeader("Accept-Encoding");
    message.setHeader(Exchange.CONTENT_TYPE, MediaType.APPLICATION_JSON);
    message.setHeader(Exchange.HTTP_METHOD, HttpMethod.POST);

    String username = message.getHeader(Constants.OSS_INDEX_USER_HEADER, String.class);
    String token = message.getHeader(Constants.OSS_INDEX_TOKEN_HEADER, String.class);
    if (Objects.nonNull(username) && Objects.nonNull(token)) {
      message.setHeader(Exchange.HTTP_PATH, Constants.OSS_INDEX_AUTH_COMPONENT_API_PATH);
      StringBuilder auth = new StringBuilder().append(username).append(":").append(token);
      message.setHeader(
          "Authorization",
          "Basic " + Base64.getEncoder().encodeToString(auth.toString().getBytes()));
    } else {
      message.setHeader(Exchange.HTTP_PATH, Constants.OSS_INDEX_COMPONENT_API_PATH);
    }
  }

  // TODO: Refactor
  private void processResponseError(Exchange exchange) {
    ProviderStatus.Builder builder =
        ProviderStatus.builder().ok(false).provider(Constants.OSS_INDEX_PROVIDER);
    Exception exception = (Exception) exchange.getProperty(Exchange.EXCEPTION_CAUGHT);
    Throwable cause = exception.getCause();

    if (cause != null) {
      if (cause instanceof HttpOperationFailedException) {
        HttpOperationFailedException httpException = (HttpOperationFailedException) cause;
        builder.message(httpException.getMessage()).status(httpException.getStatusCode());

      } else {
        builder
            .message(cause.getMessage())
            .status(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
      }
    } else {
      builder
          .message(exception.getMessage())
          .status(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
    }
    exchange.getMessage().setBody(builder.build());
  }
}

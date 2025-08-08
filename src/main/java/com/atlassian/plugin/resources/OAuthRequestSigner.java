package com.atlassian.plugin.resources;

import com.atlassian.oauth.Request;
import com.atlassian.oauth.ServiceProvider;
import com.atlassian.oauth.consumer.ConsumerService;
import com.atlassian.sal.api.ApplicationProperties;
import com.atlassian.sal.api.UrlMode;
import net.oauth.OAuth;
import net.oauth.OAuthMessage;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class OAuthRequestSigner {
    private static final String OAUTH_TOKEN = "oauth_token";
    private static final String XOAUTH_REQUESTOR_ID = "xoauth_requestor_id";

    private final ConsumerService consumerService;
    private final ServiceProvider serviceProvider;

    public OAuthRequestSigner(ConsumerService consumerService,
                              ApplicationProperties applicationProperties) {
        this.consumerService = consumerService;
        String baseUrl = applicationProperties.getBaseUrl(UrlMode.CANONICAL);
        this.serviceProvider = new ServiceProvider(URI.create(baseUrl + "/plugins/servlet/oauth/request-token"),
                URI.create(baseUrl + "/plugins/servlet/oauth/access-token"),
                URI.create(baseUrl + "/plugins/servlet/oauth/authorize"));
    }

    public String createAuthorizationHeader(String url, String impersonatedUsername,
                                            Request.HttpMethod httpMethod) throws IOException {
        final Request oAuthRequest = createUnsignedRequest(url, impersonatedUsername, httpMethod);
        final Request signedRequest = consumerService.sign(oAuthRequest, serviceProvider);
        return asOAuthMessage(signedRequest).getAuthorizationHeader(null);
    }

    private OAuthMessage asOAuthMessage(final Request request) {
        return new OAuthMessage(request.getMethod().name(), request.getUri().toString(),
                Collections.unmodifiableList(StreamSupport.stream(request.getParameters().spliterator(), false)
                                .map(param -> new OAuth.Parameter(param.getName(), param.getValue()))
                                .collect(Collectors.toList())));
    }

    private Request createUnsignedRequest(String url, String impersonatedUsername, Request.HttpMethod httpMethod) {
        return new Request(httpMethod, URI.create(url), List.of(new Request.Parameter(OAUTH_TOKEN, ""),
                new Request.Parameter(XOAUTH_REQUESTOR_ID, impersonatedUsername)));
    }
}

package com.atlassian.plugin.resources;

import com.atlassian.beehive.ClusterLock;
import com.atlassian.beehive.ClusterLockService;
import com.atlassian.oauth.Consumer;
import com.atlassian.oauth.Request;
import com.atlassian.oauth.ServiceProvider;
import com.atlassian.oauth.consumer.ConsumerService;
import com.atlassian.oauth.serviceprovider.ServiceProviderConsumerStore;
import com.atlassian.sal.api.ApplicationProperties;
import com.atlassian.sal.api.UrlMode;
import jakarta.annotation.PostConstruct;
import net.oauth.OAuth;
import net.oauth.OAuthMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Component
public class OAuthRequestHelper {
    private static final Logger logger = LoggerFactory.getLogger(OAuthRequestHelper.class);

    private static final String OAUTH_TOKEN = "oauth_token";
    private static final String XOAUTH_REQUESTOR_ID = "xoauth_requestor_id";

    // This prefix needs to be added to the consumer key to keep the OAuth1 consumer
    // without trying to convert it to an applink.
    private static final String OAUTH_CONSUMER_KEY_PREFIX = "__KEEP_AS_OAUTH1__";

    private static final String UNIQUE_PLUGIN_KEY = "com.company.plugin-key";
    private static final String OAUTH_CONSUMER_KEY = OAUTH_CONSUMER_KEY_PREFIX + UNIQUE_PLUGIN_KEY;

    private final ConsumerService consumerService;
    private final ServiceProviderConsumerStore serviceProviderConsumerStore;
    private final ServiceProvider serviceProvider;
    private final ClusterLockService clusterLockService;

    @Autowired
    public OAuthRequestHelper(ConsumerService consumerService,
                              ServiceProviderConsumerStore serviceProviderConsumerStore,
                              ClusterLockService clusterLockService,
                              ApplicationProperties applicationProperties) {
        this.consumerService = consumerService;
        this.serviceProviderConsumerStore = serviceProviderConsumerStore;
        this.clusterLockService = clusterLockService;
        String baseUrl = applicationProperties.getBaseUrl(UrlMode.CANONICAL);
        this.serviceProvider = new ServiceProvider(URI.create(baseUrl + "/plugins/servlet/oauth/request-token"),
                URI.create(baseUrl + "/plugins/servlet/oauth/access-token"),
                URI.create(baseUrl + "/plugins/servlet/oauth/authorize"));
    }

    @PostConstruct
    public void init() {
        initOAuthConsumers();
    }

    private void initOAuthConsumers() {
        if (consumerService.getConsumerByKey(OAUTH_CONSUMER_KEY) == null
                || serviceProviderConsumerStore.get(OAUTH_CONSUMER_KEY) == null) {
            ClusterLock lock = clusterLockService.getLockForName(OAUTH_CONSUMER_KEY);
            lock.lock();

            try {
                if (consumerService.getConsumerByKey(OAUTH_CONSUMER_KEY) == null
                        || serviceProviderConsumerStore.get(OAUTH_CONSUMER_KEY) == null) {

                    KeyPair keyPair;
                    try {
                        keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
                    } catch (NoSuchAlgorithmException e) {
                        throw new RuntimeException(e);
                    }

                    Consumer consumer = Consumer.key(OAUTH_CONSUMER_KEY)
                            .name(OAUTH_CONSUMER_KEY)
                            .signatureMethod(Consumer.SignatureMethod.RSA_SHA1)
                            .description("Some description")
                            .publicKey(keyPair.getPublic())
                            .twoLOImpersonationAllowed(true)
                            .twoLOAllowed(true)
                            .threeLOAllowed(true)
                            .build();

                    consumerService.add(consumer.getName(), consumer, keyPair.getPrivate());
                    logger.info("Created and registered OAuth consumer: {}", consumer.getKey());

                    serviceProviderConsumerStore.put(consumer);
                    logger.info("Created and registered OAuth service provider consumer: {}", consumer.getKey());
                }
            } finally {
                lock.unlock();
            }
        } else {
            logger.debug("OAuth consumer and service provider already exist for key: {}", OAUTH_CONSUMER_KEY);
        }
    }

    public String createAuthorizationHeader(String url, String impersonatedUsername, Request.HttpMethod httpMethod) throws IOException {
        final Request oAuthRequest = createUnsignedRequest(url, impersonatedUsername, httpMethod);
        final Request signedRequest;
        try {
            signedRequest = consumerService.sign(oAuthRequest, OAUTH_CONSUMER_KEY, serviceProvider);
        } catch (Exception e) {
            logger.error("Error signing OAuth request", e);
            throw new IOException("Failed to sign OAuth request", e);
        }
        return asOAuthMessage(signedRequest).getAuthorizationHeader(null);
    }

    public String addUsernameToUrl(String url, String username) {
        String delimiter = url.contains("?") ? "&" : "?";
        String encodedUser = URLEncoder.encode(username, StandardCharsets.UTF_8);
        return url + delimiter + XOAUTH_REQUESTOR_ID + "=" + encodedUser;
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

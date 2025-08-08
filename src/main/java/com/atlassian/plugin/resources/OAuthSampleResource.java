package com.atlassian.plugin.resources;

import com.atlassian.oauth.Consumer;
import com.atlassian.oauth.consumer.ConsumerService;
import com.atlassian.oauth.serviceprovider.ServiceProviderConsumerStore;
import com.atlassian.plugins.rest.api.security.annotation.UnrestrictedAccess;
import com.atlassian.sal.api.ApplicationProperties;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Objects;

import static com.atlassian.oauth.Request.HttpMethod;
import static java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME;

@Path("/oauthExamples")
public class OAuthSampleResource {
    private static final String XOAUTH_REQUESTOR_ID = "xoauth_requestor_id";
    private static final String CONFLUENCE_CONTENT_URL = "http://localhost:1990/confluence/rest/api/content";
    private static final String JIRA_PROJECT_URL = "http://localhost:2990/jira/rest/api/2/project";
    private static final String JIRA_ISSUE_URL = "http://localhost:2990/jira/rest/api/2/issue";

    private final ConsumerService consumerService;
    private final ServiceProviderConsumerStore serviceProviderConsumerStore;
    private final OAuthRequestSigner oAuthRequestSigner;

    @Inject
    public OAuthSampleResource(ConsumerService consumerService,
                               ServiceProviderConsumerStore serviceProviderConsumerStore,
                               ApplicationProperties applicationProperties) {
        this.consumerService = consumerService;
        this.serviceProviderConsumerStore = serviceProviderConsumerStore;
        this.oAuthRequestSigner = new OAuthRequestSigner(consumerService, applicationProperties);
        createServiceProviderConsumer();
    }

    // http://localhost:1990/confluence/rest/plugin-tutorial/latest/oauthExamples/hello
    // http://localhost:2990/jira/rest/plugin-tutorial/latest/oauthExamples/hello
    @GET
    @Path("hello")
    @UnrestrictedAccess
    public String hello() {
        return "HELLO";
    }

    // http://localhost:1990/confluence/rest/plugin-tutorial/latest/oauthExamples/createConfluencePage?impersonatedUsername=admin&space=ds
    @GET
    @Path("createConfluencePage")
    @UnrestrictedAccess
    public String createConfluencePage(@QueryParam("impersonatedUsername") String impersonatedUsername,
                                       @QueryParam("space") String projectSpace) throws Exception {
        Objects.requireNonNull(impersonatedUsername, "Impersonated username must not be null");
        Objects.requireNonNull(projectSpace, "Project space must not be null");

        String jsonBody = String.format("""
        {
            "type": "page",
            "title": "New Page Title %s",
            "space": { "key": "%s" },
            "body": {
                "storage": {
                    "value": "<p>This is the page content.</p>",
                    "representation": "storage"
                }
            }
        }
        """, ISO_LOCAL_DATE_TIME.format(LocalDateTime.now()), projectSpace);

        return executeOAuthRequest(impersonatedUsername, CONFLUENCE_CONTENT_URL, jsonBody);
    }

    // http://localhost:2990/jira/rest/plugin-tutorial/latest/oauthExamples/createJiraProject?impersonatedUsername=admin&projectKey=PROJ
    @GET
    @Path("createJiraProject")
    @UnrestrictedAccess
    public String createJiraProject(@QueryParam("impersonatedUsername") String impersonatedUsername,
                                    @QueryParam("projectKey") String projectKey) throws Exception {
        Objects.requireNonNull(impersonatedUsername, "Impersonated username must not be null");
        Objects.requireNonNull(projectKey, "Project key must not be null");

        String jsonBody = String.format("""
        {
            "key": "%s",
            "name": "Demo Project %s",
            "projectTypeKey": "business",
            "lead": "admin",
            "assigneeType": "PROJECT_LEAD"
        }
        """, projectKey, ISO_LOCAL_DATE_TIME.format(LocalDateTime.now()));

        return executeOAuthRequest(impersonatedUsername, JIRA_PROJECT_URL, jsonBody);
    }

    // http://localhost:2990/jira/rest/plugin-tutorial/latest/oauthExamples/createJiraIssue?impersonatedUsername=admin&projectKey=PROJ
    @GET
    @Path("createJiraIssue")
    @UnrestrictedAccess
    public String createJiraIssue(@QueryParam("impersonatedUsername") String impersonatedUsername,
                                  @QueryParam("projectKey") String projectKey) throws Exception {
        Objects.requireNonNull(impersonatedUsername, "Impersonated username must not be null");
        Objects.requireNonNull(projectKey, "Project key must not be null");

        String jsonBody = String.format("""
        {
            "fields": {
                "project": {
                    "key": "%s"
                },
                "summary": "Example issue from REST API %s",
                "description": "This issue was created using the Jira REST API on Data Center.",
                "issuetype": {
                    "name": "Task"
                }
            }
        }
        """, projectKey, ISO_LOCAL_DATE_TIME.format(LocalDateTime.now()));

        return executeOAuthRequest(impersonatedUsername, JIRA_ISSUE_URL, jsonBody);
    }

    public String executeOAuthRequest(String impersonatedUsername, String url, String jsonBody) throws Exception {
        String fullUrl = url + "?" + XOAUTH_REQUESTOR_ID + "=" + impersonatedUsername;
        String authorizationHeader = oAuthRequestSigner.createAuthorizationHeader(url, impersonatedUsername, HttpMethod.POST);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", authorizationHeader)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 400) {
            throw new RuntimeException("HTTP error " + response.statusCode() + ": " + response.body());
        }

        return response.body();
    }

    /**
     * DC products (Jira, Confluence) provides host consumer.
     * In the future the host service provider consumer could be created on the product side, so the plugins can use it.
     */
    public void createServiceProviderConsumer() {
        Consumer hostConsumer = consumerService.getConsumer();
        if (hostConsumer == null) {
            throw new IllegalStateException("Consumer is null");
        }
        if (serviceProviderConsumerStore.get(hostConsumer.getKey()) == null) {
            Consumer hostServiceProviderConsumer = new Consumer.InstanceBuilder(hostConsumer.getKey())
                    .signatureMethod(hostConsumer.getSignatureMethod())
                    .name(hostConsumer.getName())
                    .description(hostConsumer.getDescription())
                    .publicKey(hostConsumer.getPublicKey())
                    .callback(hostConsumer.getCallback())
                    .twoLOAllowed(true)
                    .twoLOImpersonationAllowed(true)
                    .build();
            serviceProviderConsumerStore.put(hostServiceProviderConsumer);
        }
    }
}

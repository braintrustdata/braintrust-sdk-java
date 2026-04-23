package dev.braintrust.api;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.braintrust.config.BraintrustConfig;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class BraintrustOpenApiClientTest {

    @RegisterExtension
    static WireMockExtension wireMock =
            WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    // HTTPS WireMock server using a keytool-generated cert with localhost SAN,
    // set up in @BeforeAll so we can generate the keystore first.
    static WireMockServer wireMockHttps;
    static Path httpsKeystoreFile;
    static SSLContext customSslContext; // trusts the generated cert

    @BeforeAll
    static void setUpHttps() throws Exception {
        httpsKeystoreFile = Files.createTempFile("test-keystore", ".jks");
        Files.delete(httpsKeystoreFile); // keytool requires the file not exist yet
        var password = "changeit";

        // Generate a self-signed cert with localhost SAN
        var keytool =
                new ProcessBuilder(
                                "keytool", "-genkeypair",
                                "-alias", "test",
                                "-keyalg", "RSA",
                                "-keystore", httpsKeystoreFile.toString(),
                                "-storepass", password,
                                "-keypass", password,
                                "-dname", "CN=localhost",
                                "-ext", "san=dns:localhost,ip:127.0.0.1",
                                "-validity", "1")
                        .redirectErrorStream(true)
                        .start();
        int exitCode = keytool.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException(
                    "keytool failed: " + new String(keytool.getInputStream().readAllBytes()));
        }

        // Build client SSLContext that trusts the generated cert
        var keyStore = KeyStore.getInstance("JKS");
        try (var fis = new FileInputStream(httpsKeystoreFile.toFile())) {
            keyStore.load(fis, password.toCharArray());
        }
        var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        var trustStore = KeyStore.getInstance("JKS");
        trustStore.load(null, null);
        trustStore.setCertificateEntry("test-server", keyStore.getCertificate("test"));
        tmf.init(trustStore);
        customSslContext = SSLContext.getInstance("TLS");
        customSslContext.init(null, tmf.getTrustManagers(), new SecureRandom());

        // Start WireMock HTTPS with our keystore
        wireMockHttps =
                new WireMockServer(
                        wireMockConfig()
                                .dynamicPort()
                                .dynamicHttpsPort()
                                .keystorePath(httpsKeystoreFile.toString())
                                .keystorePassword(password)
                                .keyManagerPassword(password));
        wireMockHttps.start();
    }

    @AfterAll
    static void tearDownHttps() throws Exception {
        if (wireMockHttps != null) wireMockHttps.stop();
        if (httpsKeystoreFile != null) Files.deleteIfExists(httpsKeystoreFile);
    }

    private BraintrustConfig config;
    private BraintrustOpenApiClient client;

    @BeforeEach
    void beforeEach() {
        wireMock.resetAll();
        config =
                BraintrustConfig.builder()
                        .apiKey("test-key")
                        .apiUrl("http://localhost:" + wireMock.getPort())
                        .appUrl("http://app.example.com")
                        .defaultProjectName("test-project")
                        .build();
        client = BraintrustOpenApiClient.of(config);
    }

    // ── Construction ──────────────────────────────────────────────────────────

    @Test
    void of_setsBaseUri() {
        assertEquals("http://localhost:" + wireMock.getPort(), client.getBaseUri());
    }

    @Test
    void of_addsAuthorizationHeader() throws Exception {
        wireMock.stubFor(
                get(urlPathEqualTo("/v1/project"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{\"objects\":[]}")));

        // Trigger any request so the interceptor fires
        new dev.braintrust.openapi.api.ProjectsApi(client)
                .getProject(null, null, null, null, "any", null);

        wireMock.verify(
                getRequestedFor(urlPathEqualTo("/v1/project"))
                        .withHeader("Authorization", equalTo("Bearer test-key")));
    }

    @Test
    void of_usesSslContextFromConfig() throws Exception {
        // WireMock's HTTPS port uses a self-signed cert that the default SSLContext
        // will reject. A trust-all SSLContext passed through config should allow it.
        // This proves the custom SSLContext is actually wired into the HttpClient
        // rather than silently ignored.
        String httpsUrl = "https://localhost:" + wireMockHttps.httpsPort();

        wireMockHttps.stubFor(
                post(urlEqualTo("/api/apikey/login"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"org_info\":[{\"id\":\"org-1\",\"name\":\"Test"
                                                        + " Org\"}]}")));

        // Client with the custom SSLContext that trusts our cert → should succeed
        var customClient =
                BraintrustOpenApiClient.of(
                        BraintrustConfig.builder()
                                .apiKey("test-key")
                                .apiUrl(httpsUrl)
                                .sslContext(customSslContext)
                                .build());
        var response = customClient.login();
        assertEquals("Test Org", response.orgInfo().get(0).name());

        // Client with the default SSLContext → should reject our self-signed cert,
        // proving the custom context is actually wired in and not just ignored
        var defaultClient =
                BraintrustOpenApiClient.of(
                        BraintrustConfig.builder()
                                .apiKey("test-key")
                                .apiUrl(httpsUrl)
                                .sslContext(SSLContext.getDefault())
                                .build());
        assertThrows(
                Exception.class,
                defaultClient::login,
                "Default SSLContext should reject our self-signed cert");
    }

    // ── login() ───────────────────────────────────────────────────────────────

    @Test
    void login_parsesOrgInfo() {
        wireMock.stubFor(
                post(urlEqualTo("/api/apikey/login"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                                        {
                                          "org_info": [
                                            {"id": "org-1", "name": "My Org"}
                                          ]
                                        }
                                        """)));

        var response = client.login();

        assertNotNull(response);
        assertEquals(1, response.orgInfo().size());
        assertEquals("org-1", response.orgInfo().get(0).id());
        assertEquals("My Org", response.orgInfo().get(0).name());
    }

    @Test
    void login_sendsApiKeyAsToken() {
        wireMock.stubFor(
                post(urlEqualTo("/api/apikey/login"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{\"org_info\":[]}")));

        client.login();

        wireMock.verify(
                postRequestedFor(urlEqualTo("/api/apikey/login"))
                        .withRequestBody(matchingJsonPath("$.token", equalTo("test-key"))));
    }

    @Test
    void login_throwsOnNon2xx() {
        wireMock.stubFor(
                post(urlEqualTo("/api/apikey/login"))
                        .willReturn(aResponse().withStatus(401).withBody("Unauthorized")));

        assertThrows(RuntimeException.class, () -> client.login());
    }

    // ── btqlQuery() ───────────────────────────────────────────────────────────

    @Test
    void btqlQuery_parsesResponse() {
        wireMock.stubFor(
                post(urlEqualTo("/btql"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                                        {
                                          "data": [
                                            {"version": "12345"},
                                            {"version": "12346"}
                                          ]
                                        }
                                        """)));

        var result = client.btqlQuery("SELECT max(_xact_id) FROM dataset('abc')");

        assertNotNull(result);
        assertEquals(2, result.data().size());
        assertEquals("12345", result.data().get(0).get("version"));
    }

    @Test
    void btqlQuery_sendsQueryInBody() {
        wireMock.stubFor(
                post(urlEqualTo("/btql"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{\"data\":[]}")));

        client.btqlQuery("SELECT 1");

        wireMock.verify(
                postRequestedFor(urlEqualTo("/btql"))
                        .withRequestBody(matchingJsonPath("$.query", equalTo("SELECT 1"))));
    }

    @Test
    void btqlQuery_throwsOnNon2xx() {
        wireMock.stubFor(
                post(urlEqualTo("/btql"))
                        .willReturn(aResponse().withStatus(500).withBody("Server Error")));

        assertThrows(RuntimeException.class, () -> client.btqlQuery("SELECT 1"));
    }
}

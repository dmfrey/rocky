package com.example.clientragdemo.documents.adapter.in.endpoint;

import com.example.clientragdemo.TestSessions;
import com.example.clientragdemo.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = RANDOM_PORT)
@AutoConfigureTestRestTemplate
class DocumentControllerIT {

    @Autowired
    private TestRestTemplate restTemplate;

    private HttpHeaders authHeaders;

    @BeforeEach
    void registerAndLogin() {
        // BannerAckFilter gates /api/auth/register and /api/auth/login until this session has
        // acknowledged the consent banner (see SecurityConfig) - ack first and carry that
        // session's cookie through both calls.
        HttpHeaders bannerHeaders = TestSessions.acknowledgedSession(restTemplate);

        AuthRequest credentials = new AuthRequest("doc-user-" + UUID.randomUUID(), "password123");
        restTemplate.postForEntity("/api/auth/register", new HttpEntity<>(credentials, bannerHeaders), Void.class);
        ResponseEntity<Void> loginResponse = restTemplate.postForEntity(
                "/api/auth/login", new HttpEntity<>(credentials, bannerHeaders), Void.class);

        // Login doesn't always issue a new Set-Cookie - only when session-fixation protection
        // actually changes the session ID. Reuse the banner-ack cookie in that case.
        authHeaders = TestSessions.afterLogin(bannerHeaders, loginResponse);
    }

    @Test
    void uploadRefreshListDeleteFlow() {
        String filename = "notes-" + UUID.randomUUID() + ".txt";
        String content = "Podman requires a running machine on macOS before Testcontainers can connect.";

        DocumentResponse uploaded = upload(filename, content);
        assertThat(uploaded.status()).isIn("PROCESSING", "READY");
        assertThat(uploaded.contentType()).isEqualTo("TXT");

        DocumentResponse ready = awaitReady(uploaded.id());
        assertThat(ready.status()).isEqualTo("READY");
        assertThat(ready.chunkCount()).isGreaterThan(0);
        assertThat(ready.uploadedBy()).isNotBlank();

        ResponseEntity<DocumentResponse[]> listResponse = restTemplate.exchange(
                "/api/documents", HttpMethod.GET, new HttpEntity<>(authHeaders), DocumentResponse[].class);
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(List.of(listResponse.getBody())).extracting(DocumentResponse::filename).contains(filename);

        // Re-uploading the same filename refreshes the existing document rather than duplicating it.
        DocumentResponse refreshed = upload(filename, content + " Refreshed.");
        assertThat(refreshed.id()).isEqualTo(uploaded.id());
        DocumentResponse refreshedReady = awaitReady(refreshed.id());
        assertThat(refreshedReady.status()).isEqualTo("READY");

        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
                "/api/documents/" + uploaded.id(), HttpMethod.DELETE, new HttpEntity<>(authHeaders), Void.class);
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> getAfterDelete = restTemplate.exchange(
                "/api/documents/" + uploaded.id(), HttpMethod.GET, new HttpEntity<>(authHeaders), String.class);
        assertThat(getAfterDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void rejectsUnsupportedContentType() {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", namedResource("malware.exe", "not really".getBytes(StandardCharsets.UTF_8)));

        HttpHeaders headers = new HttpHeaders();
        headers.addAll(authHeaders);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> response = restTemplate.postForEntity("/api/documents", new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsUploadsOverTheConfiguredSizeLimit() {
        byte[] oversized = new byte[200 * 1024]; // test config caps max-file-size at 100KB

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", namedResource("too-big.txt", oversized));

        HttpHeaders headers = new HttpHeaders();
        headers.addAll(authHeaders);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> response = restTemplate.postForEntity("/api/documents", new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
    }

    @Test
    void requiresAuthentication() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/documents", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private DocumentResponse upload(String filename, String content) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", namedResource(filename, content.getBytes(StandardCharsets.UTF_8)));

        HttpHeaders headers = new HttpHeaders();
        headers.addAll(authHeaders);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<DocumentResponse> response = restTemplate.postForEntity("/api/documents", new HttpEntity<>(body, headers), DocumentResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return response.getBody();
    }

    private DocumentResponse awaitReady(Long id) {
        return await().atMost(Duration.ofSeconds(60)).until(
                () -> restTemplate.exchange("/api/documents/" + id, HttpMethod.GET, new HttpEntity<>(authHeaders), DocumentResponse.class).getBody(),
                document -> !"PROCESSING".equals(document.status()));
    }

    private static ByteArrayResource namedResource(String filename, byte[] content) {
        return new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    private record AuthRequest(String username, String password) {}

    private record DocumentResponse(
            Long id,
            String filename,
            String contentType,
            String status,
            String errorMessage,
            Integer chunkCount,
            String uploadedBy,
            String createdAt,
            String updatedAt
    ) {}
}

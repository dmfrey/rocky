package com.example.clientragdemo.chat.adapter.in.endpoint;

import com.example.clientragdemo.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import com.example.clientragdemo.TestSessions;
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
class ChatControllerIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void sendMessageStreamsGroundedAnswerWithSourcesAndAutoTitle() {
        HttpHeaders authHeaders = registerAndLogin("chat-user-" + UUID.randomUUID());

        String filename = "facts-" + UUID.randomUUID() + ".txt";
        uploadAndAwaitReady(authHeaders, filename, "The secret code word for this test is BANANAFISH42.");

        Long sessionId = createChatSession(authHeaders);

        HttpHeaders sseHeaders = new HttpHeaders();
        sseHeaders.addAll(authHeaders);
        sseHeaders.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));

        HttpEntity<SendMessageRequest> request = new HttpEntity<>(new SendMessageRequest("What is the secret code word?"), sseHeaders);
        ResponseEntity<String> streamResponse = restTemplate.exchange(
                "/api/chats/" + sessionId + "/messages", HttpMethod.POST, request, String.class);

        assertThat(streamResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = streamResponse.getBody();
        assertThat(body).isNotBlank();
        assertThat(body).contains("event:token");
        assertThat(body).contains("event:sources");
        assertThat(body).contains(filename);

        List<MessageResponse> messages = fetchMessages(authHeaders, sessionId);
        assertThat(messages).extracting(MessageResponse::role).contains("USER", "ASSISTANT");

        // Citation persistence and title generation both happen as best-effort side effects after
        // the stream completes (see SendChatMessageService), so poll rather than assert immediately.
        await().atMost(Duration.ofSeconds(30)).until(
                () -> restTemplate.exchange("/api/chats", HttpMethod.GET, new HttpEntity<>(authHeaders), SessionResponse[].class).getBody(),
                sessions -> sessions.length == 1 && sessions[0].title() != null);

        await().atMost(Duration.ofSeconds(30)).until(
                () -> fetchMessages(authHeaders, sessionId),
                fetched -> fetched.stream()
                        .filter(message -> "ASSISTANT".equals(message.role()))
                        .anyMatch(message -> message.citations() != null && !message.citations().isEmpty()));

        MessageResponse assistantMessage = fetchMessages(authHeaders, sessionId).stream()
                .filter(message -> "ASSISTANT".equals(message.role()))
                .findFirst()
                .orElseThrow();
        assertThat(assistantMessage.citations()).extracting(CitationResponse::filename).contains(filename);
    }

    @Test
    void sidebarOrdersSessionsByMostRecentActivityNotJustFirstMessage() {
        HttpHeaders authHeaders = registerAndLogin("chat-order-" + UUID.randomUUID());

        Long sessionA = createChatSession(authHeaders);
        Long sessionB = createChatSession(authHeaders);

        sendAndAwaitStreamCompletion(authHeaders, sessionA, "Hello from session A");
        sendAndAwaitStreamCompletion(authHeaders, sessionB, "Hello from session B");

        // B was touched more recently than A.
        await().atMost(Duration.ofSeconds(30)).until(
                () -> restTemplate.exchange("/api/chats", HttpMethod.GET, new HttpEntity<>(authHeaders), SessionResponse[].class).getBody(),
                sessions -> sessions.length == 2 && sessions[0].id().equals(sessionB));

        // Touching A again should move it back to the front - this is exactly what was broken
        // when SendChatMessageService only bumped updated_at on a session's first message.
        sendAndAwaitStreamCompletion(authHeaders, sessionA, "Second message in session A");

        await().atMost(Duration.ofSeconds(30)).until(
                () -> restTemplate.exchange("/api/chats", HttpMethod.GET, new HttpEntity<>(authHeaders), SessionResponse[].class).getBody(),
                sessions -> sessions.length == 2 && sessions[0].id().equals(sessionA));
    }

    @Test
    void chatMemoryEvictsOldestMessagesOncePastTheConfiguredWindow() {
        // Test config (application.yml) sets app.chat.memory.max-messages to 4 - proves
        // ChatConfiguration's chatMemory bean actually applies ChatMemoryProperties, not just
        // that it compiles against Spring AI's default 20-message window.
        HttpHeaders authHeaders = registerAndLogin("chat-window-" + UUID.randomUUID());
        Long sessionId = createChatSession(authHeaders);

        sendAndAwaitStreamCompletion(authHeaders, sessionId, "MARKER_ONE this is the first message");
        sendAndAwaitStreamCompletion(authHeaders, sessionId, "MARKER_TWO this is the second message");

        // Two exchanges (4 messages) exactly fill a 4-message window - nothing evicted yet.
        List<MessageResponse> afterTwoExchanges = fetchMessages(authHeaders, sessionId);
        assertThat(afterTwoExchanges).hasSize(4);
        assertThat(afterTwoExchanges).anyMatch(message -> message.content().contains("MARKER_ONE"));

        sendAndAwaitStreamCompletion(authHeaders, sessionId, "MARKER_THREE this is the third message");

        // A third exchange pushes the window past 4 messages - the first exchange should be
        // evicted from the database outright, not just hidden from this one read.
        await().atMost(Duration.ofSeconds(30)).until(
                () -> fetchMessages(authHeaders, sessionId),
                messages -> messages.size() == 4 && messages.stream().noneMatch(message -> message.content().contains("MARKER_ONE")));

        List<MessageResponse> afterThreeExchanges = fetchMessages(authHeaders, sessionId);
        assertThat(afterThreeExchanges).anyMatch(message -> message.content().contains("MARKER_TWO"));
        assertThat(afterThreeExchanges).anyMatch(message -> message.content().contains("MARKER_THREE"));
    }

    @Test
    void renameUpdatesTitleAndRejectsBlank() {
        HttpHeaders authHeaders = registerAndLogin("chat-rename-" + UUID.randomUUID());
        Long sessionId = createChatSession(authHeaders);

        ResponseEntity<SessionResponse> renamed = restTemplate.exchange(
                "/api/chats/" + sessionId, HttpMethod.PATCH, new HttpEntity<>(new RenameRequest("  My Renamed Chat  "), authHeaders), SessionResponse.class);
        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(renamed.getBody().title()).isEqualTo("My Renamed Chat");

        ResponseEntity<String> blank = restTemplate.exchange(
                "/api/chats/" + sessionId, HttpMethod.PATCH, new HttpEntity<>(new RenameRequest("   "), authHeaders), String.class);
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void archiveAndUnarchiveRoundTripAndFilterTheListEndpoint() {
        HttpHeaders authHeaders = registerAndLogin("chat-archive-" + UUID.randomUUID());
        Long sessionId = createChatSession(authHeaders);

        ResponseEntity<SessionResponse> archived = restTemplate.exchange(
                "/api/chats/" + sessionId + "/archive", HttpMethod.POST, new HttpEntity<>(null, authHeaders), SessionResponse.class);
        assertThat(archived.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(archived.getBody().archived()).isTrue();

        assertThat(listSessions(authHeaders, false)).isEmpty();
        assertThat(listSessions(authHeaders, true)).extracting(SessionResponse::id).containsExactly(sessionId);

        ResponseEntity<SessionResponse> unarchived = restTemplate.exchange(
                "/api/chats/" + sessionId + "/unarchive", HttpMethod.POST, new HttpEntity<>(null, authHeaders), SessionResponse.class);
        assertThat(unarchived.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(unarchived.getBody().archived()).isFalse();

        assertThat(listSessions(authHeaders, false)).extracting(SessionResponse::id).containsExactly(sessionId);
        assertThat(listSessions(authHeaders, true)).isEmpty();
    }

    @Test
    void deleteRemovesSessionMessagesAndCitations() {
        HttpHeaders authHeaders = registerAndLogin("chat-delete-" + UUID.randomUUID());
        Long sessionId = createChatSession(authHeaders);
        sendAndAwaitStreamCompletion(authHeaders, sessionId, "Hello before deletion");

        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
                "/api/chats/" + sessionId, HttpMethod.DELETE, new HttpEntity<>(authHeaders), Void.class);
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> getAfterDelete = restTemplate.exchange(
                "/api/chats/" + sessionId, HttpMethod.GET, new HttpEntity<>(authHeaders), String.class);
        assertThat(getAfterDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<String> messagesAfterDelete = restTemplate.exchange(
                "/api/chats/" + sessionId + "/messages", HttpMethod.GET, new HttpEntity<>(authHeaders), String.class);
        assertThat(messagesAfterDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(listSessions(authHeaders, false)).isEmpty();
    }

    @Test
    void sessionMutationsRequireOwnership() {
        HttpHeaders ownerHeaders = registerAndLogin("chat-mut-owner-" + UUID.randomUUID());
        Long sessionId = createChatSession(ownerHeaders);

        HttpHeaders otherHeaders = registerAndLogin("chat-mut-intruder-" + UUID.randomUUID());

        assertThat(restTemplate.exchange("/api/chats/" + sessionId, HttpMethod.GET, new HttpEntity<>(otherHeaders), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.exchange("/api/chats/" + sessionId, HttpMethod.PATCH, new HttpEntity<>(new RenameRequest("Hijacked"), otherHeaders), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.exchange("/api/chats/" + sessionId + "/archive", HttpMethod.POST, new HttpEntity<>(null, otherHeaders), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.exchange("/api/chats/" + sessionId, HttpMethod.DELETE, new HttpEntity<>(otherHeaders), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void messagesRequireOwnership() {
        HttpHeaders ownerHeaders = registerAndLogin("chat-owner-" + UUID.randomUUID());
        Long sessionId = createChatSession(ownerHeaders);

        HttpHeaders otherHeaders = registerAndLogin("chat-intruder-" + UUID.randomUUID());
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/chats/" + sessionId + "/messages", HttpMethod.GET, new HttpEntity<>(otherHeaders), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void nonexistentSessionReturnsNotFound() {
        HttpHeaders headers = registerAndLogin("chat-none-" + UUID.randomUUID());
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/chats/999999999/messages", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private List<SessionResponse> listSessions(HttpHeaders authHeaders, boolean archived) {
        return List.of(restTemplate.exchange(
                        "/api/chats?archived=" + archived, HttpMethod.GET, new HttpEntity<>(authHeaders), SessionResponse[].class)
                .getBody());
    }

    private List<MessageResponse> fetchMessages(HttpHeaders authHeaders, Long sessionId) {
        return List.of(restTemplate.exchange(
                "/api/chats/" + sessionId + "/messages", HttpMethod.GET, new HttpEntity<>(authHeaders), MessageResponse[].class).getBody());
    }

    private void sendAndAwaitStreamCompletion(HttpHeaders authHeaders, Long sessionId, String content) {
        HttpHeaders sseHeaders = new HttpHeaders();
        sseHeaders.addAll(authHeaders);
        sseHeaders.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));

        HttpEntity<SendMessageRequest> request = new HttpEntity<>(new SendMessageRequest(content), sseHeaders);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/chats/" + sessionId + "/messages", HttpMethod.POST, request, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private Long createChatSession(HttpHeaders authHeaders) {
        ResponseEntity<SessionResponse> response = restTemplate.postForEntity(
                "/api/chats", new HttpEntity<>(null, authHeaders), SessionResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private void uploadAndAwaitReady(HttpHeaders authHeaders, String filename, String content) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", namedResource(filename, content.getBytes(StandardCharsets.UTF_8)));

        HttpHeaders headers = new HttpHeaders();
        headers.addAll(authHeaders);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<UploadResponse> response = restTemplate.postForEntity("/api/documents", new HttpEntity<>(body, headers), UploadResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Long documentId = response.getBody().id();

        await().atMost(Duration.ofSeconds(60)).until(
                () -> restTemplate.exchange("/api/documents/" + documentId, HttpMethod.GET, new HttpEntity<>(authHeaders), UploadResponse.class).getBody(),
                document -> !"PROCESSING".equals(document.status()));
    }

    private HttpHeaders registerAndLogin(String username) {
        // BannerAckFilter gates /api/auth/register and /api/auth/login until this session has
        // acknowledged the consent banner (see SecurityConfig) - ack first and carry that
        // session's cookie through both calls.
        HttpHeaders bannerHeaders = TestSessions.acknowledgedSession(restTemplate);

        AuthRequest credentials = new AuthRequest(username, "password123");
        restTemplate.postForEntity("/api/auth/register", new HttpEntity<>(credentials, bannerHeaders), Void.class);
        ResponseEntity<Void> loginResponse = restTemplate.postForEntity(
                "/api/auth/login", new HttpEntity<>(credentials, bannerHeaders), Void.class);

        // Login doesn't always issue a new Set-Cookie - only when session-fixation protection
        // actually changes the session ID. Reuse the banner-ack cookie in that case.
        return TestSessions.afterLogin(bannerHeaders, loginResponse);
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

    private record SendMessageRequest(String content) {}

    private record RenameRequest(String title) {}

    private record SessionResponse(Long id, String title, boolean archived, String createdAt, String updatedAt) {}

    private record MessageResponse(String role, String content, List<CitationResponse> citations) {}

    private record CitationResponse(Long documentId, String filename) {}

    private record UploadResponse(Long id, String filename, String contentType, String status, String errorMessage,
                                   Integer chunkCount, String uploadedBy, String createdAt, String updatedAt) {}
}

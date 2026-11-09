package com.example.clientragdemo.chat.adapter.in.endpoint;

import com.example.clientragdemo.TestSessions;
import com.example.clientragdemo.TestcontainersConfiguration;
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

/**
 * The whole chat against a real model with {@code app.chat.limited-model=true}: the request carries the system
 * instruction and the output cap and is accepted by the model endpoint, retrieval returns at most three chunks, the
 * title is taken from the message (no second model call), and the answer comes back. What a model then <em>does</em>
 * with "at most 4 bullets" cannot be asserted - that depends on the model - so the cap on output is checked only as a
 * loose upper bound.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = "app.chat.limited-model=true")
@AutoConfigureTestRestTemplate
class ChatLimitedModelIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void chatsWithTheLimitedModelBudget() {
        HttpHeaders headers = registerAndLogin("limited-" + UUID.randomUUID());
        // More than three chunks' worth of matching material: the retrieval limit is what keeps the sources to three.
        for (int i = 1; i <= 5; i++) {
            upload(headers, "limit-doc-" + i + "-" + UUID.randomUUID() + ".txt",
                    "The secret code word number " + i + " for the limited model test is BANANAFISH" + i + ". " + "Filler sentence. ".repeat(40));
        }
        Long sessionId = createChatSession(headers);
        String message = "What is the secret code word for the limited model test, please tell me";

        HttpHeaders sse = new HttpHeaders();
        sse.addAll(headers);
        sse.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
        ResponseEntity<String> stream = restTemplate.exchange("/api/chats/" + sessionId + "/messages", HttpMethod.POST,
                new HttpEntity<>(new SendMessageRequest(message), sse), String.class);
        assertThat(stream.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<MessageResponse> messages = await().atMost(Duration.ofSeconds(90)).until(
                () -> fetchMessages(headers, sessionId), found -> found.size() >= 2);
        MessageResponse answer = messages.stream().filter(m -> "ASSISTANT".equalsIgnoreCase(m.role())).findFirst().orElseThrow();
        assertThat(answer.content()).isNotBlank();
        assertThat(answer.content().length()).as("output is capped at 200 tokens").isLessThan(2000);
        assertThat(answer.citations()).as("at most three chunks are retrieved").hasSizeLessThanOrEqualTo(3);

        // Title: the first six words, set without asking the model.
        String expectedTitle = "What is the secret code word";
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
                restTemplate.exchange("/api/chats", HttpMethod.GET, new HttpEntity<>(headers), SessionResponse[].class).getBody())
                .extracting(SessionResponse::title).contains(expectedTitle));
    }

    private List<MessageResponse> fetchMessages(HttpHeaders headers, Long sessionId) {
        return List.of(restTemplate.exchange("/api/chats/" + sessionId + "/messages", HttpMethod.GET,
                new HttpEntity<>(headers), MessageResponse[].class).getBody());
    }

    private Long createChatSession(HttpHeaders headers) {
        ResponseEntity<SessionResponse> response = restTemplate.postForEntity("/api/chats", new HttpEntity<>(null, headers), SessionResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private void upload(HttpHeaders headers, String filename, String content) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        HttpHeaders multipart = new HttpHeaders();
        multipart.addAll(headers);
        multipart.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<UploadResponse> response = restTemplate.postForEntity("/api/documents", new HttpEntity<>(body, multipart), UploadResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Long documentId = response.getBody().id();
        await().atMost(Duration.ofSeconds(60)).until(
                () -> restTemplate.exchange("/api/documents/" + documentId, HttpMethod.GET, new HttpEntity<>(headers), UploadResponse.class).getBody(),
                document -> !"PROCESSING".equals(document.status()));
    }

    private HttpHeaders registerAndLogin(String username) {
        HttpHeaders session = TestSessions.acknowledgedSession(restTemplate);
        AuthRequest credentials = new AuthRequest(username, "password123");
        restTemplate.postForEntity("/api/auth/register", new HttpEntity<>(credentials, session), Void.class);
        ResponseEntity<Void> login = restTemplate.postForEntity("/api/auth/login", new HttpEntity<>(credentials, session), Void.class);
        return TestSessions.afterLogin(session, login);
    }

    private record AuthRequest(String username, String password) {}

    private record SendMessageRequest(String content) {}

    private record SessionResponse(Long id, String title, boolean archived, String createdAt, String updatedAt) {}

    private record MessageResponse(String role, String content, List<CitationResponse> citations) {}

    private record CitationResponse(Long documentId, String filename) {}

    private record UploadResponse(Long id, String filename, String contentType, String status, String errorMessage,
                                   Integer chunkCount, String uploadedBy, String createdAt, String updatedAt) {}
}

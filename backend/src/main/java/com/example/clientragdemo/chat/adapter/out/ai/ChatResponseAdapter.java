package com.example.clientragdemo.chat.adapter.out.ai;

import com.example.clientragdemo.chat.application.domain.model.ChatStreamEvent;
import com.example.clientragdemo.chat.application.domain.model.ChatStreamEvent.SourcesEvent;
import com.example.clientragdemo.chat.application.domain.model.ChatStreamEvent.TokenEvent;
import com.example.clientragdemo.chat.application.domain.model.Citation;
import com.example.clientragdemo.chat.application.port.out.GenerateChatTitlePort;
import com.example.clientragdemo.chat.application.port.out.StreamChatResponsePort;
import com.example.clientragdemo.chat.configuration.ChatRetrievalProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Component
class ChatResponseAdapter implements StreamChatResponsePort, GenerateChatTitlePort {

    private static final String TITLE_SYSTEM_PROMPT = """
            Summarize the user's message into a short chat title of at most 6 words.
            Respond with the title text only - no quotes, no punctuation at the end, no explanation.""";

    private final ChatClient chatClient;
    private final QuestionAnswerAdvisor questionAnswerAdvisor;
    private final MessageChatMemoryAdvisor messageChatMemoryAdvisor;

    ChatResponseAdapter(ChatClient.Builder chatClientBuilder, VectorStore vectorStore, ChatMemory chatMemory,
                        ChatRetrievalProperties retrievalProperties) {
        this.chatClient = chatClientBuilder.build();
        this.questionAnswerAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder().topK(retrievalProperties.topK()).build())
                .build();
        this.messageChatMemoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();
    }

    @Override
    public Flux<ChatStreamEvent> stream(Long sessionId, String userMessage) {
        Flux<ChatClientResponse> responses = chatClient.prompt()
                .user(userMessage)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId.toString()))
                .advisors(messageChatMemoryAdvisor, questionAnswerAdvisor)
                .stream()
                .chatClientResponse();

        AtomicReference<List<Citation>> sourcesRef = new AtomicReference<>(List.of());

        Flux<ChatStreamEvent> tokens = responses
                .doOnNext(response -> captureSources(response, sourcesRef))
                .mapNotNull(ChatResponseAdapter::extractText)
                .filter(text -> !text.isEmpty())
                .map(TokenEvent::new);

        return tokens.concatWith(Flux.defer(() -> Flux.just(new SourcesEvent(sourcesRef.get()))));
    }

    @Override
    public String generateTitle(String firstUserMessage) {
        String title = chatClient.prompt()
                .system(TITLE_SYSTEM_PROMPT)
                .user(firstUserMessage)
                .call()
                .content();

        if (title == null || title.isBlank()) {
            return "New chat";
        }

        String trimmed = title.strip().replaceAll("^\"|\"$", "");
        return trimmed.length() > 100 ? trimmed.substring(0, 100) : trimmed;
    }

    private static void captureSources(ChatClientResponse response, AtomicReference<List<Citation>> sourcesRef) {
        Object retrieved = response.context().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
        if (retrieved instanceof List<?> documents) {
            List<Citation> citations = documents.stream()
                    .filter(Document.class::isInstance)
                    .map(Document.class::cast)
                    .map(ChatResponseAdapter::toCitation)
                    .distinct()
                    .toList();
            sourcesRef.set(citations);
        }
    }

    private static Citation toCitation(Document document) {
        Object documentId = document.getMetadata().get("document_id");
        Object filename = document.getMetadata().get("filename");
        return new Citation(documentId instanceof Number number ? number.longValue() : null, String.valueOf(filename));
    }

    private static String extractText(ChatClientResponse response) {
        if (response.chatResponse() == null || response.chatResponse().getResult() == null) {
            return null;
        }
        return response.chatResponse().getResult().getOutput().getText();
    }
}

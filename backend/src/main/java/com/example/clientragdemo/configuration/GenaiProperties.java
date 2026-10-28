package com.example.clientragdemo.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

// Which bound Cloud Foundry GenAI service supplies the chat model (see GenaiModelConfiguration).
// The embedding side is ingestion-core's GenaiEmbeddingProperties, bound from the same prefix.
// chatModel is optional: with a single-model plan (the intended setup) the one model the service
// grants is used and this stays blank; it only disambiguates an all-models plan.
@ConfigurationProperties(prefix = "app.genai")
// chatTimeout is optional: unset preserves the locator's default. Slow internal models can use
// APP_GENAI_CHAT_TIMEOUT=300s without changing model selection or service credentials.
public record GenaiProperties(String chatService, String chatModel, Duration chatTimeout) {
}

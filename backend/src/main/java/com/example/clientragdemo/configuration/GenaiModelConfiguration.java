package com.example.clientragdemo.configuration;

import com.example.clientragdemo.ingestion.configuration.GenaiLocatorFactory;
import com.example.clientragdemo.ingestion.configuration.GenaiModelSelector;
import io.pivotal.cfenv.boot.genai.GenaiLocator;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

// Cloud Foundry only: active when app.genai.chat-service is set (application-cloud.yaml).
// Everywhere else (local Ollama, tests) Spring AI's own spring.ai.openai.* autoconfiguration is
// used unchanged.
//
// The chat model comes from its own bound GenAI service, normally on a single-model plan, so which
// model is used is decided by which service you bind - not by a model name in a manifest. The
// embedding model is handled the same way by ingestion-core's GenaiBindingsConfiguration (shared
// with sharepoint-batch), which also supplies the GenaiLocatorFactory used here.
@Configuration
@ConditionalOnProperty("app.genai.chat-service")
@EnableConfigurationProperties(GenaiProperties.class)
class GenaiModelConfiguration {

    @Bean
    @Primary
    ChatModel genaiChatModel(GenaiLocatorFactory locators, GenaiProperties properties) {
        if (properties.chatTimeout() != null && !properties.chatTimeout().isPositive()) {
            throw new IllegalArgumentException("app.genai.chat-timeout must be positive");
        }
        GenaiLocator locator = locators.forService(properties.chatService());
        String name = GenaiModelSelector.select(properties.chatService(), "CHAT",
                locator.getModelNamesByCapability("CHAT"), properties.chatModel());
        ChatModel model = locator.getChatModelByName(name);
        if (properties.chatTimeout() == null) {
            return model;
        }
        if (!(model instanceof OpenAiChatModel openAiModel)) {
            throw new IllegalStateException("app.genai.chat-timeout requires an OpenAI-compatible chat model");
        }
        // DefaultGenaiLocator constructs the client directly, so Spring AI's auto-configuration
        // timeout properties do not affect it. Retain the binding's model, URL, and credentials.
        return OpenAiChatModel.builder()
                .options(openAiModel.getOptions().mutate().timeout(properties.chatTimeout()).build())
                .build();
    }
}

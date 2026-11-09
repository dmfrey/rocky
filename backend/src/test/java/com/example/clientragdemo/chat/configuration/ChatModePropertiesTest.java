package com.example.clientragdemo.chat.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChatModePropertiesTest {

    private static ChatModeProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties)).bindOrCreate("app.chat", Bindable.of(ChatModeProperties.class));
    }

    @Test
    void isOffByDefault() {
        assertThat(bind(Map.of()).limitedModel()).isFalse();
    }

    @Test
    void bindsTheSingleSwitchAppChatLimitedModel() {
        // APP_CHAT_LIMITED_MODEL=true
        assertThat(bind(Map.of("app.chat.limited-model", "true")).limitedModel()).isTrue();
    }

    @Test
    void isNotDisturbedByTheOtherAppChatSettings() {
        ChatModeProperties properties = bind(Map.of("app.chat.limited-model", "true", "app.chat.retrieval.top-k", "9", "app.chat.memory.max-messages", "30"));

        assertThat(properties.limitedModel()).isTrue();
    }

    @Test
    void theInstructionStatesTheAgreedBudget() {
        assertThat(ChatModeProperties.ANSWER_INSTRUCTION).contains("at most 4 bullets").contains("under 100 words").contains("document id");
        assertThat(ChatModeProperties.RETRIEVAL_TOP_K).isEqualTo(3);
    }
}

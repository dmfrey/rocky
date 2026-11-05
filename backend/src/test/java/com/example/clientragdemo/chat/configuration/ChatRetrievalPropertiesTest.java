package com.example.clientragdemo.chat.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatRetrievalPropertiesTest {

    private static ChatRetrievalProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate("app.chat.retrieval", Bindable.of(ChatRetrievalProperties.class));
    }

    @Test
    void defaultsToSpringAisOwnTopKOfFour() {
        assertThat(bind(Map.of()).topK()).isEqualTo(4);
    }

    @Test
    void bindsTopKFromTheProperty() {
        assertThat(bind(Map.of("app.chat.retrieval.top-k", "10")).topK()).isEqualTo(10);
    }

    @Test
    void rejectsATopKBelowOne() {
        assertThatThrownBy(() -> bind(Map.of("app.chat.retrieval.top-k", "0")))
                .isInstanceOf(BindException.class)
                .hasRootCauseMessage("app.chat.retrieval.top-k must be at least 1, was 0");
    }
}

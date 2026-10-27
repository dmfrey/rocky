package com.example.clientragdemo.ingestion.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class GenaiModelSelectorTest {

    @Test
    void usesTheOneModelASingleModelServiceGrants() {
        assertThat(GenaiModelSelector.select("svc", "CHAT", List.of("openai/gpt-oss-120b"), null))
                .isEqualTo("openai/gpt-oss-120b");
        assertThat(GenaiModelSelector.select("svc", "CHAT", List.of("openai/gpt-oss-120b"), "  "))
                .isEqualTo("openai/gpt-oss-120b");
    }

    @Test
    void rejectsAServiceThatGrantsNoModelOfTheCapability() {
        assertThatThrownBy(() -> GenaiModelSelector.select("rocky-embed", "EMBEDDING", List.of(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rocky-embed")
                .hasMessageContaining("no EMBEDDING model");
    }

    @Test
    void rejectsAnAmbiguousServiceInsteadOfPickingTheFirstModel() {
        assertThatThrownBy(() -> GenaiModelSelector.select("all", "CHAT", List.of("a", "b", "c"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("3 CHAT models (a, b, c)")
                .hasMessageContaining("app.genai.chat-model");
    }

    @Test
    void anExplicitNameResolvesAnAmbiguousService() {
        assertThat(GenaiModelSelector.select("all", "CHAT", List.of("a", "b"), "b")).isEqualTo("b");
    }

    @Test
    void anExplicitNameThatTheServiceDoesNotGrantIsRejected() {
        assertThatThrownBy(() -> GenaiModelSelector.select("all", "EMBEDDING", List.of("a"), "typo"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'typo'")
                .hasMessageContaining("grants: a");
    }
}

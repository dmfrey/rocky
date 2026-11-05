package com.example.clientragdemo.chat.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How many document chunks the chat's retrieval step hands the model with each question
 * ({@code QuestionAnswerAdvisor}'s {@code topK}). Spring AI's own default is 4, which is what this
 * defaults to so nothing changes until someone overrides it. With the small chunks the embedding
 * model forces (128 tokens is common on Tanzu Platform), 4 chunks is only ~500 tokens of source
 * text, so a larger chat model's context window goes unused: raise it
 * ({@code APP_CHAT_RETRIEVAL_TOP_K}) together with the model's context size - see
 * {@code offline/README.md}.
 */
@ConfigurationProperties(prefix = "app.chat.retrieval")
public record ChatRetrievalProperties(@DefaultValue("4") int topK) {

    public ChatRetrievalProperties {
        if (topK < 1) {
            throw new IllegalArgumentException("app.chat.retrieval.top-k must be at least 1, was " + topK);
        }
    }
}

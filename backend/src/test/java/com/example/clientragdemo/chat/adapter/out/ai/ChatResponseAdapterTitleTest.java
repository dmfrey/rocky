package com.example.clientragdemo.chat.adapter.out.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatResponseAdapterTitleTest {

    @Test
    void usesTheFirstSixWords() {
        assertThat(ChatResponseAdapter.fallbackTitle("How do I rotate the database credentials for the backend?"))
                .isEqualTo("How do I rotate the database");
    }

    @Test
    void keepsAShortMessageWhole() {
        assertThat(ChatResponseAdapter.fallbackTitle("  What is Rocky?  ")).isEqualTo("What is Rocky?");
    }

    @Test
    void collapsesWhitespaceAndNewlines() {
        assertThat(ChatResponseAdapter.fallbackTitle("List\n\nthe   supported\tfile types")).isEqualTo("List the supported file types");
    }

    @Test
    void capsAVeryLongWordRun() {
        String title = ChatResponseAdapter.fallbackTitle("a".repeat(200) + " b");

        assertThat(title).hasSizeLessThanOrEqualTo(60);
    }

    @Test
    void fallsBackWhenThereIsNothingToUse() {
        assertThat(ChatResponseAdapter.fallbackTitle("   ")).isEqualTo("New chat");
        assertThat(ChatResponseAdapter.fallbackTitle(null)).isEqualTo("New chat");
    }
}

package com.example.clientragdemo.chat.configuration;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;

@Configuration
@ComponentScan(basePackages = "com.example.clientragdemo.chat")
@EnableJdbcRepositories(basePackages = "com.example.clientragdemo.chat.adapter.out.persistence")
@EnableConfigurationProperties({ChatMemoryProperties.class, ChatRetrievalProperties.class, ChatModeProperties.class})
class ChatConfiguration {

    // Overrides Spring AI's ChatMemoryAutoConfiguration#chatMemory, which is
    // @ConditionalOnMissingBean and otherwise hardcodes a 20-message window with no property to
    // change it - this is the supported override point (define your own ChatMemory bean, Boot's
    // auto-configured default backs off), now driven by ChatMemoryProperties instead of a
    // hardcoded number here.
    @Bean
    ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository, ChatMemoryProperties chatMemoryProperties,
                          ChatModeProperties chatModeProperties) {
        int maxMessages = chatModeProperties.limitedModel()
                ? Math.min(chatMemoryProperties.maxMessages(), ChatModeProperties.MEMORY_MAX_MESSAGES)
                : chatMemoryProperties.maxMessages();
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(maxMessages)
                .build();
    }
}

package com.example.clientragdemo.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.clientragdemo.ingestion.configuration.GenaiLocatorFactory;
import io.pivotal.cfenv.boot.genai.GenaiLocator;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

// The chat model comes from its own separately bound GenAI service, looked up by name; the model
// used is whichever one that service grants. (The embedding side is covered by ingestion-core's
// GenaiBindingsConfigurationTest.)
class GenaiModelConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GenaiModelConfiguration.class)
            .withPropertyValues("app.genai.chat-service=chat-svc");

    @Test
    void takesTheChatModelFromTheChatService() {
        ChatModel chat = mock(ChatModel.class);
        GenaiLocator chatLocator = mock(GenaiLocator.class);
        when(chatLocator.getModelNamesByCapability("CHAT")).thenReturn(List.of("openai/gpt-oss-120b"));
        when(chatLocator.getChatModelByName("openai/gpt-oss-120b")).thenReturn(chat);
        GenaiLocatorFactory factory = name -> name.equals("chat-svc") ? chatLocator : null;

        runner.withBean(GenaiLocatorFactory.class, () -> factory)
                .run(context -> assertThat(context.getBean(ChatModel.class)).isSameAs(chat));
    }

    @Test
    void failsStartupWhenTheServiceIsAmbiguous() {
        GenaiLocator allModels = mock(GenaiLocator.class);
        when(allModels.getModelNamesByCapability("CHAT")).thenReturn(List.of("a", "b"));

        runner.withBean(GenaiLocatorFactory.class, () -> name -> allModels).run(context ->
                assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("2 CHAT models"));
    }

    @Test
    void extendsTheTimeoutWhileKeepingTheBoundModelAndCredentials() {
        OpenAiChatModel boundModel = OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .model("gemma3:4b")
                        .baseUrl("https://internal-model.example.invalid/openai")
                        .apiKey("test-binding-key")
                        .build())
                .build();
        GenaiLocator locator = mock(GenaiLocator.class);
        when(locator.getModelNamesByCapability("CHAT")).thenReturn(List.of("gemma3:4b"));
        when(locator.getChatModelByName("gemma3:4b")).thenReturn(boundModel);

        runner.withPropertyValues("app.genai.chat-timeout=300s")
                .withBean(GenaiLocatorFactory.class, () -> name -> locator)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    OpenAiChatOptions options = ((OpenAiChatModel) context.getBean(ChatModel.class)).getOptions();
                    assertThat(options.getTimeout()).isEqualTo(Duration.ofMinutes(5));
                    assertThat(options.getModel()).isEqualTo("gemma3:4b");
                    assertThat(options.getBaseUrl()).isEqualTo("https://internal-model.example.invalid/openai");
                    assertThat(options.getApiKey()).isEqualTo("test-binding-key");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "-1s"})
    void rejectsNonPositiveTimeouts(String timeout) {
        runner.withPropertyValues("app.genai.chat-timeout=" + timeout)
                .withBean(GenaiLocatorFactory.class, () -> name -> null)
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("app.genai.chat-timeout must be positive"));
    }

    @Test
    void inactiveWithoutTheServiceName() {
        new ApplicationContextRunner().withUserConfiguration(GenaiModelConfiguration.class).run(context ->
                assertThat(context).doesNotHaveBean(ChatModel.class));
    }
}

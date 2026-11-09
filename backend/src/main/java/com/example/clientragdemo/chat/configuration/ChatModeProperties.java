package com.example.clientragdemo.chat.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code app.chat.limited-model} ({@code APP_CHAT_LIMITED_MODEL}): the deployment's chat model is small or slow -
 * the offline lab runs CPU-only models, where generating a token and, above all, reading a long prompt is
 * expensive. When on, every part of a chat that costs model time is cut down at once:
 *
 * <ul>
 *   <li>retrieval hands the model at most {@value #RETRIEVAL_TOP_K} chunks, set in the search request itself -
 *       not asked of the model, which a small model would not reliably follow - which also shortens the prompt;</li>
 *   <li>a system message limits every answer ({@link #ANSWER_INSTRUCTION}), and each chunk the model sees is
 *       labelled with its document id so it can cite one;</li>
 *   <li>chat history keeps at most {@value #MEMORY_MAX_MESSAGES} messages, so earlier turns do not bloat the prompt;</li>
 *   <li>output is capped at {@value #MAX_OUTPUT_TOKENS} tokens, a hard backstop for the word limit;</li>
 *   <li>a new chat's title comes from the first words of the message instead of a second model call.</li>
 * </ul>
 *
 * The figures are constants, not settings: they are the lab's agreed budget, and this is one switch.
 */
@ConfigurationProperties(prefix = "app.chat")
public record ChatModeProperties(@DefaultValue("false") boolean limitedModel) {

    public static final int RETRIEVAL_TOP_K = 3;
    public static final int MEMORY_MAX_MESSAGES = 6;
    public static final int MAX_OUTPUT_TOKENS = 200;

    public static final String ANSWER_INSTRUCTION = """
            For every answer in this conversation: use at most 4 bullets, stay under 100 words, and cite \
            exactly one source by its document id, written like (document 42). The document ids are in the \
            context, in the form [document 42: file.pdf].""";
}

package com.aicodehelper.memory;

import com.aicodehelper.config.AppProperties;
import com.aicodehelper.error.AiExecutionRejectedException;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.service.memory.ChatMemoryService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationMemoryRegistryTest {

    @Test
    void rewindsOneCompleteTurnAndCanClearTheFirstTurn() {
        ConversationMemoryRegistry registry = new ConversationMemoryRegistry(new AppProperties());
        ChatMemory memory = registry.get("guest:one:memory:same-id");
        memory.add(UserMessage.from("first"));
        memory.add(AiMessage.from("first answer"));
        memory.add(UserMessage.from("second"));
        memory.add(AiMessage.from("second answer"));

        assertThat(registry.rewindLastTurn("guest:one:memory:same-id")).isTrue();
        assertThat(memory.messages()).containsExactly(
                UserMessage.from("first"),
                AiMessage.from("first answer")
        );

        assertThat(registry.rewindLastTurn("guest:one:memory:same-id")).isTrue();
        assertThat(memory.messages()).isEmpty();
    }

    @Test
    void sameClientMemoryIdIsIsolatedByOwnerKey() {
        ConversationMemoryRegistry registry = new ConversationMemoryRegistry(new AppProperties());
        registry.get("guest:one:memory:42").add(UserMessage.from("one"));
        registry.get("guest:two:memory:42").add(UserMessage.from("two"));

        assertThat(registry.size()).isEqualTo(2);
        assertThat(registry.get("guest:one:memory:42").messages()).containsExactly(UserMessage.from("one"));
        assertThat(registry.get("guest:two:memory:42").messages()).containsExactly(UserMessage.from("two"));
    }

    @Test
    void snapshotRestoresThePreviousTurnAfterFailedRegeneration() {
        ConversationMemoryRegistry registry = new ConversationMemoryRegistry(new AppProperties());
        String key = "guest:one:memory:regen";
        ChatMemory memory = registry.get(key);
        memory.add(UserMessage.from("original"));
        memory.add(AiMessage.from("original answer"));

        ConversationMemoryRegistry.RewindSnapshot snapshot = registry.rewindLastTurnWithSnapshot(key);
        memory.add(UserMessage.from("replacement"));
        registry.restore(key, snapshot);

        assertThat(registry.get(key).messages()).containsExactly(
                UserMessage.from("original"),
                AiMessage.from("original answer")
        );
    }

    @Test
    void firstFailureKeepsTheSameMemoryObjectCachedByLangChain4j() {
        ConversationMemoryRegistry registry = new ConversationMemoryRegistry(new AppProperties());
        ChatMemoryService langChainMemory = new ChatMemoryService(registry);
        String key = "guest:one:memory:first-failure";

        ConversationMemoryRegistry.RewindSnapshot beforeFirstCall = registry.snapshot(key);
        ChatMemory cached = langChainMemory.getOrCreateChatMemory(key);
        cached.add(UserMessage.from("failed request"));
        registry.restore(key, beforeFirstCall);

        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.get(key)).isSameAs(cached);
        assertThat(langChainMemory.getOrCreateChatMemory(key)).isSameAs(cached);
        assertThat(cached.messages()).isEmpty();

        cached.add(UserMessage.from("successful request"));
        assertThat(registry.snapshot(key).messages()).containsExactly(UserMessage.from("successful request"));
    }

    @Test
    void rejectsNewConversationInsteadOfEvictingAnObjectCachedByLangChain4j() {
        AppProperties properties = new AppProperties();
        properties.getAi().setMaxConversations(2);
        ConversationMemoryRegistry registry = new ConversationMemoryRegistry(properties);
        ChatMemory first = registry.get("first");
        registry.get("second");

        assertThatThrownBy(() -> registry.get("third"))
                .isInstanceOf(AiExecutionRejectedException.class)
                .hasMessageContaining("容量已满");
        assertThat(registry.size()).isEqualTo(2);
        assertThat(registry.get("first")).isSameAs(first);
    }
}

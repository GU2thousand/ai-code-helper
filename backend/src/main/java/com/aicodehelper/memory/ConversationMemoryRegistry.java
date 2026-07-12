package com.aicodehelper.memory;

import com.aicodehelper.config.AppProperties;
import com.aicodehelper.error.AiExecutionRejectedException;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

@Component
public class ConversationMemoryRegistry implements ChatMemoryProvider {

    private final Map<Object, MemoryEntry> memories = new ConcurrentHashMap<>();
    private final Object creationLock = new Object();
    private final int maxMessages;
    private final int maxConversations;

    public ConversationMemoryRegistry(AppProperties properties) {
        this.maxMessages = properties.getAi().getMaxMemoryMessages();
        this.maxConversations = properties.getAi().getMaxConversations();
        if (maxConversations < 1) {
            throw new IllegalArgumentException("AI_MAX_CONVERSATIONS must be at least 1");
        }
    }

    @Override
    public ChatMemory get(Object memoryId) {
        MemoryEntry existing = memories.get(memoryId);
        if (existing != null) {
            existing.touch();
            return existing.memory();
        }
        synchronized (creationLock) {
            existing = memories.get(memoryId);
            if (existing != null) {
                existing.touch();
                return existing.memory();
            }
            if (memories.size() >= maxConversations) {
                throw AiExecutionRejectedException.memoryCapacityReached();
            }
            MemoryEntry created = newEntry(memoryId);
            memories.put(memoryId, created);
            created.touch();
            return created.memory();
        }
    }

    public int size() {
        return memories.size();
    }

    public boolean contains(Object memoryId) {
        return memories.containsKey(memoryId);
    }

    public boolean isAtCapacity() {
        return memories.size() >= maxConversations;
    }

    public Optional<Object> leastRecentlyUsed(Predicate<Object> candidateFilter) {
        return memories.entrySet().stream()
                .filter(entry -> candidateFilter.test(entry.getKey()))
                .min((left, right) -> Long.compare(
                        left.getValue().lastAccessNanos(),
                        right.getValue().lastAccessNanos()
                ))
                .map(Map.Entry::getKey);
    }

    /**
     * Remove only after every LangChain4j ChatMemoryAccess cache has evicted the same id.
     */
    public void evictAfterServiceCaches(Object memoryId) {
        MemoryEntry removed = memories.remove(memoryId);
        if (removed != null) {
            removed.memory().clear();
        }
    }

    public void clear() {
        memories.values().forEach(entry -> entry.memory().clear());
    }

    public boolean rewindLastTurn(Object memoryId) {
        return rewindLastTurnWithSnapshot(memoryId).rewound();
    }

    public RewindSnapshot rewindLastTurnWithSnapshot(Object memoryId) {
        MemoryEntry entry = memories.get(memoryId);
        if (entry == null) {
            return new RewindSnapshot(false, false, List.of());
        }
        ChatMemory memory = entry.memory();
        synchronized (memory) {
            List<ChatMessage> messages = memory.messages();
            for (int index = messages.size() - 1; index >= 0; index--) {
                if (messages.get(index) instanceof UserMessage) {
                    if (index == 0) {
                        memory.clear();
                    } else {
                        memory.set(messages.subList(0, index));
                    }
                    entry.touch();
                    return new RewindSnapshot(true, true, messages);
                }
            }
            return new RewindSnapshot(true, false, messages);
        }
    }

    public RewindSnapshot snapshot(Object memoryId) {
        MemoryEntry entry = memories.get(memoryId);
        if (entry == null) {
            return new RewindSnapshot(false, false, List.of());
        }
        ChatMemory memory = entry.memory();
        synchronized (memory) {
            entry.touch();
            return new RewindSnapshot(true, false, memory.messages());
        }
    }

    public void restore(Object memoryId, RewindSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        if (!snapshot.memoryExisted()) {
            MemoryEntry created = memories.get(memoryId);
            if (created != null) {
                created.memory().clear();
                created.touch();
            }
            return;
        }
        MemoryEntry entry = memories.get(memoryId);
        if (entry == null) {
            throw new IllegalStateException("Cannot replace a ChatMemory already cached by LangChain4j");
        }
        ChatMemory memory = entry.memory();
        synchronized (memory) {
            if (snapshot.messages().isEmpty()) {
                memory.clear();
            } else {
                memory.set(snapshot.messages());
            }
            entry.touch();
        }
    }

    private MemoryEntry newEntry(Object id) {
        return new MemoryEntry(new SynchronizedChatMemory(MessageWindowChatMemory.builder()
                .id(id)
                .maxMessages(maxMessages)
                .alwaysKeepSystemMessageFirst(true)
                .build()));
    }

    private static final class MemoryEntry {
        private final ChatMemory memory;
        private volatile long lastAccessNanos = System.nanoTime();

        private MemoryEntry(ChatMemory memory) {
            this.memory = memory;
        }

        private ChatMemory memory() {
            return memory;
        }

        private long lastAccessNanos() {
            return lastAccessNanos;
        }

        private void touch() {
            lastAccessNanos = System.nanoTime();
        }
    }

    private static final class SynchronizedChatMemory implements ChatMemory {
        private final ChatMemory delegate;

        private SynchronizedChatMemory(ChatMemory delegate) {
            this.delegate = delegate;
        }

        @Override
        public synchronized Object id() {
            return delegate.id();
        }

        @Override
        public synchronized void add(ChatMessage message) {
            delegate.add(message);
        }

        @Override
        public synchronized void set(Iterable<ChatMessage> messages) {
            delegate.set(messages);
        }

        @Override
        public synchronized List<ChatMessage> messages() {
            return List.copyOf(delegate.messages());
        }

        @Override
        public synchronized void clear() {
            delegate.clear();
        }
    }

    public record RewindSnapshot(
            boolean memoryExisted,
            boolean rewound,
            List<ChatMessage> messages
    ) {
        public RewindSnapshot {
            messages = List.copyOf(messages);
        }
    }
}

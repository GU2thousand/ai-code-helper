package com.aicodehelper.memory;

import com.aicodehelper.config.AppProperties;
import com.aicodehelper.user.GuestSessionService;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Clock;
import static org.assertj.core.api.Assertions.*;

class DurableStateTest {
    @TempDir Path directory;
    private AppProperties properties() {
        AppProperties properties = new AppProperties();
        properties.getStorage().setEnabled(true);
        properties.getStorage().setDirectory(directory.toString());
        return properties;
    }

    @Test void defaultSigningKeySurvivesRestartAndRejectsCorruption() throws Exception {
        var properties = properties();
        var first = new GuestSessionService(Clock.systemUTC(), properties);
        var guest = first.create("Test");
        var restarted = new GuestSessionService(Clock.systemUTC(), properties);
        assertThat(restarted.verify(guest.userId(), guest.token())).isTrue();
        Files.writeString(directory.resolve("session.key"), "corrupted");
        assertThatThrownBy(() -> new GuestSessionService(Clock.systemUTC(), properties))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Invalid persisted");
    }

    @Test void onlyCommittedTurnsSurviveRestartAndRemainOwnerIsolated() {
        var properties = properties();
        var first = new ConversationMemoryRegistry(properties);
        first.get("owner-a:memory:1").add(UserMessage.from("remember 42"));
        first.get("owner-a:memory:1").add(AiMessage.from("saved"));
        first.commit("owner-a:memory:1");
        var snapshot = first.rewindLastTurnWithSnapshot("owner-a:memory:1");
        first.get("owner-a:memory:1").add(UserMessage.from("incomplete regeneration"));
        var restarted = new ConversationMemoryRegistry(properties);
        assertThat(restarted.get("owner-a:memory:1").messages()).isEqualTo(snapshot.messages());
        assertThat(restarted.get("owner-b:memory:1").messages()).isEmpty();
        first.restore("owner-a:memory:1", snapshot);
        assertThat(first.get("owner-a:memory:1").messages()).isEqualTo(snapshot.messages());
        first.evictAfterServiceCaches("owner-a:memory:1");
        assertThat(first.get("owner-a:memory:1").messages()).isEqualTo(snapshot.messages());
    }

    @Test void snapshotRetentionIsBoundedAndFileNamesCannotEscapeDirectory() throws Exception {
        var properties = properties();
        properties.getAi().setMaxConversations(2);
        var registry = new ConversationMemoryRegistry(properties);
        for (String id : new String[]{"../../a", "b", "c"}) {
            registry.get(id).add(UserMessage.from(id));
            registry.commit(id);
            registry.evictAfterServiceCaches(id);
        }
        try (var files = Files.list(directory)) {
            assertThat(files.toList()).hasSize(2).allSatisfy(path ->
                    assertThat(path.getFileName().toString()).matches("[a-f0-9]{64}\\.json"));
        }
    }
}

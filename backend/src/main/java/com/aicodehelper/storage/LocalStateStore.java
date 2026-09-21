package com.aicodehelper.storage;

import com.aicodehelper.config.AppProperties;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Single-instance durable state. Mount this directory on persistent, private storage. */
public final class LocalStateStore {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LocalStateStore.class);
    private final Path directory;
    private final int maxConversations;

    public LocalStateStore(AppProperties properties) {
        directory = properties.getStorage().isEnabled()
                ? Path.of(properties.getStorage().getDirectory()).toAbsolutePath() : null;
        maxConversations = properties.getAi().getMaxConversations();
        if (directory != null) {
            try {
                Files.createDirectories(directory);
                if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
                    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
                }
            } catch (IOException error) { throw failure(error); }
        }
    }

    public byte[] signingSecret() {
        if (directory == null) return randomSecret();
        // Serialize first creation across processes; never overwrite an existing signing key.
        try (FileChannel channel = FileChannel.open(directory.resolve("session.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var ignored = channel.lock()) {
            Path path = directory.resolve("session.key");
            if (!Files.exists(path)) write(path, randomSecret());
            byte[] secret = Files.readAllBytes(path);
            if (secret.length != 32) throw new IllegalStateException("Invalid persisted session key");
            return secret;
        } catch (IOException error) { throw failure(error); }
    }

    public List<ChatMessage> load(Object id) {
        if (directory == null) return List.of();
        Path path = conversationPath(id);
        try {
            if (!Files.exists(path)) return List.of();
            return ChatMessageDeserializer.messagesFromJson(Files.readString(path));
        } catch (IOException error) { throw failure(error); }
    }

    public synchronized void save(Object id, List<ChatMessage> messages) {
        if (directory == null) return;
        Path path = conversationPath(id);
        try {
            write(path, ChatMessageSerializer.messagesToJson(messages).getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) { throw failure(error); }
        try {
            // Bound disk retention as well as the in-process cache; keep the newly committed turn.
            try (var files = Files.list(directory)) {
                var snapshots = files.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .filter(p -> !p.equals(path))
                        .sorted(Comparator.comparingLong(p -> p.toFile().lastModified())).toList();
                for (int i = 0; i < snapshots.size() + 1 - maxConversations; i++) {
                    Files.deleteIfExists(snapshots.get(i));
                }
            }
        } catch (IOException error) {
            // The atomic commit has already succeeded: never report a failed turn or roll it back
            // merely because retention cleanup failed. Retry cleanup on the next successful save.
            log.warn("Durable state retention cleanup failed errorType={}", error.getClass().getSimpleName());
        }
    }

    private Path conversationPath(Object id) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(id).getBytes(StandardCharsets.UTF_8));
            return directory.resolve(HexFormat.of().formatHex(hash) + ".json");
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private void write(Path path, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(directory, ".state-", ".tmp");
        try {
            if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            }
            Files.write(temporary, bytes);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private byte[] randomSecret() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        return secret;
    }

    private IllegalStateException failure(IOException error) {
        return new IllegalStateException("Cannot access APP_DATA_DIR durable state", error);
    }
}

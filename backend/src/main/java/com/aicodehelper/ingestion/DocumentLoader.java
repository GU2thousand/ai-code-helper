package com.aicodehelper.ingestion;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DocumentLoader {
    private final ResourcePatternResolver resolver;
    public DocumentLoader(ResourcePatternResolver resolver) { this.resolver = resolver; }

    public List<SourceDocument> load(String pattern) {
        try {
            Map<String, SourceDocument> documents = new LinkedHashMap<>();
            for (Resource resource : resolver.getResources(pattern)) {
                if (!resource.isReadable()) continue;
                String source = resource.getFilename() == null ? "knowledge.md" : resource.getFilename();
                // Resolved URI avoids collisions for recursive patterns with duplicate filenames.
                String location = canonicalLocation(pattern, resource);
                String text;
                try (var input = resource.getInputStream()) {
                    text = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
                }
                documents.putIfAbsent(location, new SourceDocument(source, location, text, hash(text)));
            }
            return documents.values().stream().sorted(Comparator.comparing(SourceDocument::location)).toList();
        } catch (IOException error) {
            throw new IllegalStateException("Unable to load knowledge-base documents", error);
        }
    }

    private String canonicalLocation(String pattern, Resource resource) throws IOException {
        String uri = resource.getURI().toString();
        if (!pattern.startsWith("classpath")) return uri;
        String path = pattern.substring(pattern.indexOf(':') + 1).replaceFirst("^/", "");
        int wildcard = path.indexOf('*');
        if (wildcard < 0) return "classpath:" + path;
        String root = path.substring(0, path.lastIndexOf('/', wildcard) + 1);
        int position = uri.lastIndexOf("/" + root);
        return "classpath:" + (position < 0 ? root + resource.getFilename() : uri.substring(position + 1));
    }

    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    public record SourceDocument(String source, String location, String text, String sourceHash) {}
}

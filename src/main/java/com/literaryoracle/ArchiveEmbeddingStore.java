package com.literaryoracle;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/** Loads and validates pre-generated archive vectors without making network calls. */
@Component
public final class ArchiveEmbeddingStore {
    static final String RESOURCE_NAME = "archive-embeddings-v3.json";
    static final int SCHEMA_VERSION = 1;
    static final String TASK = "retrieval.passage";
    static final int DIMENSIONS = 1024;

    private final Map<Long, StoredVector> vectors;
    private final boolean ready;

    @Autowired
    public ArchiveEmbeddingStore(ObjectMapper objectMapper, ArchiveRepository repository) {
        this(load(objectMapper), repository.entries());
    }

    ArchiveEmbeddingStore(EmbeddingDocument document, List<OracleEntry> archive) {
        Map<Long, StoredVector> validated;
        boolean valid;
        try {
            validated = validate(document, archive);
            valid = true;
        } catch (RuntimeException exception) {
            validated = Map.of();
            valid = false;
        }
        this.vectors = validated;
        this.ready = valid;
    }

    boolean ready() {
        return ready;
    }

    int size() {
        return vectors.size();
    }

    List<EmbeddingMatch> nearest(float[] queryVector, Set<Long> allowedIds, int limit) {
        if (!ready || queryVector == null || queryVector.length != DIMENSIONS || limit <= 0) {
            return List.of();
        }
        double queryNorm = norm(queryVector);
        if (!Double.isFinite(queryNorm) || queryNorm <= 0) return List.of();
        Set<Long> allowed = allowedIds == null ? Set.of() : allowedIds;
        List<EmbeddingMatch> matches = new ArrayList<>();
        vectors.forEach((id, stored) -> {
            if (!allowed.isEmpty() && !allowed.contains(id)) return;
            double cosine = dot(queryVector, stored.vector()) / (queryNorm * stored.norm());
            if (Double.isFinite(cosine)) matches.add(new EmbeddingMatch(id, cosine));
        });
        return matches.stream()
                .sorted(Comparator.comparingDouble(EmbeddingMatch::cosine).reversed()
                        .thenComparingLong(EmbeddingMatch::id))
                .limit(limit)
                .toList();
    }

    static String embeddingText(OracleEntry entry) {
        LocalizedArchiveContent english = entry.localizations().get("en");
        if (english == null) {
            throw new IllegalArgumentException("Archive entry has no English localization");
        }
        return "passage=" + english.passage()
                + "\ncontext=" + english.contextNote()
                + "\nthemes=" + String.join(",", entry.themes());
    }

    static String contentHash(OracleEntry entry) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(embeddingText(entry).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Map<Long, StoredVector> validate(EmbeddingDocument document,
            List<OracleEntry> archive) {
        if (document == null || document.schemaVersion() != SCHEMA_VERSION
                || !JinaSemanticRetriever.EMBEDDING_MODEL.equals(document.model())
                || !TASK.equals(document.task()) || document.dimensions() != DIMENSIONS
                || !document.normalized() || document.entries() == null
                || document.archiveCount() != archive.size()
                || document.entries().size() != archive.size()) {
            throw new IllegalArgumentException("Embedding metadata does not match the archive");
        }
        Map<Long, OracleEntry> archiveById = new HashMap<>();
        archive.forEach(entry -> archiveById.put(entry.id(), entry));
        if (archiveById.size() != archive.size()) {
            throw new IllegalArgumentException("Archive IDs are not unique");
        }

        Set<Long> seen = new HashSet<>();
        Map<Long, StoredVector> result = new LinkedHashMap<>();
        for (EmbeddingRecord record : document.entries()) {
            if (record == null || !seen.add(record.id())) {
                throw new IllegalArgumentException("Embedding IDs are not unique");
            }
            OracleEntry entry = archiveById.get(record.id());
            if (entry == null || !contentHash(entry).equals(record.contentHash())
                    || record.vector() == null || record.vector().size() != DIMENSIONS) {
                throw new IllegalArgumentException("Embedding entry does not match the archive");
            }
            float[] vector = new float[DIMENSIONS];
            for (int index = 0; index < DIMENSIONS; index++) {
                Double value = record.vector().get(index);
                if (value == null || !Double.isFinite(value)) {
                    throw new IllegalArgumentException("Embedding vectors must be finite");
                }
                vector[index] = value.floatValue();
            }
            double vectorNorm = norm(vector);
            if (!Double.isFinite(vectorNorm) || vectorNorm <= 0) {
                throw new IllegalArgumentException("Embedding vectors must be non-zero");
            }
            result.put(record.id(), new StoredVector(vector, vectorNorm));
        }
        if (!seen.equals(archiveById.keySet())) {
            throw new IllegalArgumentException("Embedding IDs do not cover the archive");
        }
        return Map.copyOf(result);
    }

    private static EmbeddingDocument load(ObjectMapper objectMapper) {
        ClassPathResource resource = new ClassPathResource(RESOURCE_NAME);
        if (!resource.exists()) return null;
        try (InputStream input = resource.getInputStream()) {
            return objectMapper.readValue(input, EmbeddingDocument.class);
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private static double dot(float[] left, float[] right) {
        double dot = 0;
        for (int index = 0; index < left.length; index++) dot += left[index] * right[index];
        return dot;
    }

    private static double norm(float[] vector) {
        double squared = 0;
        for (float value : vector) {
            if (!Float.isFinite(value)) return Double.NaN;
            squared += value * value;
        }
        return Math.sqrt(squared);
    }

    record EmbeddingMatch(long id, double cosine) {
    }

    private record StoredVector(float[] vector, double norm) {
    }

    public record EmbeddingDocument(
            int schemaVersion,
            String model,
            String task,
            int dimensions,
            boolean normalized,
            int archiveCount,
            List<EmbeddingRecord> entries) {
    }

    public record EmbeddingRecord(long id, String contentHash, List<Double> vector) {
    }
}

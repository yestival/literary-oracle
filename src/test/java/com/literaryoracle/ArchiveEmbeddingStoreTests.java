package com.literaryoracle;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.core.io.ClassPathResource;

import tools.jackson.databind.ObjectMapper;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ArchiveEmbeddingStoreTests {
    private static final int EXPECTED_VECTOR_COUNT = 440;

    private List<OracleEntry> archive;
    private ArchiveEmbeddingStore.EmbeddingDocument document;

    @BeforeAll
    void loadArchiveAndEmbeddingResourceOnce() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        SupportedLanguageCatalog catalog = new SupportedLanguageCatalog(objectMapper);
        archive = new ArchiveRepository(objectMapper, catalog).entries();

        ClassPathResource resource = new ClassPathResource(
                ArchiveEmbeddingStore.RESOURCE_NAME);
        try (InputStream input = resource.getInputStream()) {
            document = objectMapper.readValue(input,
                    ArchiveEmbeddingStore.EmbeddingDocument.class);
        }
    }

    @Test
    void checkedInResourceExactlyMatchesEveryCurrentArchiveEntry() {
        ArchiveEmbeddingStore store = new ArchiveEmbeddingStore(document, archive);

        assertAll(
                () -> assertEquals(ArchiveEmbeddingStore.SCHEMA_VERSION,
                        document.schemaVersion()),
                () -> assertEquals(JinaSemanticRetriever.EMBEDDING_MODEL,
                        document.model()),
                () -> assertEquals(ArchiveEmbeddingStore.TASK, document.task()),
                () -> assertEquals(ArchiveEmbeddingStore.DIMENSIONS,
                        document.dimensions()),
                () -> assertTrue(document.normalized()),
                () -> assertEquals(EXPECTED_VECTOR_COUNT, archive.size()),
                () -> assertEquals(EXPECTED_VECTOR_COUNT, document.archiveCount()),
                () -> assertEquals(EXPECTED_VECTOR_COUNT, document.entries().size()),
                () -> assertTrue(store.ready()),
                () -> assertEquals(EXPECTED_VECTOR_COUNT, store.size()));

        Map<Long, OracleEntry> archiveById = new HashMap<>();
        archive.forEach(entry -> archiveById.put(entry.id(), entry));
        Set<Long> vectorIds = new HashSet<>();
        for (ArchiveEmbeddingStore.EmbeddingRecord record : document.entries()) {
            assertTrue(vectorIds.add(record.id()),
                    () -> "Duplicate embedding ID " + record.id());
            OracleEntry entry = archiveById.get(record.id());
            assertTrue(entry != null,
                    () -> "Embedding ID is absent from archive: " + record.id());
            assertEquals(ArchiveEmbeddingStore.contentHash(entry), record.contentHash(),
                    () -> "Content hash mismatch for archive ID " + record.id());
            assertEquals(ArchiveEmbeddingStore.DIMENSIONS, record.vector().size(),
                    () -> "Wrong vector dimensions for archive ID " + record.id());
            assertTrue(record.vector().stream()
                            .allMatch(value -> value != null && Double.isFinite(value)),
                    () -> "Non-finite vector component for archive ID " + record.id());
        }
        assertEquals(archiveById.keySet(), vectorIds);
    }

    @Test
    void corruptSmallDocumentsRemainNotReadyWithoutLoadingOrGeneratingOtherVectors() {
        List<OracleEntry> smallArchive = List.copyOf(archive.subList(0, 2));
        Map<Long, ArchiveEmbeddingStore.EmbeddingRecord> recordsById = new HashMap<>();
        document.entries().forEach(record -> recordsById.put(record.id(), record));
        ArchiveEmbeddingStore.EmbeddingRecord first = recordsById.get(smallArchive.get(0).id());
        ArchiveEmbeddingStore.EmbeddingRecord second = recordsById.get(smallArchive.get(1).id());
        assertTrue(first != null && second != null);

        ArchiveEmbeddingStore.EmbeddingRecord wrongHash =
                new ArchiveEmbeddingStore.EmbeddingRecord(first.id(), "0".repeat(64),
                        first.vector());

        ArchiveEmbeddingStore.EmbeddingRecord duplicateId =
                new ArchiveEmbeddingStore.EmbeddingRecord(first.id(), second.contentHash(),
                        second.vector());

        List<Double> shortVector = new ArrayList<>(first.vector());
        shortVector.remove(shortVector.size() - 1);
        ArchiveEmbeddingStore.EmbeddingRecord wrongDimensions =
                new ArchiveEmbeddingStore.EmbeddingRecord(first.id(), first.contentHash(),
                        List.copyOf(shortVector));

        List<ArchiveEmbeddingStore> corruptStores = List.of(
                new ArchiveEmbeddingStore(smallDocument(List.of(wrongHash, second)),
                        smallArchive),
                new ArchiveEmbeddingStore(smallDocument(List.of(first, duplicateId)),
                        smallArchive),
                new ArchiveEmbeddingStore(smallDocument(List.of(wrongDimensions, second)),
                        smallArchive));

        float[] query = new float[ArchiveEmbeddingStore.DIMENSIONS];
        query[0] = 1.0f;
        for (ArchiveEmbeddingStore store : corruptStores) {
            assertAll(
                    () -> assertFalse(store.ready()),
                    () -> assertEquals(0, store.size()),
                    () -> assertTrue(store.nearest(query, Set.of(), 30).isEmpty()));
        }
    }

    private static ArchiveEmbeddingStore.EmbeddingDocument smallDocument(
            List<ArchiveEmbeddingStore.EmbeddingRecord> entries) {
        return new ArchiveEmbeddingStore.EmbeddingDocument(
                ArchiveEmbeddingStore.SCHEMA_VERSION,
                JinaSemanticRetriever.EMBEDDING_MODEL,
                ArchiveEmbeddingStore.TASK,
                ArchiveEmbeddingStore.DIMENSIONS,
                true,
                entries.size(),
                entries);
    }
}

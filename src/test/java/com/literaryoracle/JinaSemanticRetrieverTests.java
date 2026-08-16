package com.literaryoracle;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.mockito.ArgumentCaptor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class JinaSemanticRetrieverTests {
    private static final String FAKE_KEY = "unit-test-placeholder";
    private static final URI EMBEDDING_ENDPOINT = URI.create("https://mock.invalid/embeddings");
    private static final URI RERANK_ENDPOINT = URI.create("https://mock.invalid/rerank");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OracleEntry first = entry(10L,
            "The first English passage.", "\u7b2c\u4e00\u6bb5\u4e2d\u6587\u3002",
            "First English context.");
    private final OracleEntry second = entry(20L,
            "The second English passage.", "\u7b2c\u4e8c\u6bb5\u4e2d\u6587\u3002",
            "Second English context.");
    private final List<OracleEntry> archive = List.of(first, second);
    private final ArchiveEmbeddingStore embeddingStore = embeddingStore(archive,
            List.of(vector(1.0, 0.1), vector(0.7, 0.7)));

    @Test
    void missingKeyFallsBackWithoutAnyHttpCall() {
        HttpClient httpClient = mock(HttpClient.class);
        JinaSemanticRetriever retriever = retriever(httpClient, "   ");

        SemanticRetriever.RetrievalResult result = retriever.retrieve(
                "A private input", "en", archive);

        assertAll(
                () -> assertEquals(SemanticRetriever.SemanticMode.LOCAL_FALLBACK,
                        result.mode()),
                () -> assertTrue(result.candidates().isEmpty()),
                () -> assertFalse(retriever.status().configured()),
                () -> assertFalse(retriever.status().reachable()),
                () -> assertTrue(retriever.status().archiveEmbeddingsReady()),
                () -> assertEquals("NOT_CONFIGURED", retriever.status().lastCallStatus()));
        verifyNoInteractions(httpClient);
    }

    @Test
    void crossLanguageRequestUsesBothStagesAndRerankerControlsTheFinalOrder()
            throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> embeddingResponse = response(200,
                embeddingResponse(vector(1.0, 0.0)));
        HttpResponse<String> rerankResponse = response(200, rerankResponse(
                new ResultScore(1, 0.95), new ResultScore(0, 0.10)));
        when(httpClient.send(any(HttpRequest.class),
                anyStringBodyHandler())).thenReturn(embeddingResponse, rerankResponse);
        JinaSemanticRetriever retriever = retriever(httpClient, FAKE_KEY);
        String chineseInput = "\u6240\u6709\u4eba\u90fd\u6709\u5f52\u5904\uff0c"
                + "\u53ea\u6709\u6211\u8fd8\u5728\u95e8\u5916\u3002";

        SemanticRetriever.RetrievalResult result = retriever.retrieve(
                chineseInput, "zh-Hans", archive);

        assertAll(
                () -> assertEquals(SemanticRetriever.SemanticMode.JINA_RERANKED,
                        result.mode()),
                () -> assertEquals(List.of(20L, 10L), result.candidates().stream()
                        .map(SemanticRetriever.RankedCandidate::id).toList()),
                () -> assertTrue(result.candidates().get(0).score()
                        > result.candidates().get(1).score()),
                () -> assertTrue(retriever.status().configured()),
                () -> assertTrue(retriever.status().reachable()),
                () -> assertEquals("RERANKED", retriever.status().lastCallStatus()));

        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(2)).send(requests.capture(), anyStringBodyHandler());
        HttpRequest embeddingRequest = requests.getAllValues().get(0);
        HttpRequest rerankRequest = requests.getAllValues().get(1);
        JsonNode embeddingJson = objectMapper.readTree(requestBody(embeddingRequest));
        JsonNode rerankJson = objectMapper.readTree(requestBody(rerankRequest));

        assertAll(
                () -> assertEquals(EMBEDDING_ENDPOINT, embeddingRequest.uri()),
                () -> assertEquals(RERANK_ENDPOINT, rerankRequest.uri()),
                () -> assertEquals(Duration.ofSeconds(20),
                        embeddingRequest.timeout().orElseThrow()),
                () -> assertEquals("Bearer " + FAKE_KEY, embeddingRequest.headers()
                        .firstValue("Authorization").orElseThrow()),
                () -> assertEquals(JinaSemanticRetriever.EMBEDDING_MODEL,
                        embeddingJson.path("model").asText()),
                () -> assertEquals("retrieval.query", embeddingJson.path("task").asText()),
                () -> assertEquals(chineseInput,
                        embeddingJson.path("input").get(0).asText()),
                () -> assertEquals(JinaSemanticRetriever.RERANKER_MODEL,
                        rerankJson.path("model").asText()),
                () -> assertEquals(chineseInput, rerankJson.path("query").asText()),
                () -> assertEquals(2, rerankJson.path("documents").size()),
                () -> assertTrue(rerankJson.path("documents").get(0).asText()
                        .contains("archive_id=10\npassage=\u7b2c\u4e00\u6bb5\u4e2d\u6587\u3002"
                                + "\ncontext=First English context.")),
                () -> assertTrue(rerankJson.path("documents").get(1).asText()
                        .contains("archive_id=20\npassage=\u7b2c\u4e8c\u6bb5\u4e2d\u6587\u3002"
                                + "\ncontext=Second English context.")),
                () -> assertFalse(requestBody(embeddingRequest).contains(FAKE_KEY)),
                () -> assertFalse(requestBody(rerankRequest).contains(FAKE_KEY)));
    }

    @Test
    void illegalEmbeddingResponseFallsBackWithoutCallingTheReranker() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> invalidEmbedding = response(200,
                embeddingResponse(List.of(1.0, 0.0)));
        when(httpClient.send(any(HttpRequest.class), anyStringBodyHandler()))
                .thenReturn(invalidEmbedding);
        JinaSemanticRetriever retriever = retriever(httpClient, FAKE_KEY);

        SemanticRetriever.RetrievalResult result = retriever.retrieve(
                "Input with an invalid embedding response", "en", archive);

        assertAll(
                () -> assertEquals(SemanticRetriever.SemanticMode.LOCAL_FALLBACK,
                        result.mode()),
                () -> assertTrue(result.candidates().isEmpty()),
                () -> assertTrue(retriever.status().reachable()),
                () -> assertEquals("EMBEDDING_FAILED", retriever.status().lastCallStatus()));
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(1)).send(request.capture(), anyStringBodyHandler());
        assertEquals(EMBEDDING_ENDPOINT, request.getValue().uri());
    }

    @Test
    void outOfRangeRerankerIndexFallsBackToEmbeddingOnly() throws Exception {
        assertInvalidRerankerFallsBackToEmbeddingOnly(rerankResponse(
                new ResultScore(0, 0.9), new ResultScore(2, 0.8)));
    }

    @Test
    void duplicateRerankerIndexFallsBackToEmbeddingOnly() throws Exception {
        assertInvalidRerankerFallsBackToEmbeddingOnly(rerankResponse(
                new ResultScore(0, 0.9), new ResultScore(0, 0.8)));
    }

    @Test
    void retryableFailureIsRetriedOnlyOnce() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> firstFailure = response(503, "{}");
        HttpResponse<String> secondFailure = response(503, "{}");
        when(httpClient.send(any(HttpRequest.class), anyStringBodyHandler()))
                .thenReturn(firstFailure, secondFailure);
        JinaSemanticRetriever retriever = retriever(httpClient, FAKE_KEY);

        SemanticRetriever.RetrievalResult result = retriever.retrieve(
                "Transient failure", "en", archive);

        assertAll(
                () -> assertEquals(SemanticRetriever.SemanticMode.LOCAL_FALLBACK,
                        result.mode()),
                () -> assertTrue(result.candidates().isEmpty()),
                () -> assertTrue(retriever.status().reachable()),
                () -> assertEquals("EMBEDDING_FAILED", retriever.status().lastCallStatus()));
        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(2)).send(requests.capture(), anyStringBodyHandler());
        assertTrue(requests.getAllValues().stream()
                .allMatch(request -> EMBEDDING_ENDPOINT.equals(request.uri())));
    }

    @TestFactory
    Stream<DynamicTest> embeddingHttpFailuresUseTheDocumentedFallback() {
        return Stream.of(
                new HttpFailureCase(401, 1),
                new HttpFailureCase(429, 2),
                new HttpFailureCase(500, 2))
                .map(testCase -> DynamicTest.dynamicTest(
                        "embedding HTTP " + testCase.status(), () -> {
                            HttpClient httpClient = mock(HttpClient.class);
                            HttpResponse<String> failure = response(testCase.status(), "{}");
                            when(httpClient.send(any(HttpRequest.class), anyStringBodyHandler()))
                                    .thenReturn(failure);
                            JinaSemanticRetriever retriever = retriever(httpClient, FAKE_KEY);

                            SemanticRetriever.RetrievalResult result = retriever.retrieve(
                                    "synthetic semantic query", "en", archive);

                            assertAll(
                                    () -> assertEquals(
                                            SemanticRetriever.SemanticMode.LOCAL_FALLBACK,
                                            result.mode()),
                                    () -> assertTrue(result.candidates().isEmpty()),
                                    () -> assertEquals("EMBEDDING_FAILED",
                                            retriever.status().lastCallStatus()),
                                    () -> assertTrue(retriever.status().reachable()));
                            verify(httpClient, times(testCase.expectedCalls())).send(
                                    any(HttpRequest.class), anyStringBodyHandler());
                        }));
    }

    @TestFactory
    Stream<DynamicTest> embeddingTransportFailuresRetryOnceAndFallBack() {
        return Stream.of(
                new TransportFailureCase("timeout",
                        new HttpTimeoutException("synthetic timeout")),
                new TransportFailureCase("network I/O",
                        new IOException("synthetic network failure")))
                .map(testCase -> DynamicTest.dynamicTest(
                        "embedding " + testCase.name(), () -> {
                            HttpClient httpClient = mock(HttpClient.class);
                            when(httpClient.send(any(HttpRequest.class), anyStringBodyHandler()))
                                    .thenThrow(testCase.failure());
                            JinaSemanticRetriever retriever = retriever(httpClient, FAKE_KEY);

                            SemanticRetriever.RetrievalResult result = retriever.retrieve(
                                    "synthetic semantic query", "en", archive);

                            assertAll(
                                    () -> assertEquals(
                                            SemanticRetriever.SemanticMode.LOCAL_FALLBACK,
                                            result.mode()),
                                    () -> assertTrue(result.candidates().isEmpty()),
                                    () -> assertEquals("EMBEDDING_FAILED",
                                            retriever.status().lastCallStatus()),
                                    () -> assertFalse(retriever.status().reachable()));
                            verify(httpClient, times(2)).send(
                                    any(HttpRequest.class), anyStringBodyHandler());
                        }));
    }

    @Test
    void malformedEmbeddingJsonUsesTheLocalFallback() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> malformedResponse = response(200, "not-json");
        when(httpClient.send(any(HttpRequest.class), anyStringBodyHandler()))
                .thenReturn(malformedResponse);
        JinaSemanticRetriever retriever = retriever(httpClient, FAKE_KEY);

        SemanticRetriever.RetrievalResult result = retriever.retrieve(
                "synthetic semantic query", "en", archive);

        assertAll(
                () -> assertEquals(SemanticRetriever.SemanticMode.LOCAL_FALLBACK,
                        result.mode()),
                () -> assertTrue(result.candidates().isEmpty()),
                () -> assertEquals("EMBEDDING_FAILED",
                        retriever.status().lastCallStatus()),
                () -> assertTrue(retriever.status().reachable()));
        verify(httpClient).send(any(HttpRequest.class), anyStringBodyHandler());
    }

    @TestFactory
    Stream<DynamicTest> rerankerHttpFailuresKeepTheValidatedEmbeddingOrder() {
        return Stream.of(
                new HttpFailureCase(401, 1),
                new HttpFailureCase(429, 2),
                new HttpFailureCase(500, 2))
                .map(testCase -> DynamicTest.dynamicTest(
                        "reranker HTTP " + testCase.status(), () -> {
                            HttpClient httpClient = mock(HttpClient.class);
                            HttpResponse<String> embeddingResponse = response(200,
                                    embeddingResponse(vector(1.0, 0.0)));
                            HttpResponse<String> failure = response(testCase.status(), "{}");
                            when(httpClient.send(any(HttpRequest.class), anyStringBodyHandler()))
                                    .thenReturn(embeddingResponse, failure);
                            JinaSemanticRetriever retriever = retriever(httpClient, FAKE_KEY);

                            SemanticRetriever.RetrievalResult result = retriever.retrieve(
                                    "synthetic semantic query", "en", archive);

                            assertAll(
                                    () -> assertEquals(
                                            SemanticRetriever.SemanticMode.JINA_EMBEDDING_ONLY,
                                            result.mode()),
                                    () -> assertEquals(List.of(10L, 20L),
                                            result.candidates().stream()
                                                    .map(SemanticRetriever.RankedCandidate::id)
                                                    .toList()),
                                    () -> assertEquals("RERANK_FAILED",
                                            retriever.status().lastCallStatus()),
                                    () -> assertTrue(retriever.status().reachable()));
                            verify(httpClient, times(1 + testCase.expectedCalls())).send(
                                    any(HttpRequest.class), anyStringBodyHandler());
                        }));
    }

    @TestFactory
    Stream<DynamicTest> rerankerTransportFailuresKeepTheValidatedEmbeddingOrder() {
        return Stream.of(
                new TransportFailureCase("timeout",
                        new HttpTimeoutException("synthetic timeout")),
                new TransportFailureCase("network I/O",
                        new IOException("synthetic network failure")))
                .map(testCase -> DynamicTest.dynamicTest(
                        "reranker " + testCase.name(), () -> {
                            HttpClient httpClient = mock(HttpClient.class);
                            HttpResponse<String> embeddingResponse = response(200,
                                    embeddingResponse(vector(1.0, 0.0)));
                            when(httpClient.send(any(HttpRequest.class), anyStringBodyHandler()))
                                    .thenReturn(embeddingResponse)
                                    .thenThrow(testCase.failure());
                            JinaSemanticRetriever retriever = retriever(httpClient, FAKE_KEY);

                            SemanticRetriever.RetrievalResult result = retriever.retrieve(
                                    "synthetic semantic query", "en", archive);

                            assertAll(
                                    () -> assertEquals(
                                            SemanticRetriever.SemanticMode.JINA_EMBEDDING_ONLY,
                                            result.mode()),
                                    () -> assertEquals(List.of(10L, 20L),
                                            result.candidates().stream()
                                                    .map(SemanticRetriever.RankedCandidate::id)
                                                    .toList()),
                                    () -> assertEquals("RERANK_FAILED",
                                            retriever.status().lastCallStatus()),
                                    () -> assertFalse(retriever.status().reachable()));
                            verify(httpClient, times(3)).send(
                                    any(HttpRequest.class), anyStringBodyHandler());
                        }));
    }

    private void assertInvalidRerankerFallsBackToEmbeddingOnly(String invalidRerankResponse)
            throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> embeddingResponse = response(200,
                embeddingResponse(vector(1.0, 0.0)));
        HttpResponse<String> rerankResponse = response(200, invalidRerankResponse);
        when(httpClient.send(any(HttpRequest.class), anyStringBodyHandler())).thenReturn(
                embeddingResponse, rerankResponse);
        JinaSemanticRetriever retriever = retriever(httpClient, FAKE_KEY);

        SemanticRetriever.RetrievalResult result = retriever.retrieve(
                "Input whose reranking response is invalid", "en", archive);

        assertAll(
                () -> assertEquals(SemanticRetriever.SemanticMode.JINA_EMBEDDING_ONLY,
                        result.mode()),
                () -> assertEquals(List.of(10L, 20L), result.candidates().stream()
                        .map(SemanticRetriever.RankedCandidate::id).toList()),
                () -> assertEquals("RERANK_FAILED", retriever.status().lastCallStatus()),
                () -> assertTrue(retriever.status().reachable()));
        verify(httpClient, times(2)).send(any(HttpRequest.class), anyStringBodyHandler());
    }

    private JinaSemanticRetriever retriever(HttpClient httpClient, String apiKey) {
        return new JinaSemanticRetriever(httpClient, objectMapper, embeddingStore, apiKey,
                EMBEDDING_ENDPOINT, RERANK_ENDPOINT);
    }

    private ArchiveEmbeddingStore embeddingStore(List<OracleEntry> entries,
            List<List<Double>> vectors) {
        List<ArchiveEmbeddingStore.EmbeddingRecord> records = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            OracleEntry entry = entries.get(index);
            records.add(new ArchiveEmbeddingStore.EmbeddingRecord(entry.id(),
                    ArchiveEmbeddingStore.contentHash(entry), vectors.get(index)));
        }
        ArchiveEmbeddingStore.EmbeddingDocument document =
                new ArchiveEmbeddingStore.EmbeddingDocument(
                        ArchiveEmbeddingStore.SCHEMA_VERSION,
                        JinaSemanticRetriever.EMBEDDING_MODEL,
                        ArchiveEmbeddingStore.TASK,
                        ArchiveEmbeddingStore.DIMENSIONS,
                        true,
                        entries.size(),
                        records);
        ArchiveEmbeddingStore store = new ArchiveEmbeddingStore(document, entries);
        assertTrue(store.ready());
        return store;
    }

    private OracleEntry entry(long id, String englishPassage, String chinesePassage,
            String englishContext) {
        LocalizedArchiveContent english = new LocalizedArchiveContent(englishPassage,
                "English title " + id, englishContext, "Author biography", "Translation note");
        LocalizedArchiveContent chinese = new LocalizedArchiveContent(chinesePassage,
                "Chinese title " + id, "Chinese context " + id,
                "Author biography", "Translation note");
        return new OracleEntry(id, englishPassage, "en",
                Map.of("en", english, "zh-Hans", chinese), "Author " + id,
                "Original title " + id, 1900, "POEM", "https://example.invalid/source/" + id,
                List.of("hope"), "PUBLIC_DOMAIN");
    }

    private static List<Double> vector(double first, double second) {
        List<Double> vector = new ArrayList<>(
                java.util.Collections.nCopies(ArchiveEmbeddingStore.DIMENSIONS, 0.0));
        vector.set(0, first);
        vector.set(1, second);
        return List.copyOf(vector);
    }

    private String embeddingResponse(List<Double> embedding) throws Exception {
        return objectMapper.writeValueAsString(Map.of("data", List.of(Map.of(
                "index", 0,
                "embedding", embedding))));
    }

    private String rerankResponse(ResultScore... scores) throws Exception {
        List<Map<String, Object>> results = new ArrayList<>();
        for (ResultScore score : scores) {
            results.add(Map.of("index", score.index(), "relevance_score", score.score()));
        }
        return objectMapper.writeValueAsString(Map.of("results", results));
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    private static HttpResponse.BodyHandler<String> anyStringBodyHandler() {
        return any();
    }

    private static String requestBody(HttpRequest request) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CompletableFuture<Void> completed = new CompletableFuture<>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer buffer) {
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                output.writeBytes(bytes);
            }

            @Override
            public void onError(Throwable throwable) {
                completed.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                completed.complete(null);
            }
        });
        completed.get(1, TimeUnit.SECONDS);
        return output.toString(StandardCharsets.UTF_8);
    }

    private record ResultScore(int index, double score) {
    }

    private record HttpFailureCase(int status, int expectedCalls) {
    }

    private record TransportFailureCase(String name, IOException failure) {
    }
}

package com.literaryoracle;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

/** Backend-only Jina two-stage semantic retrieval with a bounded retry. */
@Component
public final class JinaSemanticRetriever implements SemanticRetriever {
    static final String EMBEDDING_MODEL = "jina-embeddings-v3";
    static final String RERANKER_MODEL = "jina-reranker-v3";
    static final String API_KEY_ENVIRONMENT_VARIABLE = "JINA_API_KEY";
    static final URI EMBEDDING_ENDPOINT = URI.create("https://api.jina.ai/v1/embeddings");
    static final URI RERANK_ENDPOINT = URI.create("https://api.jina.ai/v1/rerank");
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final int FIRST_STAGE_LIMIT = 30;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ObjectReader strictReader;
    private final ArchiveEmbeddingStore embeddingStore;
    private final String apiKey;
    private final URI embeddingEndpoint;
    private final URI rerankEndpoint;
    private final AtomicBoolean reachable = new AtomicBoolean(false);
    private final AtomicReference<String> lastCallStatus = new AtomicReference<>("NOT_CALLED");

    @Autowired
    public JinaSemanticRetriever(ObjectMapper objectMapper, ArchiveEmbeddingStore embeddingStore) {
        this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(), objectMapper,
                embeddingStore, System.getenv(API_KEY_ENVIRONMENT_VARIABLE),
                EMBEDDING_ENDPOINT, RERANK_ENDPOINT);
    }

    JinaSemanticRetriever(HttpClient httpClient, ObjectMapper objectMapper,
            ArchiveEmbeddingStore embeddingStore, String apiKey,
            URI embeddingEndpoint, URI rerankEndpoint) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.strictReader = objectMapper.reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
        this.embeddingStore = embeddingStore;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.embeddingEndpoint = embeddingEndpoint;
        this.rerankEndpoint = rerankEndpoint;
    }

    @Override
    public RetrievalResult retrieve(String input, String languageCode,
            List<OracleEntry> availableEntries) {
        if (apiKey.isBlank()) {
            lastCallStatus.set("NOT_CONFIGURED");
            return RetrievalResult.localFallback();
        }
        if (!embeddingStore.ready()) {
            lastCallStatus.set("ARCHIVE_EMBEDDINGS_NOT_READY");
            return RetrievalResult.localFallback();
        }
        if (input == null || input.isBlank() || availableEntries == null
                || availableEntries.isEmpty()) {
            lastCallStatus.set("INVALID_REQUEST");
            return RetrievalResult.localFallback();
        }

        Optional<float[]> queryVector = embedQuery(input);
        if (queryVector.isEmpty()) {
            lastCallStatus.set("EMBEDDING_FAILED");
            return RetrievalResult.localFallback();
        }
        Set<Long> allowedIds = availableEntries.stream().map(OracleEntry::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<ArchiveEmbeddingStore.EmbeddingMatch> nearest = embeddingStore.nearest(
                queryVector.orElseThrow(), allowedIds, FIRST_STAGE_LIMIT);
        if (nearest.isEmpty()) {
            lastCallStatus.set("EMBEDDING_NO_CANDIDATES");
            return RetrievalResult.localFallback();
        }

        Map<Long, OracleEntry> availableById = new HashMap<>();
        availableEntries.forEach(entry -> availableById.put(entry.id(), entry));
        List<Double> embeddingScores = normalize(nearest.stream()
                .map(ArchiveEmbeddingStore.EmbeddingMatch::cosine).toList());
        List<RankedCandidate> embeddingOnly = new ArrayList<>();
        for (int index = 0; index < nearest.size(); index++) {
            embeddingOnly.add(new RankedCandidate(nearest.get(index).id(),
                    embeddingScores.get(index)));
        }

        List<String> documents = nearest.stream().map(match -> rerankDocument(
                availableById.get(match.id()), languageCode)).toList();
        Optional<List<RerankScore>> reranked = rerank(input, documents);
        if (reranked.isEmpty()) {
            lastCallStatus.set("RERANK_FAILED");
            return new RetrievalResult(SemanticMode.JINA_EMBEDDING_ONLY, embeddingOnly);
        }

        List<RerankScore> scores = reranked.orElseThrow();
        List<Double> normalizedRerank = normalize(scores.stream()
                .map(RerankScore::score).toList());
        List<RankedCandidate> combined = new ArrayList<>();
        for (int rank = 0; rank < scores.size(); rank++) {
            RerankScore rerankScore = scores.get(rank);
            ArchiveEmbeddingStore.EmbeddingMatch match = nearest.get(rerankScore.index());
            double score = 0.90 * normalizedRerank.get(rank)
                    + 0.10 * embeddingScores.get(rerankScore.index());
            combined.add(new RankedCandidate(match.id(), clamp(score)));
        }
        combined.sort(Comparator.comparingDouble(RankedCandidate::score).reversed()
                .thenComparingLong(RankedCandidate::id));
        lastCallStatus.set("RERANKED");
        return new RetrievalResult(SemanticMode.JINA_RERANKED, combined);
    }

    @Override
    public SemanticStatus status() {
        return new SemanticStatus(!apiKey.isBlank(), reachable.get(), EMBEDDING_MODEL,
                RERANKER_MODEL, embeddingStore.ready(), lastCallStatus.get());
    }

    private Optional<float[]> embedQuery(String input) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", EMBEDDING_MODEL);
        body.put("task", "retrieval.query");
        body.put("dimensions", ArchiveEmbeddingStore.DIMENSIONS);
        body.put("normalized", true);
        body.put("embedding_type", "float");
        body.put("late_chunking", false);
        body.put("truncate", true);
        body.put("input", List.of(input));
        Optional<JsonNode> root = post(embeddingEndpoint, body);
        if (root.isEmpty()) return Optional.empty();
        JsonNode data = root.orElseThrow().get("data");
        if (data == null || !data.isArray() || data.size() != 1
                || data.get(0).path("index").asInt(-1) != 0) return Optional.empty();
        JsonNode embedding = data.get(0).get("embedding");
        if (embedding == null || !embedding.isArray()
                || embedding.size() != ArchiveEmbeddingStore.DIMENSIONS) {
            return Optional.empty();
        }
        float[] vector = new float[ArchiveEmbeddingStore.DIMENSIONS];
        double norm = 0;
        for (int index = 0; index < vector.length; index++) {
            JsonNode value = embedding.get(index);
            if (!value.isNumber() || !Double.isFinite(value.doubleValue())) {
                return Optional.empty();
            }
            vector[index] = value.floatValue();
            norm += vector[index] * vector[index];
        }
        return norm > 0 && Double.isFinite(norm) ? Optional.of(vector) : Optional.empty();
    }

    private Optional<List<RerankScore>> rerank(String input, List<String> documents) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", RERANKER_MODEL);
        body.put("query", input);
        body.put("documents", documents);
        body.put("top_n", documents.size());
        body.put("return_documents", false);
        body.put("return_embeddings", false);
        Optional<JsonNode> root = post(rerankEndpoint, body);
        if (root.isEmpty()) return Optional.empty();
        JsonNode results = root.orElseThrow().get("results");
        if (results == null || !results.isArray() || results.size() != documents.size()) {
            return Optional.empty();
        }
        Set<Integer> seen = new HashSet<>();
        List<RerankScore> parsed = new ArrayList<>();
        for (JsonNode result : results) {
            JsonNode indexNode = result.get("index");
            JsonNode scoreNode = result.get("relevance_score");
            if (indexNode == null || !indexNode.isIntegralNumber()
                    || scoreNode == null || !scoreNode.isNumber()) return Optional.empty();
            int index = indexNode.intValue();
            double score = scoreNode.doubleValue();
            if (index < 0 || index >= documents.size() || !seen.add(index)
                    || !Double.isFinite(score)) return Optional.empty();
            parsed.add(new RerankScore(index, score));
        }
        return Optional.of(List.copyOf(parsed));
    }

    private Optional<JsonNode> post(URI endpoint, Map<String, Object> body) {
        try {
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    HttpResponse<String> response = httpClient.send(request,
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    reachable.set(true);
                    if (isRetryable(response.statusCode()) && attempt == 0) continue;
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        return Optional.empty();
                    }
                    return Optional.of(strictReader.readTree(response.body()));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    reachable.set(false);
                    return Optional.empty();
                } catch (IOException exception) {
                    reachable.set(false);
                    if (attempt == 1) return Optional.empty();
                }
            }
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static boolean isRetryable(int status) {
        return status == 408 || status == 429 || status == 500 || status == 502
                || status == 503 || status == 504;
    }

    private static String rerankDocument(OracleEntry entry, String languageCode) {
        if (entry == null) throw new IllegalArgumentException("Unknown archive candidate");
        LocalizedArchiveContent localized = entry.localizations().get(languageCode);
        if (localized == null) localized = entry.localizations().get("en");
        LocalizedArchiveContent english = entry.localizations().get("en");
        if (localized == null || english == null) {
            throw new IllegalArgumentException("Archive candidate lacks required localization");
        }
        return "archive_id=" + entry.id()
                + "\npassage=" + localized.passage()
                + "\ncontext=" + english.contextNote();
    }

    private static List<Double> normalize(List<Double> values) {
        if (values.isEmpty()) return List.of();
        double minimum = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double maximum = values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        if (!Double.isFinite(minimum) || !Double.isFinite(maximum)) return List.of();
        if (maximum - minimum < 1e-12) {
            return values.stream().map(ignored -> 1.0).toList();
        }
        return values.stream().map(value -> clamp((value - minimum) / (maximum - minimum))).toList();
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private record RerankScore(int index, double score) {
    }
}

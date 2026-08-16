package com.literaryoracle;

import java.util.List;

/** Retrieves immutable archive IDs for one transient user input. */
public interface SemanticRetriever {
    RetrievalResult retrieve(String input, String languageCode, List<OracleEntry> availableEntries);

    default SemanticStatus status() {
        return new SemanticStatus(false, false, JinaSemanticRetriever.EMBEDDING_MODEL,
                JinaSemanticRetriever.RERANKER_MODEL, false, "NOT_CONFIGURED");
    }

    enum SemanticMode {
        JINA_RERANKED,
        JINA_EMBEDDING_ONLY,
        LOCAL_FALLBACK
    }

    record RankedCandidate(long id, double score) {
    }

    record RetrievalResult(SemanticMode mode, List<RankedCandidate> candidates) {
        public RetrievalResult {
            mode = mode == null ? SemanticMode.LOCAL_FALLBACK : mode;
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        static RetrievalResult localFallback() {
            return new RetrievalResult(SemanticMode.LOCAL_FALLBACK, List.of());
        }
    }

    record SemanticStatus(
            boolean configured,
            boolean reachable,
            String embeddingModel,
            String rerankerModel,
            boolean archiveEmbeddingsReady,
            String lastCallStatus) {
    }
}

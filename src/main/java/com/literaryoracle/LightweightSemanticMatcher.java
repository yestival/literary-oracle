package com.literaryoracle;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A local, lightweight semantic index. Each target language has its own text
 * corpus, so a query is compared only with the corresponding translations,
 * work titles, and context notes.
 */
final class LightweightSemanticMatcher {
    private static final double BM25_K1 = 1.2;
    private static final double BM25_B = 0.75;
    private static final double BM25_TEXT_WEIGHT = 0.65;
    private static final double COSINE_TEXT_WEIGHT = 0.25;
    private static final double PHRASE_TEXT_WEIGHT = 0.10;
    private static final int MAX_QUERY_TERMS = 128;
    private static final int MAX_QUERY_PHRASES = 48;

    private static final Set<String> ENGLISH_STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "been", "being", "but", "by",
            "can", "could", "did", "do", "does", "for", "from", "had", "has", "have",
            "he", "her", "hers", "him", "his", "how", "i", "if", "in", "into", "is",
            "it", "its", "me", "my", "of", "on", "or", "our", "ours", "she", "so",
            "than", "that", "the", "their", "theirs", "them", "then", "there", "these",
            "they", "this", "those", "to", "too", "us", "was", "we", "were", "what",
            "when", "where", "which", "who", "why", "will", "with", "would", "you",
            "your", "yours", "m", "re", "s", "t", "ve");

    private static final Set<String> GREETING_ONLY_INPUTS = Set.of(
            "hello", "hello there", "hi", "hey", "good morning", "good evening",
            "hola", "bonjour", "ciao", "ola", "hallo", "selam", "halo",
            "你好", "您好", "嗨", "こんにちは", "안녕하세요", "привет", "مرحبا",
            "नमस्ते", "নমস্কার", "สวัสดี", "xin chao");

    private static final Set<String> LATIN_FOLDING_LANGUAGES = Set.of(
            "de", "en", "es", "fr", "id", "it", "pt", "tr", "vi");

    private final List<OracleEntry> archive;
    private final Set<String> languageCodes;
    private final Map<String, LanguageIndex> languageIndices = new ConcurrentHashMap<>();

    LightweightSemanticMatcher(List<OracleEntry> archive) {
        this.archive = List.copyOf(archive);
        Set<String> languageCodes = new LinkedHashSet<>();
        archive.forEach(entry -> languageCodes.addAll(entry.localizations().keySet()));
        this.languageCodes = Set.copyOf(languageCodes);
    }

    LocalAnalysis analyze(String text, String languageCode, List<String> lexicalThemes,
            List<OracleEntry> available) {
        String effectiveLanguage = languageCodes.contains(languageCode) ? languageCode : "en";
        LanguageIndex index = languageIndices.computeIfAbsent(effectiveLanguage,
                code -> buildLanguageIndex(archive, code));
        Query query = query(text, effectiveLanguage, index);
        boolean meaningfulInput = meaningful(query);
        if (!meaningfulInput) {
            return new LocalAnalysis(false, zeroScores(available), List.of());
        }

        Map<Long, Double> textSimilarities = textSimilarities(query, index, available);
        ThemeInference themes = inferThemes(query, index, lexicalThemes);
        return new LocalAnalysis(!themes.themes().isEmpty(),
                Map.copyOf(textSimilarities), themes.scoredThemes());
    }

    private LanguageIndex buildLanguageIndex(List<OracleEntry> archive, String languageCode) {
        Map<Long, MutableDocument> mutableDocuments = new LinkedHashMap<>();
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (OracleEntry entry : archive) {
            LocalizedArchiveContent localized = entry.localizations().get(languageCode);
            if (localized == null) continue;
            MutableDocument document = new MutableDocument();
            addText(document, localized.passage(), languageCode, 1.0);
            addText(document, localized.workTitle(), languageCode, 1.45);
            addText(document, localized.contextNote(), languageCode, 1.25);
            mutableDocuments.put(entry.id(), document);
            document.termFrequency.keySet().forEach(term ->
                    documentFrequency.merge(term, 1, Integer::sum));
        }

        int documentCount = Math.max(1, mutableDocuments.size());
        Map<String, Double> idf = new HashMap<>();
        documentFrequency.forEach((term, frequency) -> idf.put(term,
                Math.log(1.0 + (documentCount - frequency + 0.5) / (frequency + 0.5))));
        double averageLength = mutableDocuments.values().stream()
                .mapToDouble(document -> document.length).average().orElse(1.0);

        Map<Long, IndexedDocument> documents = new LinkedHashMap<>();
        mutableDocuments.forEach((id, document) -> documents.put(id,
                new IndexedDocument(Map.copyOf(document.termFrequency), document.length,
                        document.searchText.toString(), vectorNorm(document.termFrequency, idf))));

        Map<String, MutableDocument> mutableThemeProfiles = new LinkedHashMap<>();
        ThemeDetector.THEME_KEYS.forEach(theme ->
                mutableThemeProfiles.put(theme, new MutableDocument()));
        for (OracleEntry entry : archive) {
            MutableDocument document = mutableDocuments.get(entry.id());
            if (document == null) continue;
            for (String theme : entry.themes()) {
                MutableDocument profile = mutableThemeProfiles.get(theme);
                if (profile != null) profile.merge(document);
            }
        }
        Map<String, IndexedDocument> themeProfiles = new LinkedHashMap<>();
        mutableThemeProfiles.forEach((theme, profile) -> themeProfiles.put(theme,
                new IndexedDocument(Map.copyOf(profile.termFrequency), profile.length, "",
                        vectorNorm(profile.termFrequency, idf))));
        return new LanguageIndex(Map.copyOf(documents), Map.copyOf(themeProfiles),
                Map.copyOf(idf), averageLength);
    }

    private Map<Long, Double> textSimilarities(Query query, LanguageIndex index,
            List<OracleEntry> available) {
        Set<Long> availableIds = available.stream().map(OracleEntry::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<RawTextScore> rawScores = new ArrayList<>(available.size());
        double bestBm25 = 0;
        for (Map.Entry<Long, IndexedDocument> indexed : index.documents().entrySet()) {
            IndexedDocument document = indexed.getValue();
            double bm25 = bm25(query.termFrequency(), document, index);
            bestBm25 = Math.max(bestBm25, bm25);
            if (availableIds.contains(indexed.getKey())) {
                double cosine = cosine(query, document, index.inverseDocumentFrequency());
                double phrase = phraseScore(query.phrases(), document.searchText());
                rawScores.add(new RawTextScore(indexed.getKey(), bm25, cosine, phrase));
            }
        }

        Map<Long, Double> similarities = new HashMap<>();
        for (RawTextScore raw : rawScores) {
            double normalizedBm25 = bestBm25 == 0 ? 0 : raw.bm25() / bestBm25;
            double similarity = BM25_TEXT_WEIGHT * normalizedBm25
                    + COSINE_TEXT_WEIGHT * raw.cosine()
                    + PHRASE_TEXT_WEIGHT * raw.phrase();
            similarities.put(raw.id(), clamp(similarity));
        }
        available.forEach(entry -> similarities.putIfAbsent(entry.id(), 0.0));
        return Map.copyOf(similarities);
    }

    private ThemeInference inferThemes(Query query, LanguageIndex index,
            List<String> lexicalThemes) {
        Map<String, Double> textEvidence = new LinkedHashMap<>();
        ThemeDetector.THEME_KEYS.forEach(theme -> textEvidence.put(theme,
                cosine(query, index.themeProfiles().get(theme),
                        index.inverseDocumentFrequency())));
        normalizeValues(textEvidence);

        Set<String> lexical = lexicalThemes == null ? Set.of()
                : lexicalThemes.stream().filter(ThemeDetector.THEME_KEYS::contains)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<String, Double> combined = new LinkedHashMap<>();
        for (String theme : ThemeDetector.THEME_KEYS) {
            double score = !lexical.isEmpty()
                    ? 0.80 * (lexical.contains(theme) ? 1.0 : 0.0)
                            + 0.20 * textEvidence.get(theme)
                    : textEvidence.get(theme);
            combined.put(theme, score);
        }

        List<ThemeWeight> selected = combined.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .sorted((left, right) -> {
                    int byScore = Double.compare(right.getValue(), left.getValue());
                    if (byScore != 0) return byScore;
                    return Integer.compare(ThemeDetector.THEME_KEYS.indexOf(left.getKey()),
                            ThemeDetector.THEME_KEYS.indexOf(right.getKey()));
                })
                .limit(3)
                .map(entry -> new ThemeWeight(entry.getKey(), entry.getValue()))
                .toList();
        return new ThemeInference(selected);
    }

    private static void normalizeValues(Map<String, Double> values) {
        double maximum = values.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        if (maximum <= 0) return;
        values.replaceAll((ignored, value) -> value / maximum);
    }

    private Query query(String text, String languageCode, LanguageIndex index) {
        String normalized = normalizeForPhrase(text, languageCode);
        List<String> tokens = tokenizeNormalized(normalized, languageCode);
        if (tokens.size() > MAX_QUERY_TERMS) tokens = List.copyOf(tokens.subList(0, MAX_QUERY_TERMS));
        Map<String, Double> terms = new HashMap<>();
        tokens.forEach(token -> terms.merge(token, 1.0, Double::sum));
        boolean containsLetters = normalized.codePoints().filter(Character::isLetter).count() >= 4;
        return new Query(Map.copyOf(terms), phrases(normalized),
                vectorNorm(terms, index.inverseDocumentFrequency()), normalized,
                List.copyOf(tokens), containsLetters);
    }

    private static boolean meaningful(Query query) {
        if (GREETING_ONLY_INPUTS.contains(query.normalized())) return false;
        long distinctTerms = query.tokens().stream().distinct().count();
        boolean substantialSingleTerm = distinctTerms == 1 && query.tokens().stream()
                .findFirst().map(token -> token.codePointCount(0, token.length()) >= 4).orElse(false);
        return query.containsLetters() && (distinctTerms >= 2 || substantialSingleTerm);
    }

    private static double bm25(Map<String, Double> query, IndexedDocument document,
            LanguageIndex index) {
        double score = 0;
        double lengthRatio = document.length() / Math.max(1.0, index.averageDocumentLength());
        for (Map.Entry<String, Double> queryTerm : query.entrySet()) {
            double termFrequency = document.termFrequency().getOrDefault(queryTerm.getKey(), 0.0);
            if (termFrequency == 0) continue;
            double idf = index.inverseDocumentFrequency().getOrDefault(queryTerm.getKey(), 0.0);
            double saturation = termFrequency * (BM25_K1 + 1.0)
                    / (termFrequency + BM25_K1 * (1.0 - BM25_B + BM25_B * lengthRatio));
            score += idf * saturation * (1.0 + Math.log(queryTerm.getValue()));
        }
        return score;
    }

    private static double cosine(Query query, IndexedDocument document, Map<String, Double> idf) {
        if (query.vectorNorm() == 0 || document.vectorNorm() == 0) return 0;
        double dot = 0;
        for (Map.Entry<String, Double> queryTerm : query.termFrequency().entrySet()) {
            double documentFrequency = document.termFrequency().getOrDefault(queryTerm.getKey(), 0.0);
            if (documentFrequency == 0) continue;
            double termIdf = idf.getOrDefault(queryTerm.getKey(), 0.0);
            double queryWeight = (1.0 + Math.log(queryTerm.getValue())) * termIdf;
            double documentWeight = (1.0 + Math.log(documentFrequency)) * termIdf;
            dot += queryWeight * documentWeight;
        }
        return clamp(dot / (query.vectorNorm() * document.vectorNorm()));
    }

    private static double phraseScore(List<String> phrases, String documentText) {
        if (phrases.isEmpty()) return 0;
        long matched = phrases.stream().filter(phrase -> containsPhrase(documentText, phrase)).count();
        return matched / (double) phrases.size();
    }

    private static void addText(MutableDocument document, String text, String languageCode,
            double fieldWeight) {
        if (text == null || text.isBlank()) return;
        String normalized = normalizeForPhrase(text, languageCode);
        if (normalized.isBlank()) return;
        tokenizeNormalized(normalized, languageCode)
                .forEach(token -> document.add(token, fieldWeight));
        document.searchText.append(' ').append(normalized).append(' ').append('\u0000');
    }

    private static List<String> tokenizeNormalized(String normalized, String languageCode) {
        if (normalized == null || normalized.isBlank()) return List.of();
        List<String> tokens = new ArrayList<>();
        for (String raw : normalized.split(" ")) {
            if (raw.isBlank()) continue;
            String token = "en".equals(languageCode) ? stemEnglish(raw) : raw;
            if (token.isBlank() || ("en".equals(languageCode) && ENGLISH_STOP_WORDS.contains(token))) {
                continue;
            }
            int[] codePoints = token.codePoints().toArray();
            if (containsDenseScript(codePoints) && codePoints.length > 1) {
                if (codePoints.length <= 8) tokens.add(token);
                for (int index = 0; index + 1 < codePoints.length; index++) {
                    tokens.add(new String(codePoints, index, 2));
                }
            } else {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static String normalizeForPhrase(String text, String languageCode) {
        if (text == null) return "";
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace('\u2019', '\'').replace('\u2018', '\'').replace('\u02bc', '\'');
        if ("en".equals(languageCode)) {
            normalized = normalized.replace("won't", "will not")
                    .replace("can't", "can not")
                    .replaceAll("(?U)\\b([a-z]+)n't\\b", "$1 not");
        }
        if (LATIN_FOLDING_LANGUAGES.contains(languageCode)) {
            normalized = Normalizer.normalize(normalized, Normalizer.Form.NFKD)
                    .replaceAll("\\p{M}+", "");
        }
        return normalized
                .replaceAll("[^\\p{L}\\p{M}\\p{N}]+", " ")
                .replaceAll("\\s+", " ").strip();
    }

    private static String stemEnglish(String token) {
        return regularEnglishStem(token);
    }

    private static String regularEnglishStem(String token) {
        if (token.length() > 5 && token.endsWith("ies")) return token.substring(0, token.length() - 3) + "y";
        if (token.length() > 5 && token.endsWith("ing")) return undouble(token.substring(0, token.length() - 3));
        if (token.length() > 4 && token.endsWith("ed")) return undouble(token.substring(0, token.length() - 2));
        if (token.length() > 5 && token.endsWith("ly")) return token.substring(0, token.length() - 2);
        if (token.length() > 4 && token.endsWith("es")) return token.substring(0, token.length() - 2);
        if (token.length() > 3 && token.endsWith("s") && !token.endsWith("ss")) {
            return token.substring(0, token.length() - 1);
        }
        return token;
    }

    private static String undouble(String value) {
        if (value.length() < 3) return value;
        int last = value.length() - 1;
        return value.charAt(last) == value.charAt(last - 1) ? value.substring(0, last) : value;
    }

    private static List<String> phrases(String normalized) {
        if (normalized.isBlank()) return List.of();
        String[] words = normalized.split(" ");
        LinkedHashSet<String> phrases = new LinkedHashSet<>();
        if (words.length == 1 && containsDenseScript(words[0].codePoints().toArray())) {
            int[] points = words[0].codePoints().toArray();
            for (int size = Math.min(4, points.length); size >= 2; size--) {
                for (int start = 0; start + size <= points.length; start++) {
                    phrases.add(new String(points, start, size));
                    if (phrases.size() >= MAX_QUERY_PHRASES) return List.copyOf(phrases);
                }
            }
        } else {
            for (int size = Math.min(4, words.length); size >= 2; size--) {
                for (int start = 0; start + size <= words.length; start++) {
                    phrases.add(String.join(" ", Arrays.copyOfRange(words, start, start + size)));
                    if (phrases.size() >= MAX_QUERY_PHRASES) return List.copyOf(phrases);
                }
            }
        }
        return List.copyOf(phrases);
    }

    private static boolean containsPhrase(String text, String phrase) {
        if (text == null || phrase == null || phrase.isBlank()) return false;
        if (!phrase.contains(" ") && containsDenseScript(phrase.codePoints().toArray())) {
            return text.contains(phrase);
        }
        return (" " + text + " ").contains(" " + phrase + " ");
    }

    private static boolean containsDenseScript(int[] codePoints) {
        return Arrays.stream(codePoints).mapToObj(Character.UnicodeScript::of).anyMatch(script ->
                script == Character.UnicodeScript.HAN
                        || script == Character.UnicodeScript.HIRAGANA
                        || script == Character.UnicodeScript.KATAKANA
                        || script == Character.UnicodeScript.HANGUL
                        || script == Character.UnicodeScript.THAI);
    }

    private static double vectorNorm(Map<String, Double> termFrequency, Map<String, Double> idf) {
        double squared = 0;
        for (Map.Entry<String, Double> term : termFrequency.entrySet()) {
            double weight = (1.0 + Math.log(term.getValue())) * idf.getOrDefault(term.getKey(), 0.0);
            squared += weight * weight;
        }
        return Math.sqrt(squared);
    }

    private static Map<Long, Double> zeroScores(List<OracleEntry> entries) {
        Map<Long, Double> scores = new HashMap<>();
        entries.forEach(entry -> scores.put(entry.id(), 0.0));
        return Map.copyOf(scores);
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    record LocalAnalysis(boolean meaningful, Map<Long, Double> textSimilarities,
            List<ScoredTheme> inferredThemes) {
        double textSimilarityFor(long id) {
            return textSimilarities.getOrDefault(id, 0.0);
        }
    }

    private record LanguageIndex(Map<Long, IndexedDocument> documents,
            Map<String, IndexedDocument> themeProfiles,
            Map<String, Double> inverseDocumentFrequency, double averageDocumentLength) {
    }

    private record Query(Map<String, Double> termFrequency, List<String> phrases,
            double vectorNorm, String normalized, List<String> tokens,
            boolean containsLetters) {
    }

    private record IndexedDocument(Map<String, Double> termFrequency, double length,
            String searchText, double vectorNorm) {
    }

    private record RawTextScore(long id, double bm25, double cosine, double phrase) {
    }

    private record ThemeWeight(String key, double weight) {
    }

    private record ThemeInference(List<ThemeWeight> themes) {
        List<ScoredTheme> scoredThemes() {
            return themes.stream().map(theme -> new ScoredTheme(
                    theme.key(), clamp(theme.weight()))).toList();
        }
    }

    record ScoredTheme(String key, double score) {
    }

    private static final class MutableDocument {
        private final Map<String, Double> termFrequency = new HashMap<>();
        private final StringBuilder searchText = new StringBuilder();
        private double length;

        private void add(String term, double weight) {
            termFrequency.merge(term, weight, Double::sum);
            length += weight;
        }

        private void merge(MutableDocument other) {
            other.termFrequency.forEach((term, weight) ->
                    termFrequency.merge(term, weight, Double::sum));
            length += other.length;
        }
    }
}

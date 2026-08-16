package com.literaryoracle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class OracleService {
    private static final double JINA_RELEVANCE_WEIGHT = 0.90;
    private static final double JINA_THEME_AUXILIARY_WEIGHT = 0.06;
    private static final double JINA_TEXT_AUXILIARY_WEIGHT = 0.04;
    private static final double LOCAL_THEME_WEIGHT = 0.90;
    private static final double LOCAL_TEXT_WEIGHT = 0.10;
    private static final double RELATED_THEME_STRENGTH = 0.18;
    private static final Map<String, List<String>> RELATED_THEMES = relatedThemes();
    private final ThemeDetector themeDetector;
    private final List<OracleEntry> archive;
    private final DoubleSupplier randomSource;
    private final LanguageDetectionService languageDetectionService;
    private final SupportedLanguageCatalog languageCatalog;
    private final LightweightSemanticMatcher semanticMatcher;
    private final SemanticRetriever semanticRetriever;
    private final SafetyAssessmentService safetyAssessmentService;

    @Autowired
    public OracleService(ThemeDetector themeDetector, ArchiveRepository archiveRepository,
            LanguageDetectionService languageDetectionService,
            SupportedLanguageCatalog languageCatalog,
            SemanticRetriever semanticRetriever,
            SafetyAssessmentService safetyAssessmentService) {
        this(themeDetector, archiveRepository.entries(),
                () -> ThreadLocalRandom.current().nextDouble(), languageDetectionService,
                languageCatalog, semanticRetriever, safetyAssessmentService);
    }

    OracleService(ThemeDetector themeDetector, List<OracleEntry> archive,
            DoubleSupplier randomSource, LanguageDetectionService languageDetectionService,
            SupportedLanguageCatalog languageCatalog,
            SemanticRetriever semanticRetriever,
            SafetyAssessmentService safetyAssessmentService) {
        this.themeDetector = themeDetector;
        this.archive = List.copyOf(archive);
        this.randomSource = randomSource;
        this.languageDetectionService = languageDetectionService;
        this.languageCatalog = languageCatalog;
        this.semanticRetriever = semanticRetriever;
        this.safetyAssessmentService = safetyAssessmentService;
        this.semanticMatcher = new LightweightSemanticMatcher(this.archive);
    }

    public SafetyAssessmentService.SafetyAssessment assessSafety(String text) {
        return safetyAssessmentService.assess(text);
    }

    public LanguageDetectionService.LanguageResolution resolveLanguage(String text,
            String requestedLanguage, String browserLanguage) {
        return languageDetectionService.resolve(text, requestedLanguage, browserLanguage);
    }

    public OracleSelection select(String text, int chance, List<Long> excludedIds) {
        return select(text, chance, excludedIds, "auto", null);
    }

    public OracleSelection select(String text, int chance, List<Long> excludedIds,
            String requestedLanguage, String browserLanguage) {
        LanguageDetectionService.LanguageResolution language = languageDetectionService.resolve(
                text, requestedLanguage, browserLanguage);
        SafetyAssessmentService.SafetyAssessment assessment = assessSafety(text);
        return selectAfterSafetyAssessment(text, chance, excludedIds, language, assessment);
    }

    OracleSelection selectAfterSafetyAssessment(String text, int chance, List<Long> excludedIds,
            LanguageDetectionService.LanguageResolution language,
            SafetyAssessmentService.SafetyAssessment assessment) {
        if (assessment == null
                || assessment == SafetyAssessmentService.SafetyAssessment.IMMEDIATE_RISK) {
            throw new IllegalArgumentException(
                    "Crisis input must be handled before literary selection");
        }
        if (archive.isEmpty()) throw new IllegalStateException("The literary archive is empty");

        Set<Long> excluded = excludedIds == null ? Set.of() : new HashSet<>(excludedIds);
        List<OracleEntry> available = archive.stream()
                .filter(entry -> !excluded.contains(entry.id())).toList();
        if (available.isEmpty()) available = archive;

        List<String> lexicalThemes = themeDetector.detect(text, language.code());
        LightweightSemanticMatcher.LocalAnalysis localAnalysis = semanticMatcher.analyze(
                text, language.code(), lexicalThemes, available);
        SemanticRetriever.RetrievalResult retrieval = retrieveSafely(
                text, language.code(), available);

        SemanticRetriever.SemanticMode semanticMode = retrieval.mode();
        List<String> matchedThemes;
        List<ScoredEntry> ranked;
        boolean hasRelevanceSignal;
        if (semanticMode != SemanticRetriever.SemanticMode.LOCAL_FALLBACK) {
            matchedThemes = lexicalThemes;
            ranked = rankJina(retrieval.candidates(), available, matchedThemes, localAnalysis);
            if (ranked.isEmpty()) {
                semanticMode = SemanticRetriever.SemanticMode.LOCAL_FALLBACK;
                matchedThemes = localAnalysis.inferredThemes().stream()
                        .map(LightweightSemanticMatcher.ScoredTheme::key).toList();
                ranked = rankLocal(available, localAnalysis.inferredThemes(), localAnalysis);
                hasRelevanceSignal = localAnalysis.meaningful()
                        && ranked.stream().anyMatch(item -> item.semanticScore() > 0);
            } else {
                hasRelevanceSignal = true;
            }
        } else {
            matchedThemes = localAnalysis.inferredThemes().stream()
                    .map(LightweightSemanticMatcher.ScoredTheme::key).toList();
            ranked = rankLocal(available, localAnalysis.inferredThemes(), localAnalysis);
            hasRelevanceSignal = localAnalysis.meaningful()
                    && ranked.stream().anyMatch(item -> item.semanticScore() > 0);
        }

        int poolSize;
        if (semanticMode != SemanticRetriever.SemanticMode.LOCAL_FALLBACK) {
            poolSize = candidatePoolSize(chance, ranked.size());
        } else if (hasRelevanceSignal) {
            long relevantCount = ranked.stream()
                    .filter(item -> item.semanticScore() > 0).count();
            poolSize = Math.min(candidatePoolSize(chance, ranked.size()), (int) relevantCount);
        } else {
            poolSize = ranked.size();
        }

        double semanticWeight = hasRelevanceSignal ? semanticWeight(chance) : 0.0;
        double randomWeight = hasRelevanceSignal ? randomWeight(chance) : 1.0;
        ScoredEntry winner = ranked.stream().limit(poolSize)
                .map(item -> new ScoredEntry(item.entry(), item.semanticScore(),
                        item.semanticScore() * semanticWeight
                                + randomSource.getAsDouble() * randomWeight))
                .max(Comparator.comparingDouble(ScoredEntry::finalScore)).orElseThrow();

        OracleEntry entry = winner.entry();
        LocalizedArchiveContent localized = entry.localizations().get(language.code());
        if (localized == null) {
            throw new IllegalStateException("Archive entry " + entry.id()
                    + " has no localization for " + language.code());
        }
        boolean originalIsDisplayLanguage = entry.originalLanguage().equals(language.code());
        String displayText = originalIsDisplayLanguage ? entry.passageOriginal() : localized.passage();
        boolean showOriginalSeparately = !samePassage(displayText, entry.passageOriginal());
        SupportedLanguage originalLanguage = languageCatalog.requireAny(entry.originalLanguage());

        return new OracleSelection(entry, localized, displayText, language, originalLanguage,
                showOriginalSeparately, matchedThemes,
                themeDetector.localize(matchedThemes, language.code()), chanceLevel(chance), poolSize,
                winner.semanticScore(), winner.finalScore(), semanticMode);
    }

    static int candidatePoolSize(int chance, int availableCount) {
        int requested = (int) Math.round(3 + normalizedChance(chance) * 22);
        return Math.min(requested, availableCount);
    }

    static double semanticWeight(int chance) {
        return 0.95 - 0.75 * normalizedChance(chance);
    }

    static double randomWeight(int chance) {
        return 0.05 + 0.75 * normalizedChance(chance);
    }

    static double jinaRelevanceScore(double jinaScore, double themeScore,
            double textSimilarity) {
        return clamp(JINA_RELEVANCE_WEIGHT * clamp(jinaScore)
                + JINA_THEME_AUXILIARY_WEIGHT * clamp(themeScore)
                + JINA_TEXT_AUXILIARY_WEIGHT * clamp(textSimilarity));
    }

    static double localRelevanceScore(double themeScore, double textSimilarity) {
        if (themeScore <= 0) return 0;
        return clamp(LOCAL_THEME_WEIGHT * themeScore + LOCAL_TEXT_WEIGHT * textSimilarity);
    }

    private static double normalizedChance(int chance) {
        return Math.max(0, Math.min(100, chance)) / 100.0;
    }

    private SemanticRetriever.RetrievalResult retrieveSafely(String text, String languageCode,
            List<OracleEntry> available) {
        try {
            SemanticRetriever.RetrievalResult result = semanticRetriever.retrieve(
                    text, languageCode, available);
            return result == null ? SemanticRetriever.RetrievalResult.localFallback() : result;
        } catch (RuntimeException exception) {
            return SemanticRetriever.RetrievalResult.localFallback();
        }
    }

    private List<ScoredEntry> rankJina(List<SemanticRetriever.RankedCandidate> candidates,
            List<OracleEntry> available, List<String> lexicalThemes,
            LightweightSemanticMatcher.LocalAnalysis localAnalysis) {
        Map<Long, OracleEntry> availableById = new HashMap<>();
        available.forEach(entry -> availableById.put(entry.id(), entry));
        Set<Long> seen = new HashSet<>();
        List<ScoredEntry> ranked = new ArrayList<>();
        for (SemanticRetriever.RankedCandidate candidate : candidates) {
            if (candidate == null || !Double.isFinite(candidate.score())
                    || !seen.add(candidate.id())) continue;
            OracleEntry entry = availableById.get(candidate.id());
            if (entry == null) continue;
            ranked.add(new ScoredEntry(entry, jinaRelevanceScore(candidate.score(),
                    lexicalThemeScore(lexicalThemes, entry.themes()),
                    localAnalysis.textSimilarityFor(entry.id())), 0));
        }
        ranked.sort(Comparator.comparingDouble(ScoredEntry::semanticScore).reversed()
                .thenComparingLong(item -> item.entry().id()));
        return List.copyOf(ranked);
    }

    private List<ScoredEntry> rankLocal(List<OracleEntry> entries,
            List<LightweightSemanticMatcher.ScoredTheme> themes,
            LightweightSemanticMatcher.LocalAnalysis localAnalysis) {
        return entries.stream()
                .map(entry -> new ScoredEntry(entry, localRelevanceScore(
                        weightedThemeScore(themes, entry.themes()),
                        localAnalysis.textSimilarityFor(entry.id())), 0))
                .sorted(Comparator.comparingDouble(ScoredEntry::semanticScore).reversed()
                        .thenComparingLong(item -> item.entry().id()))
                .toList();
    }

    private double weightedThemeScore(List<LightweightSemanticMatcher.ScoredTheme> inferred,
            List<String> entryThemes) {
        double total = inferred.stream().mapToDouble(
                LightweightSemanticMatcher.ScoredTheme::score).sum();
        if (total <= 0) return 0;
        double matched = 0;
        for (LightweightSemanticMatcher.ScoredTheme theme : inferred) {
            matched += themeStrength(theme.key(), entryThemes) * theme.score();
        }
        return clamp(matched / total);
    }

    private double lexicalThemeScore(List<String> inferred, List<String> entryThemes) {
        if (inferred == null || inferred.isEmpty()) return 0;
        return clamp(inferred.stream().mapToDouble(theme ->
                themeStrength(theme, entryThemes)).sum() / inferred.size());
    }

    private double themeStrength(String theme, List<String> entryThemes) {
        if (entryThemes.contains(theme)) return 1.0;
        return RELATED_THEMES.getOrDefault(theme, List.of()).stream()
                .anyMatch(entryThemes::contains) ? RELATED_THEME_STRENGTH : 0.0;
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static Map<String, List<String>> relatedThemes() {
        return Map.ofEntries(
                Map.entry("uncertainty", List.of("change", "courage")),
                Map.entry("change", List.of("uncertainty", "hope")),
                Map.entry("loneliness", List.of("belonging", "self-worth")),
                Map.entry("boundaries", List.of("courage", "self-worth")),
                Map.entry("courage", List.of("hope", "freedom")),
                Map.entry("grief", List.of("love", "hope")),
                Map.entry("rest", List.of("time", "nature")),
                Map.entry("identity", List.of("self-worth", "freedom")),
                Map.entry("hope", List.of("courage", "change")),
                Map.entry("belonging", List.of("loneliness", "love")),
                Map.entry("love", List.of("belonging", "joy")),
                Map.entry("time", List.of("change", "rest")),
                Map.entry("mortality", List.of("time", "meaning")),
                Map.entry("freedom", List.of("courage", "identity")),
                Map.entry("meaning", List.of("identity", "hope")),
                Map.entry("self-worth", List.of("identity", "courage")),
                Map.entry("nature", List.of("rest", "joy")),
                Map.entry("joy", List.of("hope", "love")));
    }

    private String chanceLevel(int chance) {
        if (chance < 34) return "MEANING_LED";
        if (chance < 67) return "BALANCED";
        return "CHANCE_LED";
    }

    private boolean samePassage(String first, String second) {
        return first != null && second != null && first.strip().equals(second.strip());
    }

    public record OracleSelection(
            OracleEntry entry,
            LocalizedArchiveContent localized,
            String displayText,
            LanguageDetectionService.LanguageResolution displayLanguage,
            SupportedLanguage originalLanguage,
            boolean showOriginalSeparately,
            List<String> matchedThemes,
            List<ThemeDetector.LocalizedTheme> localizedThemes,
            String chanceLevel,
            int candidatePoolSize,
            double semanticScore,
            double finalScore,
            SemanticRetriever.SemanticMode semanticMode) {
    }

    private record ScoredEntry(OracleEntry entry, double semanticScore, double finalScore) {
    }
}

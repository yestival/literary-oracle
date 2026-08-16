package com.literaryoracle;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

@Component
public class ThemeDetector {
    static final String LEXICON_RESOURCE = "theme-lexicon.json";
    public static final List<String> THEME_KEYS = List.of(
            "uncertainty", "change", "loneliness", "boundaries", "courage",
            "grief", "rest", "identity", "hope", "belonging", "love", "time",
            "mortality", "freedom", "meaning", "self-worth", "nature", "joy");

    private final Map<String, Map<String, ThemeDefinition>> languages;

    @Autowired
    public ThemeDetector(ObjectMapper objectMapper, SupportedLanguageCatalog languageCatalog) {
        this(load(objectMapper, LEXICON_RESOURCE), languageCatalog);
    }

    ThemeDetector(ThemeLexicon lexicon, SupportedLanguageCatalog languageCatalog) {
        validate(lexicon, languageCatalog);
        Map<String, Map<String, ThemeDefinition>> copied = new LinkedHashMap<>();
        lexicon.languages().forEach((code, definitions) -> copied.put(code, Map.copyOf(definitions)));
        this.languages = Map.copyOf(copied);
    }

    /**
     * Compatibility method used by older callers. It searches the complete
     * multilingual lexicon while still returning stable English theme keys.
     */
    public List<String> detect(String text) {
        if (text == null || text.isBlank()) return List.of();
        Map<String, Integer> scores = new LinkedHashMap<>();
        THEME_KEYS.forEach(key -> scores.put(key, 0));
        languages.values().forEach(definitions -> addScores(text, definitions, scores));
        return ranked(scores);
    }

    public List<String> detect(String text, String languageCode) {
        if (text == null || text.isBlank()) return List.of();
        Map<String, ThemeDefinition> definitions = languages.get(languageCode);
        if (definitions == null) definitions = languages.get("en");
        Map<String, Integer> scores = new LinkedHashMap<>();
        THEME_KEYS.forEach(key -> scores.put(key, 0));
        addScores(text, definitions, scores);
        return ranked(scores);
    }

    public List<LocalizedTheme> localize(List<String> themeKeys, String languageCode) {
        if (themeKeys == null || themeKeys.isEmpty()) return List.of();
        Map<String, ThemeDefinition> definitions = languages.get(languageCode);
        if (definitions == null) definitions = languages.get("en");
        Map<String, ThemeDefinition> selected = definitions;
        return themeKeys.stream()
                .filter(THEME_KEYS::contains)
                .map(key -> new LocalizedTheme(key, selected.get(key).label()))
                .toList();
    }

    public String label(String themeKey, String languageCode) {
        Map<String, ThemeDefinition> definitions = languages.getOrDefault(languageCode, languages.get("en"));
        ThemeDefinition definition = definitions.get(themeKey);
        return definition == null ? themeKey : definition.label();
    }

    private static void addScores(String text, Map<String, ThemeDefinition> definitions,
            Map<String, Integer> scores) {
        String normalized = text.toLowerCase(Locale.ROOT);
        THEME_KEYS.forEach(theme -> {
            ThemeDefinition definition = definitions.get(theme);
            int score = definition.terms().stream()
                    .map(term -> term.toLowerCase(Locale.ROOT))
                    .mapToInt(term -> occurrences(normalized, term)).sum();
            scores.compute(theme, (ignored, current) -> current + score);
        });
    }

    private static List<String> ranked(Map<String, Integer> scores) {
        List<ThemeScore> found = new ArrayList<>();
        THEME_KEYS.forEach(theme -> {
            int score = scores.getOrDefault(theme, 0);
            if (score > 0) found.add(new ThemeScore(theme, score, THEME_KEYS.indexOf(theme)));
        });
        return found.stream()
                .sorted(Comparator.comparingInt(ThemeScore::score).reversed()
                        .thenComparingInt(ThemeScore::order))
                .limit(3).map(ThemeScore::theme).toList();
    }

    private static int occurrences(String text, String term) {
        if (term.isBlank()) return 0;
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(term, from)) >= 0) {
            if (!requiresWordBoundaries(term) || hasWordBoundaries(text, term, from)) {
                count++;
            }
            from += term.length();
        }
        return count;
    }

    private static boolean requiresWordBoundaries(String term) {
        return term.codePoints().mapToObj(Character.UnicodeScript::of).noneMatch(script ->
                script == Character.UnicodeScript.HAN
                        || script == Character.UnicodeScript.HIRAGANA
                        || script == Character.UnicodeScript.KATAKANA
                        || script == Character.UnicodeScript.HANGUL
                        || script == Character.UnicodeScript.THAI);
    }

    private static boolean hasWordBoundaries(String text, String term, int start) {
        int end = start + term.length();
        boolean startsAtBoundary = start == 0
                || !isWordCharacter(text.codePointBefore(start));
        boolean endsAtBoundary = end == text.length()
                || !isWordCharacter(text.codePointAt(end));
        return startsAtBoundary && endsAtBoundary;
    }

    private static boolean isWordCharacter(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isLetterOrDigit(codePoint)
                || type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK;
    }

    private static void validate(ThemeLexicon lexicon, SupportedLanguageCatalog languageCatalog) {
        if (lexicon == null || lexicon.languages() == null) {
            throw new IllegalStateException("Theme lexicon must contain languages");
        }
        if (!lexicon.languages().keySet().equals(languageCatalog.supportedCodes())) {
            throw new IllegalStateException("Theme lexicon must contain exactly the 19 supported language codes");
        }
        Set<String> expectedThemes = Set.copyOf(THEME_KEYS);
        lexicon.languages().forEach((code, themes) -> {
            if (themes == null || !themes.keySet().equals(expectedThemes)) {
                throw new IllegalStateException("Theme lexicon for " + code
                        + " must contain exactly the 18 stable theme keys");
            }
            themes.forEach((key, definition) -> {
                if (definition == null || isBlank(definition.label()) || definition.terms() == null
                        || definition.terms().isEmpty() || definition.terms().stream().anyMatch(ThemeDetector::isBlank)) {
                    throw new IllegalStateException("Theme " + key + " for " + code
                            + " needs a label and non-empty terms");
                }
            });
        });
    }

    private static ThemeLexicon load(ObjectMapper objectMapper, String resourceName) {
        ClassPathResource resource = new ClassPathResource(resourceName);
        try (InputStream input = resource.getInputStream()) {
            return objectMapper.readValue(input, ThemeLexicon.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load classpath:" + resourceName, exception);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record ThemeLexicon(Map<String, Map<String, ThemeDefinition>> languages) {
    }

    public record ThemeDefinition(String label, List<String> terms) {
    }

    public record LocalizedTheme(String key, String label) {
    }

    private record ThemeScore(String theme, int score, int order) {
    }
}

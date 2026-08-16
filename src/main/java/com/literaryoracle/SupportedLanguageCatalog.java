package com.literaryoracle;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

@Component
public class SupportedLanguageCatalog {
    static final String CONFIG_RESOURCE = "static/config/supported-languages.json";
    static final int EXPECTED_SUPPORTED_LANGUAGE_COUNT = 19;

    private final List<SupportedLanguage> supportedLanguages;
    private final List<SupportedLanguage> originalOnlyLanguages;
    private final Map<String, SupportedLanguage> supportedByCode;
    private final Map<String, SupportedLanguage> allByCode;

    @Autowired
    public SupportedLanguageCatalog(ObjectMapper objectMapper) {
        this(load(objectMapper, CONFIG_RESOURCE));
    }

    SupportedLanguageCatalog(LanguageConfiguration configuration) {
        if (configuration == null) {
            throw new IllegalStateException("Supported-language configuration is missing");
        }
        this.supportedLanguages = List.copyOf(requireLanguages(configuration.languages(), "languages"));
        this.originalOnlyLanguages = List.copyOf(
                requireLanguages(configuration.originalOnlyLanguages(), "originalOnlyLanguages"));
        if (supportedLanguages.size() != EXPECTED_SUPPORTED_LANGUAGE_COUNT) {
            throw new IllegalStateException("Expected 19 supported languages but found "
                    + supportedLanguages.size());
        }

        this.supportedByCode = index(supportedLanguages, "supported");
        Map<String, SupportedLanguage> combined = new LinkedHashMap<>(supportedByCode);
        for (SupportedLanguage language : originalOnlyLanguages) {
            validate(language);
            if (combined.putIfAbsent(language.code(), language) != null) {
                throw new IllegalStateException("Duplicate language code: " + language.code());
            }
        }
        this.allByCode = Map.copyOf(combined);
    }

    public List<SupportedLanguage> supportedLanguages() {
        return supportedLanguages;
    }

    public Set<String> supportedCodes() {
        return supportedByCode.keySet();
    }

    public Set<String> allLanguageCodes() {
        return allByCode.keySet();
    }

    public boolean isSupported(String code) {
        return normalizeSupportedCode(code).isPresent();
    }

    public Optional<SupportedLanguage> findSupported(String code) {
        return normalizeSupportedCode(code).map(supportedByCode::get);
    }

    public SupportedLanguage requireSupported(String code) {
        return findSupported(code)
                .orElseThrow(() -> new IllegalArgumentException("Unsupported language code: " + code));
    }

    public SupportedLanguage requireAny(String code) {
        if (code == null) throw new IllegalArgumentException("Language code must not be null");
        SupportedLanguage language = allByCode.get(code);
        if (language == null) {
            language = allByCode.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(code))
                    .map(Map.Entry::getValue).findFirst().orElse(null);
        }
        if (language == null) throw new IllegalArgumentException("Unknown language code: " + code);
        return language;
    }

    /**
     * Converts a browser locale such as {@code fr-FR}, {@code pt-BR} or
     * {@code zh-TW} to one of the application's supported BCP 47 codes.
     */
    public Optional<String> normalizeSupportedCode(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) return Optional.empty();
        String normalized = rawCode.strip().replace('_', '-');
        String lower = normalized.toLowerCase(Locale.ROOT);

        if (lower.equals("zh-hant") || lower.startsWith("zh-hant-")
                || lower.equals("zh-tw") || lower.startsWith("zh-tw-")
                || lower.equals("zh-hk") || lower.startsWith("zh-hk-")
                || lower.equals("zh-mo") || lower.startsWith("zh-mo-")) {
            return Optional.of("zh-Hant");
        }
        if (lower.equals("zh-hans") || lower.startsWith("zh-hans-")
                || lower.equals("zh-cn") || lower.startsWith("zh-cn-")
                || lower.equals("zh-sg") || lower.startsWith("zh-sg-")
                || lower.equals("zh")) {
            return Optional.of("zh-Hans");
        }

        Optional<String> exact = supportedByCode.keySet().stream()
                .filter(code -> code.equalsIgnoreCase(normalized)).findFirst();
        if (exact.isPresent()) return exact;

        String base = lower.contains("-") ? lower.substring(0, lower.indexOf('-')) : lower;
        return supportedByCode.keySet().stream()
                .filter(code -> code.equalsIgnoreCase(base)).findFirst();
    }

    private static LanguageConfiguration load(ObjectMapper objectMapper, String resourceName) {
        ClassPathResource resource = new ClassPathResource(resourceName);
        try (InputStream input = resource.getInputStream()) {
            return objectMapper.readValue(input, LanguageConfiguration.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load classpath:" + resourceName, exception);
        }
    }

    private static List<SupportedLanguage> requireLanguages(List<SupportedLanguage> languages, String field) {
        if (languages == null) throw new IllegalStateException(field + " must be present");
        languages.forEach(SupportedLanguageCatalog::validate);
        return languages;
    }

    private static Map<String, SupportedLanguage> index(List<SupportedLanguage> languages, String group) {
        Map<String, SupportedLanguage> indexed = new LinkedHashMap<>();
        for (SupportedLanguage language : languages) {
            if (indexed.putIfAbsent(language.code(), language) != null) {
                throw new IllegalStateException("Duplicate " + group + " language code: " + language.code());
            }
        }
        return Map.copyOf(indexed);
    }

    private static void validate(SupportedLanguage language) {
        if (language == null || isBlank(language.code()) || isBlank(language.name())) {
            throw new IllegalStateException("Every language needs a non-empty code and name");
        }
        if (!"ltr".equals(language.direction()) && !"rtl".equals(language.direction())) {
            throw new IllegalStateException("Language " + language.code() + " has invalid direction");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record LanguageConfiguration(List<SupportedLanguage> languages,
            List<SupportedLanguage> originalOnlyLanguages) {
    }
}

package com.literaryoracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import tools.jackson.databind.ObjectMapper;

class ArchiveResourceTests {
    private static final List<String> FIRST_BATCH_AUTHORS = List.of(
            "Emily Dickinson", "Rabindranath Tagore", "Rainer Maria Rilke",
            "Misuzu Kaneko", "Heinrich Heine", "Alexander Pushkin",
            "Fernando Pessoa", "Percy Bysshe Shelley", "William Shakespeare",
            "Fyodor Dostoevsky");
    private static final List<String> SECOND_BATCH_AUTHORS = List.of(
            "Friedrich Nietzsche", "Edith Södergran", "Matsuo Bashō (松尾芭蕉)",
            "Anton Chekhov", "Virginia Woolf", "Ralph Waldo Emerson",
            "Kahlil Gibran", "Walt Whitman", "Charles Baudelaire",
            "Edward Thomas");
    private static final Map<String, Integer> FINAL_BATCH_AUTHOR_COUNTS = Map.ofEntries(
            Map.entry("Federico García Lorca", 15),
            Map.entry("Henry Wadsworth Longfellow", 15),
            Map.entry("Giosuè Carducci", 15),
            Map.entry("César Vallejo", 15),
            Map.entry("Rubén Darío", 15),
            Map.entry("Kobayashi Issa", 15),
            Map.entry("Marina Tsvetaeva", 15),
            Map.entry("Akiko Yosano", 15),
            Map.entry("Li Bai (李白)", 4),
            Map.entry("Li Qingzhao (李清照)", 4),
            Map.entry("Su Shi (苏轼)", 4));
    private static final Set<String> VALID_TYPES = Set.of(
            "poem", "essay", "play", "haiku", "letter", "diary", "novel",
            "novel_in_verse", "novella", "short_story", "prose", "aphorism",
            "other");
    private static final Set<String> POETRY_TYPES = Set.of("poem", "haiku");
    private static final String VERIFIED_PUBLIC_DOMAIN_STATUS =
            "ORIGINAL_PUBLIC_DOMAIN_UK_US_2026; TRANSLATION_PROJECT_AUTHORED";
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "(?i)(^|\\[)\\s*(tbd|todo|placeholder|not yet verified)\\s*(\\]|$)|\\uFFFD");
    private static final Map<String, Pattern> REQUIRED_SCRIPTS = Map.of(
            "zh-Hans", Pattern.compile("\\p{IsHan}"),
            "zh-Hant", Pattern.compile("\\p{IsHan}"),
            "ja", Pattern.compile("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]"),
            "ko", Pattern.compile("\\p{IsHangul}"),
            "ru", Pattern.compile("\\p{IsCyrillic}"),
            "ar", Pattern.compile("\\p{IsArabic}"),
            "hi", Pattern.compile("\\p{IsDevanagari}"),
            "bn", Pattern.compile("\\p{IsBengali}"),
            "th", Pattern.compile("\\p{IsThai}"));

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SupportedLanguageCatalog catalog = new SupportedLanguageCatalog(objectMapper);

    @Test
    void sharedCatalogContainsExactlyTheNineteenTargetCodes() {
        assertEquals(19, catalog.supportedLanguages().size());
        assertEquals(List.of("en", "zh-Hans", "zh-Hant", "ja", "ko", "es", "fr", "de",
                "it", "pt", "ru", "sv", "ar", "hi", "bn", "id", "tr", "vi", "th"),
                catalog.supportedLanguages().stream().map(SupportedLanguage::code).toList());
        assertTrue(catalog.allLanguageCodes().contains("lzh"));
        assertTrue(catalog.allLanguageCodes().containsAll(Set.of("fa", "grc", "la")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void publicArchiveSummaryMatchesTheAuthoritativeResources() throws Exception {
        ArchiveRepository.ArchiveDocument document = readArchiveDocument();
        Map<String, Object> summary;
        try (InputStream input = new ClassPathResource(
                "static/config/archive-summary.json").getInputStream()) {
            summary = objectMapper.readValue(input, Map.class);
        }

        assertEquals(document.entries().size(),
                ((Number) summary.get("passages")).intValue());
        assertEquals(document.authors().size(),
                ((Number) summary.get("authors")).intValue());
        assertEquals(19, catalog.supportedLanguages().size());
    }

    @Test
    void archiveContainsTheCompleteFirstBatchAndPreservesFourLegacyEntries() {
        ArchiveRepository repository = new ArchiveRepository(objectMapper, catalog);
        assertEquals(440, repository.entries().size());
        Map<String, Long> counts = repository.entries().stream()
                .filter(entry -> FIRST_BATCH_AUTHORS.contains(entry.author()))
                .collect(Collectors.groupingBy(OracleEntry::author, Collectors.counting()));
        assertEquals(Set.copyOf(FIRST_BATCH_AUTHORS), counts.keySet());
        FIRST_BATCH_AUTHORS.forEach(author -> assertEquals(15L, counts.get(author),
                () -> author + " must have exactly 15 passages"));
        Map<Long, String> preservedLegacy = repository.entries().stream()
                .filter(entry -> Set.of(4L, 5L, 6L, 7L).contains(entry.id()))
                .collect(Collectors.toMap(OracleEntry::id, OracleEntry::author));
        assertEquals(Map.of(4L, "Walt Whitman", 5L, "Ralph Waldo Emerson",
                6L, "Li Bai (李白)", 7L, "Matsuo Bashō (松尾芭蕉)"), preservedLegacy);
    }

    @Test
    void archiveContainsTheCompleteSecondBatchAndExactlyOneHundredFortySevenNewEntries() {
        ArchiveRepository repository = new ArchiveRepository(objectMapper, catalog);
        Map<String, Long> counts = repository.entries().stream()
                .filter(entry -> SECOND_BATCH_AUTHORS.contains(entry.author()))
                .collect(Collectors.groupingBy(OracleEntry::author, Collectors.counting()));

        assertEquals(Set.copyOf(SECOND_BATCH_AUTHORS), counts.keySet());
        SECOND_BATCH_AUTHORS.forEach(author -> assertEquals(15L, counts.get(author),
                () -> author + " must have exactly 15 passages"));
        assertEquals(147L, repository.entries().stream()
                .filter(entry -> entry.id() > 154L && entry.id() <= 301L).count());
    }

    @Test
    void archiveContainsTheRequestedFinalBatchCountsAndThirtyNineAuthors() throws Exception {
        ArchiveRepository.ArchiveDocument document = readArchiveDocument();
        assertEquals(440, document.entries().size());
        assertEquals(39, document.authors().size());

        Map<String, Long> counts = document.entries().stream()
                .filter(entry -> FINAL_BATCH_AUTHOR_COUNTS.containsKey(entry.author()))
                .collect(Collectors.groupingBy(
                        ArchiveRepository.ArchiveRecord::author, Collectors.counting()));
        assertEquals(FINAL_BATCH_AUTHOR_COUNTS.keySet(), counts.keySet());
        FINAL_BATCH_AUTHOR_COUNTS.forEach((author, expected) -> assertEquals(
                expected.longValue(), counts.get(author),
                () -> author + " has the wrong final passage count"));

        List<ArchiveRepository.ArchiveRecord> batch = document.entries().stream()
                .filter(entry -> FINAL_BATCH_AUTHOR_COUNTS.containsKey(entry.author())).toList();
        Map<String, Long> workCounts = batch.stream().collect(Collectors.groupingBy(
                entry -> entry.author() + "\u0000" + normalized(entry.originalWorkTitle()),
                Collectors.counting()));
        assertTrue(workCounts.values().stream().allMatch(count -> count <= 2),
                () -> "Final batch has more than two passages from one work");
        FINAL_BATCH_AUTHOR_COUNTS.forEach((author, expected) -> assertTrue(batch.stream()
                        .filter(entry -> author.equals(entry.author()))
                        .map(entry -> normalized(entry.originalWorkTitle())).distinct().count()
                        >= Math.min(8, expected),
                () -> author + " lacks representative-work coverage"));
    }

    @Test
    void secondBatchHasCompleteSourcesMetadataTypesThemesAndTranslations() throws Exception {
        ArchiveRepository.ArchiveDocument document = readArchiveDocument();
        List<ArchiveRepository.ArchiveRecord> batch = document.entries().stream()
                .filter(entry -> SECOND_BATCH_AUTHORS.contains(entry.author())).toList();

        assertEquals(150, batch.size());
        SECOND_BATCH_AUTHORS.forEach(author -> {
            assertTrue(document.authors().containsKey(author));
            assertFalse(document.authors().get(author).isBlank());
            assertNoPlaceholder(document.authors().get(author));
        });

        batch.forEach(entry -> {
            assertTrue(entry.year() != null && entry.year() > 0 && entry.year() <= 1930,
                    () -> "Invalid year for entry " + entry.id());
            URI source = URI.create(entry.sourceUrl());
            assertTrue("https".equalsIgnoreCase(source.getScheme())
                            && source.getHost() != null && !source.getHost().isBlank()
                            && source.getPath() != null && !source.getPath().isBlank(),
                    () -> "Entry " + entry.id() + " needs a direct HTTPS source");
            assertFalse(entry.sourceUrl().matches(
                            "(?i).*?(goodreads|brainyquote|azquotes|quotefancy).*"),
                    () -> "Entry " + entry.id() + " uses a quote-aggregation source");
            assertEquals(VERIFIED_PUBLIC_DOMAIN_STATUS, entry.publicDomainStatus());
            assertTrue(VALID_TYPES.contains(entry.type()),
                    () -> "Entry " + entry.id() + " has unsupported type " + entry.type());
            assertTrue(entry.themes().size() >= 1 && entry.themes().size() <= 3);
            assertEquals(entry.themes().size(), entry.themes().stream().distinct().count());
            assertTrue(ThemeDetector.THEME_KEYS.containsAll(entry.themes()));
            assertNoPlaceholder(entry.passageOriginal(), entry.originalWorkTitle(),
                    entry.englishWorkTitle(), entry.englishContextNote(), entry.sourceUrl());
            assertEquals(catalog.supportedCodes(), entry.passages().keySet());

            entry.passages().forEach((code, passage) -> {
                assertFalse(passage.isBlank(),
                        () -> "Entry " + entry.id() + " has blank passage " + code);
                assertNoPlaceholder(passage);
                int lineCount = passage.split("\\R", -1).length;
                assertTrue(lineCount >= 1 && lineCount <= 8,
                        () -> "Entry " + entry.id() + " has " + lineCount
                                + " lines in " + code);
                if (POETRY_TYPES.contains(entry.type())) {
                    assertTrue(lineCount >= 2,
                            () -> "Poetry entry " + entry.id() + " needs at least two lines in " + code);
                }
                Pattern requiredScript = REQUIRED_SCRIPTS.get(code);
                if (requiredScript != null) {
                    assertTrue(requiredScript.matcher(passage).find(),
                            () -> "Entry " + entry.id() + " has no expected " + code
                                    + " script characters");
                }
                if (!"en".equals(code)) {
                    assertFalse(normalized(passage).equals(normalized(entry.passages().get("en"))),
                            () -> "Entry " + entry.id() + " copied English into " + code);
                }
            });
            assertEquals(entry.passageOriginal().strip(),
                    entry.passages().get(entry.originalLanguage()).strip(),
                    () -> "Entry " + entry.id() + " does not preserve its original text");
        });
    }

    @Test
    void completeArchiveHasUniqueIdsAndOriginalsAndSecondBatchUsesAtMostTwoPassagesPerWork()
            throws Exception {
        ArchiveRepository.ArchiveDocument document = readArchiveDocument();
        assertEquals(document.entries().size(), document.entries().stream()
                .map(ArchiveRepository.ArchiveRecord::id).distinct().count());
        assertEquals(document.entries().size(), document.entries().stream()
                .map(entry -> normalized(entry.passageOriginal())).distinct().count());

        List<ArchiveRepository.ArchiveRecord> batch = document.entries().stream()
                .filter(entry -> SECOND_BATCH_AUTHORS.contains(entry.author())).toList();
        Map<String, Long> workCounts = batch.stream().collect(Collectors.groupingBy(
                entry -> entry.author() + "\u0000" + normalized(entry.originalWorkTitle()),
                Collectors.counting()));
        assertTrue(workCounts.values().stream().allMatch(count -> count <= 2),
                () -> "More than two passages from one work: " + workCounts.entrySet().stream()
                        .filter(entry -> entry.getValue() > 2).toList());
        SECOND_BATCH_AUTHORS.forEach(author -> assertTrue(batch.stream()
                        .filter(entry -> author.equals(entry.author()))
                        .map(entry -> normalized(entry.originalWorkTitle())).distinct().count() >= 8,
                () -> author + " needs passages from about ten representative works"));
    }

    @Test
    void everyEntryMaterializesAllNineteenPassagesAndEnglishDetails() {
        ArchiveRepository repository = new ArchiveRepository(objectMapper, catalog);
        repository.entries().forEach(entry -> {
            assertEquals(catalog.supportedCodes(), entry.localizations().keySet());
            entry.localizations().values().forEach(content -> {
                assertFalse(content.passage().isBlank());
                assertFalse(content.workTitle().isBlank());
                assertFalse(content.contextNote().isBlank());
                assertFalse(content.authorBio().isBlank());
                assertFalse(content.translationNote().isBlank());
            });
        });
    }

    @Test
    void compactArchiveStoresOnlyPassagesPerLanguageAndEnglishMetadataOnce() throws Exception {
        String json;
        try (InputStream input = new ClassPathResource(ArchiveRepository.ARCHIVE_RESOURCE)
                .getInputStream()) {
            json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = objectMapper.readValue(json, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) raw.get("entries");

        assertTrue(raw.containsKey("authors"));
        assertTrue(raw.containsKey("translationNote"));
        entries.forEach(entry -> {
            assertTrue(entry.containsKey("passages"));
            assertTrue(entry.containsKey("englishWorkTitle"));
            assertTrue(entry.containsKey("englishContextNote"));
            assertFalse(entry.containsKey("localizations"));
            assertFalse(entry.containsKey("authorBio"));
            assertFalse(entry.containsKey("translationNote"));
        });
    }

    @Test
    void everyTranslatedVersionDeclaresTheSharedMachineAssistedProjectNote() {
        ArchiveRepository repository = new ArchiveRepository(objectMapper, catalog);
        repository.entries().forEach(entry -> entry.localizations().forEach((code, content) -> {
            boolean displaysOriginalWithoutTranslation = content.passage().strip()
                    .equals(entry.passageOriginal().strip());
            if (!displaysOriginalWithoutTranslation) {
                assertTrue(content.translationNote().toLowerCase(Locale.ROOT)
                                .contains("machine-assisted"),
                        () -> "Entry " + entry.id() + " does not identify the " + code
                                + " version as machine-assisted");
            }
        }));
    }

    @Test
    void firstBatchHasVerifiedSourcesYearsCompleteTranslationsAndNoPlaceholders() throws Exception {
        ArchiveRepository.ArchiveDocument document = readArchiveDocument();
        Map<String, String> biographies = document.authors();
        List<ArchiveRepository.ArchiveRecord> batch = document.entries().stream()
                .filter(entry -> FIRST_BATCH_AUTHORS.contains(entry.author())).toList();

        assertEquals(150, batch.size());
        FIRST_BATCH_AUTHORS.forEach(author -> assertFalse(biographies.get(author).isBlank()));
        batch.forEach(entry -> {
            assertTrue(entry.year() != null && entry.year() > 0 && entry.year() <= 1930,
                    () -> "Missing or non-publication-safe year for entry " + entry.id());
            URI source = URI.create(entry.sourceUrl());
            assertTrue("https".equalsIgnoreCase(source.getScheme())
                            && source.getHost() != null && !source.getHost().isBlank()
                            && source.getPath() != null && !source.getPath().isBlank(),
                    () -> "Entry " + entry.id() + " needs a direct HTTPS source");
            assertFalse(entry.sourceUrl().matches("(?i).*?(goodreads|brainyquote|azquotes|quotefancy).*"),
                    () -> "Entry " + entry.id() + " uses a quote-aggregation source");
            assertEquals(VERIFIED_PUBLIC_DOMAIN_STATUS, entry.publicDomainStatus());
            assertFalse(entry.englishWorkTitle().isBlank());
            assertFalse(entry.englishContextNote().isBlank());
            assertNoPlaceholder(entry.passageOriginal(), entry.englishWorkTitle(),
                    entry.originalWorkTitle(), entry.englishContextNote(), entry.sourceUrl());
            assertEquals(catalog.supportedCodes(), entry.passages().keySet());
            entry.passages().forEach((code, passage) -> {
                assertFalse(passage.isBlank(),
                        () -> "Entry " + entry.id() + " has a blank " + code + " passage");
                assertNoPlaceholder(passage);
                int lineCount = passage.split("\\R", -1).length;
                assertTrue(lineCount >= 2 && lineCount <= 8,
                        () -> "Entry " + entry.id() + " has " + lineCount
                                + " lines in " + code);
                Pattern requiredScript = REQUIRED_SCRIPTS.get(code);
                if (requiredScript != null) {
                    assertTrue(requiredScript.matcher(passage).find(),
                            () -> "Entry " + entry.id() + " has no expected " + code
                                    + " script characters");
                }
            });
            entry.passages().forEach((code, passage) -> {
                if (!"en".equals(code)) {
                    assertFalse(normalized(passage).equals(normalized(entry.passages().get("en"))),
                            () -> "Entry " + entry.id() + " copied English into " + code);
                }
            });
            assertEquals(entry.passageOriginal().strip(),
                    entry.passages().get(entry.originalLanguage()).strip(),
                    () -> "Entry " + entry.id() + " does not preserve its original text");
            assertTrue(entry.themes().size() >= 1 && entry.themes().size() <= 3);
        });
    }

    @Test
    void firstBatchHasNoDuplicateOriginalsAndUsesAtMostTwoPassagesPerWork() throws Exception {
        List<ArchiveRepository.ArchiveRecord> batch = readArchiveDocument().entries().stream()
                .filter(entry -> FIRST_BATCH_AUTHORS.contains(entry.author())).toList();
        Map<String, Long> originalCounts = batch.stream().collect(Collectors.groupingBy(
                entry -> normalized(entry.passageOriginal()), Collectors.counting()));
        assertTrue(originalCounts.values().stream().allMatch(count -> count == 1),
                () -> "Duplicate originals: " + originalCounts.entrySet().stream()
                        .filter(entry -> entry.getValue() > 1).toList());

        Map<String, Long> workCounts = batch.stream().collect(Collectors.groupingBy(
                entry -> entry.author() + "\u0000" + normalized(entry.originalWorkTitle()),
                Collectors.counting()));
        assertTrue(workCounts.values().stream().allMatch(count -> count <= 2),
                () -> "More than two passages from one work: " + workCounts.entrySet().stream()
                        .filter(entry -> entry.getValue() > 2).toList());
        FIRST_BATCH_AUTHORS.forEach(author -> assertTrue(batch.stream()
                        .filter(entry -> author.equals(entry.author()))
                        .map(entry -> normalized(entry.originalWorkTitle())).distinct().count() >= 8,
                () -> author + " needs passages from about ten representative works"));
    }

    @Test
    void firstBatchUsesTheCompleteEighteenThemeVocabulary() throws Exception {
        Set<String> usedThemes = readArchiveDocument().entries().stream()
                .filter(entry -> FIRST_BATCH_AUTHORS.contains(entry.author()))
                .flatMap(entry -> entry.themes().stream()).collect(Collectors.toSet());
        assertEquals(Set.copyOf(ThemeDetector.THEME_KEYS), usedThemes);
    }

    @Test
    void realArchivePreservesLiteraryChineseAsItsOwnOriginalLanguage() {
        OracleEntry liBai = new ArchiveRepository(objectMapper, catalog).entries().stream()
                .filter(entry -> entry.id() == 6L).findFirst().orElseThrow();
        assertEquals("lzh", liBai.originalLanguage());
        assertEquals("長風破浪會有時，\n直掛雲帆濟滄海。", liBai.passageOriginal());
        assertEquals("文言", catalog.requireAny(liBai.originalLanguage()).name());
    }

    @Test
    void missingLocalizationCausesValidationFailure() {
        ArchiveRepository repository = new ArchiveRepository(objectMapper, catalog);
        OracleEntry valid = repository.entries().get(0);
        Map<String, LocalizedArchiveContent> incomplete = new HashMap<>(valid.localizations());
        incomplete.remove("sv");
        OracleEntry invalid = new OracleEntry(valid.id(), valid.passageOriginal(),
                valid.originalLanguage(), incomplete, valid.author(), valid.originalWorkTitle(),
                valid.year(), valid.type(), valid.sourceUrl(), valid.themes(),
                valid.publicDomainStatus());

        assertThrows(IllegalStateException.class,
                () -> ArchiveRepository.validate(List.of(invalid), catalog));
    }

    @Test
    void blankLocalizedDetailCausesValidationFailure() {
        ArchiveRepository repository = new ArchiveRepository(objectMapper, catalog);
        OracleEntry valid = repository.entries().get(0);
        Map<String, LocalizedArchiveContent> broken = new HashMap<>(valid.localizations());
        LocalizedArchiveContent english = broken.get("en");
        broken.put("en", new LocalizedArchiveContent(english.passage(), english.workTitle(),
                " ", english.authorBio(), english.translationNote()));
        OracleEntry invalid = new OracleEntry(valid.id(), valid.passageOriginal(),
                valid.originalLanguage(), broken, valid.author(), valid.originalWorkTitle(),
                valid.year(), valid.type(), valid.sourceUrl(), valid.themes(),
                valid.publicDomainStatus());

        assertThrows(IllegalStateException.class,
                () -> ArchiveRepository.validate(List.of(invalid), catalog));
    }

    @Test
    void everyLanguageHasAllThemeLabelsAndTerms() throws Exception {
        ThemeDetector.ThemeLexicon lexicon;
        try (InputStream input = new ClassPathResource(ThemeDetector.LEXICON_RESOURCE).getInputStream()) {
            lexicon = objectMapper.readValue(input, ThemeDetector.ThemeLexicon.class);
        }
        assertEquals(catalog.supportedCodes(), lexicon.languages().keySet());
        lexicon.languages().forEach((code, themes) -> {
            assertEquals(ThemeDetector.THEME_KEYS.size(), themes.size());
            assertEquals(ThemeDetector.THEME_KEYS.stream().collect(java.util.stream.Collectors.toSet()),
                    themes.keySet());
            themes.values().forEach(theme -> {
                assertFalse(theme.label().isBlank());
                assertTrue(theme.terms().size() >= 3);
            });
        });
    }

    @Test
    void eachLocalizedThemeLexiconCanMatchItsOwnTerms() throws Exception {
        ThemeDetector detector = new ThemeDetector(objectMapper, catalog);
        ThemeDetector.ThemeLexicon lexicon;
        try (InputStream input = new ClassPathResource(ThemeDetector.LEXICON_RESOURCE).getInputStream()) {
            lexicon = objectMapper.readValue(input, ThemeDetector.ThemeLexicon.class);
        }
        lexicon.languages().forEach((code, themes) -> themes.forEach((key, definition) ->
                definition.terms().forEach(sample -> assertTrue(detector.detect(sample, code).contains(key),
                        () -> code + " did not match theme " + key + " using " + sample))));
    }

    private ArchiveRepository.ArchiveDocument readArchiveDocument() throws Exception {
        try (InputStream input = new ClassPathResource(ArchiveRepository.ARCHIVE_RESOURCE)
                .getInputStream()) {
            return objectMapper.readValue(input, ArchiveRepository.ArchiveDocument.class);
        }
    }

    private static String normalized(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private static void assertNoPlaceholder(String... values) {
        for (String value : values) {
            assertFalse(PLACEHOLDER.matcher(value).find(),
                    () -> "Placeholder text found in: " + value);
        }
    }
}

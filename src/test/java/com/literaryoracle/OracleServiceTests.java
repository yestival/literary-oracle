package com.literaryoracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleSupplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import tools.jackson.databind.ObjectMapper;

class OracleServiceTests {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SupportedLanguageCatalog languageCatalog = new SupportedLanguageCatalog(objectMapper);
    private final ThemeDetector detector = new ThemeDetector(objectMapper, languageCatalog);
    private final LanguageDetectionService languageDetectionService =
            new LanguageDetectionService(languageCatalog);
    private final ArchiveRepository repository = new ArchiveRepository(objectMapper, languageCatalog);
    private final SemanticRetriever fallbackRetriever = (input, language, available) ->
            SemanticRetriever.RetrievalResult.localFallback();
    private final SafetyAssessmentService safetyAssessmentService =
            new SafetyAssessmentService(mock(HttpClient.class), objectMapper, "", "");
    private final OracleService service = new OracleService(detector, repository,
            languageDetectionService, languageCatalog, fallbackRetriever,
            safetyAssessmentService);

    @Test
    void detectsThemesInEnglishAndChinese() {
        List<String> english = detector.detect("I am not sure and afraid to try", "en");
        List<String> chinese = detector.detect("我很孤独，想找到归属和同伴", "zh-Hans");
        assertEquals(2, english.size());
        assertTrue(english.containsAll(List.of("uncertainty", "courage")));
        assertEquals(2, chinese.size());
        assertTrue(chinese.containsAll(List.of("loneliness", "belonging")));
    }

    @Test
    void latinThemeTermsDoNotMatchInsideUnrelatedWords() {
        ThemeDetector detector = new ThemeDetector(objectMapper, languageCatalog);

        assertFalse(detector.detect("My interest sometimes turns to seasonal research", "en")
                .stream().anyMatch(List.of("rest", "time", "nature")::contains));
    }

    @Test
    void candidatePoolChangesLinearlyAndRespectsArchiveSize() {
        assertEquals(3, OracleService.candidatePoolSize(0, 30));
        assertEquals(14, OracleService.candidatePoolSize(50, 30));
        assertEquals(25, OracleService.candidatePoolSize(100, 30));
        assertEquals(8, OracleService.candidatePoolSize(100, 8));
    }

    @Test
    void weightsFollowSpecifiedFormula() {
        assertEquals(0.95, OracleService.semanticWeight(0), 0.0001);
        assertEquals(0.05, OracleService.randomWeight(0), 0.0001);
        assertEquals(0.9425, OracleService.semanticWeight(1), 0.0001);
        assertEquals(0.0575, OracleService.randomWeight(1), 0.0001);
        assertEquals(0.575, OracleService.semanticWeight(50), 0.0001);
        assertEquals(0.425, OracleService.randomWeight(50), 0.0001);
        assertEquals(0.20, OracleService.semanticWeight(100), 0.0001);
        assertEquals(0.80, OracleService.randomWeight(100), 0.0001);
    }

    @Test
    void jinaIsPrimaryAndLocalFallbackKeepsThemeGate() {
        assertEquals(0.752, OracleService.jinaRelevanceScore(0.8, 0.4, 0.2), 0.0001);
        assertEquals(0.76, OracleService.localRelevanceScore(0.8, 0.4), 0.0001);
        assertEquals(0.0, OracleService.localRelevanceScore(0.0, 1.0), 0.0001);
    }

    @Test
    void finalScoreCombinesRelevanceAndRandomByTheSliderValue() {
        OracleService semantic = customService(List.of(
                semanticEntry(91, "A lantern waits beside the open gate.", "The Open Gate",
                        "A traveler sees a way forward.", List.of("hope", "change"))), () -> 0.2,
                retriever(SemanticRetriever.SemanticMode.JINA_RERANKED,
                        new SemanticRetriever.RankedCandidate(91, 1.0)));

        OracleService.OracleSelection selection = semantic.select(
                "A lantern beside an open gate", 25, List.of(), "en", "en-US");

        assertEquals(selection.semanticScore() * 0.7625 + 0.2 * 0.2375,
                selection.finalScore(), 0.0001);
    }

    @TestFactory
    Stream<DynamicTest> paraphrasesSelectEntriesWithTheExpectedSemanticThemes() {
        List<OracleEntry> entries = List.of(
                semanticEntry(101, "Several roads open beyond the fork.", "At the Crossroads",
                        "A traveler weighs a choice before taking the next path.",
                        List.of("uncertainty", "change")),
                semanticEntry(102, "One window remains dark beyond the gathering.", "Outside",
                        "The speaker watches other people find a place together while remaining apart.",
                        List.of("loneliness", "belonging")),
                semanticEntry(103, "A borrowed mask falls from the face.", "The Borrowed Face",
                        "The speaker stops performing an invented role and values an authentic self.",
                        List.of("identity", "self-worth")),
                semanticEntry(104, "The road rushes on while the traveler pauses.", "A Different Pace",
                        "Rapid movement makes room for a slower step and a changed rhythm.",
                        List.of("time", "rest", "change")),
                semanticEntry(105, "Dawn softens the hard edge of the night.", "A Gentler Morning",
                        "The coming day is imagined as more merciful than the present one.",
                        List.of("hope")));

        return Stream.of(
                new SemanticCase("I don’t know which direction to take", 101,
                        List.of("uncertainty", "change")),
                new SemanticCase("Everyone has somewhere to belong except me", 102,
                        List.of("loneliness", "belonging")),
                new SemanticCase("I keep pretending to be another person", 103,
                        List.of("identity", "self-worth")),
                new SemanticCase("Life is moving faster than I can follow", 104,
                        List.of("time", "rest", "change")),
                new SemanticCase("Perhaps tomorrow will be kinder", 105,
                        List.of("hope")))
                .map(testCase -> DynamicTest.dynamicTest(testCase.text(), () -> {
                    List<SemanticRetriever.RankedCandidate> candidates = entries.stream()
                            .map(entry -> new SemanticRetriever.RankedCandidate(entry.id(),
                                    entry.id() == testCase.expectedId() ? 1.0 : 0.1))
                            .toList();
                    OracleService semantic = customService(
                            entries, () -> 0.5, (input, language, available) ->
                                    new SemanticRetriever.RetrievalResult(
                                            SemanticRetriever.SemanticMode.JINA_RERANKED,
                                            candidates));
                    OracleService.OracleSelection selection = semantic.select(
                            testCase.text(), 0, List.of(), "en", "en-US");

                    assertEquals(testCase.expectedId(), selection.entry().id());
                    assertTrue(selection.entry().themes().containsAll(testCase.expectedThemes()));
                    assertTrue(selection.semanticScore() > 0);
                    assertEquals(SemanticRetriever.SemanticMode.JINA_RERANKED,
                            selection.semanticMode());
                }));
    }

    @Test
    void jinaScoresChangeCandidateRanking() {
        List<OracleEntry> entries = List.of(
                semanticEntry(171, "The same neutral sentence.", "Same title",
                        "The same neutral context.", List.of("grief")),
                semanticEntry(172, "The same neutral sentence.", "Same title",
                        "The same neutral context.", List.of("hope")));
        SemanticRetriever retriever = retriever(SemanticRetriever.SemanticMode.JINA_RERANKED,
                new SemanticRetriever.RankedCandidate(172, 0.9),
                new SemanticRetriever.RankedCandidate(171, 0.1));

        OracleService.OracleSelection selection = customService(entries, () -> 0.5, retriever)
                .select("A metaphor with no archive keywords", 0, List.of(), "en", "en-US");

        assertEquals(172, selection.entry().id());
        assertEquals(SemanticRetriever.SemanticMode.JINA_RERANKED, selection.semanticMode());
    }

    @Test
    void retrieverFailureUsesTheLocalFallback() {
        List<OracleEntry> entries = List.of(entry(191, "rest"), entry(192, "hope"));
        SemanticRetriever failing = (input, language, available) -> {
            throw new IllegalStateException("synthetic retriever failure");
        };

        OracleService.OracleSelection selection = customService(entries, () -> 0.5, failing)
                .select("I need hope", 0, List.of(), "en", "en-US");

        assertEquals(192, selection.entry().id());
        assertTrue(selection.matchedThemes().contains("hope"));
        assertEquals(SemanticRetriever.SemanticMode.LOCAL_FALLBACK,
                selection.semanticMode());
    }

    @Test
    void archiveTextProfilesInferThemesWithoutLexiconOrManualCues() {
        List<OracleEntry> entries = List.of(
                semanticEntry(181, "Rain darkens the roofs below.", "Evening Weather",
                        "Clouds gather over a quiet town.", List.of("nature", "rest")),
                semanticEntry(182, "A falcon rises beyond the opened cage.", "Above the Cliff",
                        "The bird leaves confinement and enters the wide air.",
                        List.of("courage", "freedom")));
        String paraphrase = "The falcon crosses the cliff after leaving its cage";
        assertTrue(detector.detect(paraphrase, "en").isEmpty());

        OracleService.OracleSelection selection = customService(entries, () -> 0.5)
                .select(paraphrase, 0, List.of(), "en", "en-US");

        assertEquals(182, selection.entry().id());
        assertTrue(selection.matchedThemes().containsAll(List.of("courage", "freedom")));
        assertTrue(selection.matchedThemes().size() >= 2);
        assertTrue(selection.matchedThemes().size() <= 3);
    }

    @Test
    void semanticIndexUsesTranslationsWorkTitlesAndEnglishContext() {
        List<OracleEntry> entries = List.of(
                semanticEntry(201, "A small boat crosses the haze.", "Harbor Notes",
                        "The voyage continues without a visible shore.", List.of("meaning"),
                        Map.of("fr", "Une barque trouve le rivage après la longue brume.")),
                semanticEntry(202, "A quiet lamp burns.", "The Untraveled Road",
                        "The room remains still.", List.of("meaning")),
                semanticEntry(203, "Morning enters an empty courtyard.", "Before Dawn",
                        "A traveler is waiting beside a closed gate until it opens.",
                        List.of("meaning")),
                semanticEntry(204, "Rain crosses a field.", "Weather Notes",
                        "The speaker observes an ordinary afternoon.", List.of("nature")));

        OracleService semantic = customService(entries, () -> 0.5);
        assertEquals(201, semantic.select("Une barque avance vers le rivage couvert de brume",
                0, List.of(), "fr", "fr-FR").entry().id());
        assertEquals(202, semantic.select("I stand before the untraveled road",
                0, List.of(), "en", "en-US").entry().id());
        assertEquals(203, semantic.select("I am waiting beside a closed gate",
                0, List.of(), "en", "en-US").entry().id());
    }

    @TestFactory
    Stream<DynamicTest> multilingualParaphrasesUseTheMatchingLanguageFields() {
        return Stream.of(
                new MultilingualSemanticCase(
                        "他们都围坐在灯下，只有我站在门外。", "zh-Hans", 512,
                        List.of("loneliness", "belonging"),
                        new LocalizedArchiveContent(
                                "门内的人围坐在灯火旁，门外的旅人仍在寻找一张空椅。",
                                "门外", "一个旅人望着别人围坐，却还没有自己的位置。",
                                "Author biography", "Translation note"),
                        new LocalizedArchiveContent(
                                "雨点落在石桥上，河水缓缓流过。", "石桥",
                                "雨落在河流上的场景。", "Author biography", "Translation note")),
                new MultilingualSemanticCase(
                        "Sigo usando una máscara para que otros decidan a quién debo parecerme.",
                        "es", 522, List.of("identity", "self-worth"),
                        new LocalizedArchiveContent("Una lámpara ilumina una mesa vacía.",
                                "La máscara elegida por otros",
                                "Una persona aprende a reconocer su propio rostro.",
                                "Author biography", "Translation note"),
                        new LocalizedArchiveContent("La lluvia cruza un puente de piedra.",
                                "Cuaderno del río", "Una escena junto al agua.",
                                "Author biography", "Translation note")),
                new MultilingualSemanticCase(
                        "時計の針ばかり駆けて、私の歩幅が追いつかない。", "ja", 532,
                        List.of("time", "rest", "change"),
                        new LocalizedArchiveContent("静かな庭に朝の光が差す。", "旅人の朝",
                                "時計の針が駆け、旅人の歩幅だけが追いつけない場面。",
                                "Author biography", "Translation note"),
                        new LocalizedArchiveContent("雨上がりの石垣に光が差す。", "石垣",
                                "光が石垣に差す場面。", "Author biography", "Translation note")))
                .map(testCase -> DynamicTest.dynamicTest(testCase.language(), () -> {
                    OracleEntry foil = semanticEntryWithLocalization(testCase.expectedId() - 1,
                            testCase.expectedThemes(), testCase.language(), testCase.foil());
                    OracleEntry target = semanticEntryWithLocalization(testCase.expectedId(),
                            testCase.expectedThemes(), testCase.language(), testCase.target());
                    OracleService semantic = customService(List.of(foil, target), () -> 0.5);

                    assertTrue(detector.detect(testCase.text(), testCase.language()).isEmpty());
                    OracleService.OracleSelection selection = semantic.select(testCase.text(), 0,
                            List.of(), testCase.language(), testCase.language());

                    assertEquals(testCase.expectedId(), selection.entry().id());
                    assertTrue(selection.matchedThemes().size() >= 2);
                    assertTrue(selection.matchedThemes().size() <= 3);
                    assertTrue(selection.matchedThemes().containsAll(testCase.expectedThemes()));
                    assertTrue(selection.semanticScore() > 0);
                }));
    }

    @Test
    void veryShortMeaninglessInputCanRandomizeAcrossAllAvailableEntries() {
        List<OracleEntry> entries = Stream.iterate(301L, id -> id + 1).limit(8)
                .map(id -> semanticEntry(id, "Neutral passage " + id, "Neutral title " + id,
                        "Neutral context " + id, List.of("meaning"))).toList();
        DoubleSupplier sequence = sequence(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.99);

        OracleService.OracleSelection selection = customService(entries, sequence)
                .select("ok", 0, List.of(), "en", "en-US");

        assertEquals(8, selection.candidatePoolSize());
        assertEquals(308, selection.entry().id());
        assertEquals(0, selection.semanticScore(), 0.0001);
    }

    @Test
    void embeddingOnlyModeIsReportedWithoutFallingBack() {
        List<OracleEntry> entries = Stream.iterate(601L, id -> id + 1).limit(8)
                .map(id -> semanticEntry(id, "Neutral passage " + id, "Neutral title " + id,
                        "Neutral context " + id, List.of("meaning"))).toList();
        OracleService semantic = customService(entries,
                sequence(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.99),
                retriever(SemanticRetriever.SemanticMode.JINA_EMBEDDING_ONLY,
                        new SemanticRetriever.RankedCandidate(608, 1.0)));

        OracleService.OracleSelection selection = semantic.select(
                "hello", 0, List.of(), "en", "en-US");

        assertEquals(1, selection.candidatePoolSize());
        assertEquals(608, selection.entry().id());
        assertEquals(SemanticRetriever.SemanticMode.JINA_EMBEDDING_ONLY,
                selection.semanticMode());
    }

    @Test
    void chanceLedSelectionStillExcludesZeroSimilarityEntriesForMeaningfulInput() {
        List<OracleEntry> entries = List.of(
                semanticEntry(401, "An amber compass points beyond the harbor.", "The Compass",
                        "A precise instrument offers a bearing.", List.of("meaning")),
                semanticEntry(402, "Rain passes over a roof.", "Weather",
                        "An ordinary shower crosses the town.", List.of("nature")),
                semanticEntry(403, "A cup rests on a table.", "Still Life",
                        "The room contains familiar objects.", List.of("rest")));

        OracleService.OracleSelection selection = customService(entries,
                sequence(0.0, 0.99, 0.99),
                retriever(SemanticRetriever.SemanticMode.JINA_RERANKED,
                        new SemanticRetriever.RankedCandidate(401, 1.0)))
                .select("amber compass", 100, List.of(), "en", "en-US");

        assertEquals(1, selection.candidatePoolSize());
        assertEquals(401, selection.entry().id());
        assertTrue(selection.semanticScore() > 0);
        assertEquals(SemanticRetriever.SemanticMode.JINA_RERANKED, selection.semanticMode());
    }

    @Test
    void unknownRetrieverIdsAreRejectedBeforeUsingLocalFallback() {
        List<OracleEntry> entries = List.of(
                semanticEntry(411, "Rain crosses the harbor roof.", "Harbor Rain",
                        "A shower moves over the water.", List.of("nature")),
                semanticEntry(412, "A wooden chair stands in a room.", "Furniture",
                        "An ordinary object remains still.", List.of("rest")));
        OracleService semantic = customService(entries, sequence(0.0, 0.99),
                retriever(SemanticRetriever.SemanticMode.JINA_RERANKED,
                        new SemanticRetriever.RankedCandidate(999, 1.0)));

        OracleService.OracleSelection selection = semantic.select(
                "rain across the harbor roof", 0, List.of(), "en", "en-US");

        assertEquals(411, selection.entry().id());
        assertTrue(selection.matchedThemes().contains("nature"));
        assertEquals(SemanticRetriever.SemanticMode.LOCAL_FALLBACK,
                selection.semanticMode());
    }

    @Test
    void jinaRankingOutweighsOpposingLocalThemeBonus() {
        List<OracleEntry> entries = List.of(entry(421, "hope"), entry(422, "grief"));
        OracleService semantic = customService(entries, () -> 0.5,
                retriever(SemanticRetriever.SemanticMode.JINA_RERANKED,
                        new SemanticRetriever.RankedCandidate(422, 1.0),
                        new SemanticRetriever.RankedCandidate(421, 0.1)));

        OracleService.OracleSelection selection = semantic.select(
                "I hope for the future", 0, List.of(), "en", "en-US");

        assertEquals(422, selection.entry().id());
        assertEquals(SemanticRetriever.SemanticMode.JINA_RERANKED,
                selection.semanticMode());
    }

    @Test
    void excludedIdsAreHonored() {
        OracleService deterministic = customService(
                List.of(entry(1, "hope"), entry(2, "hope")), () -> 0.5);
        assertEquals(2, deterministic.select("hope for the future", 0, List.of(1L), "en", "en")
                .entry().id());
    }

    @Test
    void theTenRecentIdsRemainExcluded() {
        List<OracleEntry> entries = Stream.iterate(701L, id -> id + 1).limit(12)
                .map(id -> entry(id, "hope")).toList();
        List<Long> recent = entries.stream().limit(10).map(OracleEntry::id).toList();
        OracleService semantic = customService(entries, () -> 0.5,
                (input, language, available) -> new SemanticRetriever.RetrievalResult(
                        SemanticRetriever.SemanticMode.JINA_RERANKED,
                        available.stream().map(entry -> new SemanticRetriever.RankedCandidate(
                                entry.id(), 1.0)).toList()));

        OracleService.OracleSelection selection = semantic.select(
                "A kinder horizon", 0, recent, "en", "en-US");

        assertFalse(recent.contains(selection.entry().id()));
    }

    @Test
    void crisisRequestNeverCallsTheSemanticRetriever() {
        AtomicInteger calls = new AtomicInteger();
        SemanticRetriever counting = (input, language, available) -> {
            calls.incrementAndGet();
            return new SemanticRetriever.RetrievalResult(
                    SemanticRetriever.SemanticMode.JINA_RERANKED,
                    List.of(new SemanticRetriever.RankedCandidate(801, 1.0)));
        };
        OracleService guarded = customService(List.of(entry(801, "hope")), () -> 0.5, counting);

        OracleController.OracleResponse response = new OracleController(guarded).ask(
                new OracleController.OracleRequest(
                        "I decided to kill myself tonight and have the pills beside me",
                        50, List.of(), "en", "en-US"));

        assertTrue(response.crisis());
        assertEquals(0, calls.get());
        assertEquals(SemanticRetriever.SemanticMode.LOCAL_FALLBACK,
                response.semanticMode());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> guarded.select(
                        "I decided to kill myself tonight and have the pills beside me", 50,
                        List.of(), "en", "en-US"));
        assertEquals("Crisis input must be handled before literary selection",
                exception.getMessage());
        assertEquals(0, calls.get());
    }

    @Test
    void exclusionRelaxesWhenSmallArchiveHasNoRemainingEntry() {
        OracleService tiny = customService(List.of(entry(7, "rest")), () -> 0.2);
        OracleService.OracleSelection result = tiny.select(
                "I need rest", 100, List.of(7L), "en", "en");
        assertEquals(7, result.entry().id());
        assertEquals(1, result.candidatePoolSize());
    }

    @Test
    void controllerReturnsLocalizedExplanationFields() {
        OracleController controller = new OracleController(service);
        OracleController.OracleResponse response = controller.ask(new OracleController.OracleRequest(
                "I need hope for the future", 50, List.of(), "en", "en-US"));
        assertFalse(response.crisis());
        assertTrue(response.matchedThemes().contains("hope"));
        assertEquals("BALANCED", response.chanceLevel());
        assertEquals(OracleService.candidatePoolSize(50, repository.entries().size()),
                response.candidatePoolSize());
        assertNotNull(response.type());
        assertNotNull(response.publicDomainStatus());
        assertNotNull(response.localizedContextNote());
        assertNotNull(response.localizedTranslationNote());
        assertEquals("en", response.displayLanguage());
        assertEquals("English", response.displayLanguageName());
        assertEquals("English", response.displayLanguageEnglishName());
        assertNotNull(response.displayText());
        assertNotNull(response.passageOriginal());
        assertNotNull(response.originalLanguage());
        assertNotNull(response.originalLanguageName());
        assertNotNull(response.originalLanguageEnglishName());
        assertNotNull(response.originalWorkTitle());
        assertNotNull(response.localizedWorkTitle());
        assertNotNull(response.englishWorkTitle());
        assertNotNull(response.englishContextNote());
        assertNotNull(response.englishAuthorBio());
        assertNotNull(response.englishTranslationNote());
        assertNotNull(response.canonicalAuthor());
        assertNotNull(response.localizedThemes());
    }

    @Test
    void safetyAssessmentDistinguishesImmediateRiskFromConcernAndDistress() {
        assertEquals(SafetyAssessmentService.SafetyAssessment.IMMEDIATE_RISK,
                service.assessSafety(
                        "I decided to kill myself tonight and have the pills beside me"));
        assertEquals(SafetyAssessmentService.SafetyAssessment.IMMEDIATE_RISK,
                service.assessSafety("我已经决定今晚自杀，身边有药片"));
        assertEquals(SafetyAssessmentService.SafetyAssessment.IMMEDIATE_RISK,
                service.assessSafety("I'm going to kill myself"));
        assertEquals(SafetyAssessmentService.SafetyAssessment.CONCERNING,
                service.assessSafety("I want to die"));
        assertEquals(SafetyAssessmentService.SafetyAssessment.CONCERNING,
                service.assessSafety("I am going to die"));
        assertEquals(SafetyAssessmentService.SafetyAssessment.SAFE,
                service.assessSafety("I feel sad, anxious and lost"));
        assertEquals(SafetyAssessmentService.SafetyAssessment.SAFE,
                service.assessSafety("最近很难过、迷茫，也有些焦虑"));
    }

    @Test
    void requestLoggingRepresentationRedactsTheInput() {
        String sensitiveSentinel = "private-input-sentinel";
        String rendered = new OracleController.OracleRequest(
                sensitiveSentinel, 50, List.of(), "en", "en-US").toString();

        assertFalse(rendered.contains(sensitiveSentinel));
        assertTrue(rendered.contains("text=<redacted>"));
    }

    @Test
    void chineseInputUsesChineseVersionForEnglishOriginal() {
        OracleEntry englishOriginal = localizedEntry(11, "English original", "en",
                Map.of("en", "English original", "zh-Hans", "中文版本"));
        OracleService.OracleSelection selection = selectOnly(
                englishOriginal, "我在想未来", "zh-Hans");

        assertEquals("zh-Hans", selection.displayLanguage().code());
        assertEquals("中文版本", selection.displayText());
        assertEquals("English original", selection.entry().passageOriginal());
        assertEquals("en", selection.entry().originalLanguage());
        assertTrue(selection.showOriginalSeparately());
    }

    @Test
    void chineseInputUsesChineseVersionAndKeepsJapaneseOriginal() {
        OracleEntry japaneseOriginal = localizedEntry(12, "古池や 蛙飛びこむ 水の音", "ja",
                Map.of("en", "The old pond— / a frog jumps in, / sound of water.",
                        "zh-Hans", "古池塘—— / 青蛙跃入， / 水声。", "ja", "古池や 蛙飛びこむ 水の音"));
        OracleService.OracleSelection selection = selectOnly(
                japaneseOriginal, "我感到疲惫", "zh-Hans");

        assertEquals("zh-Hans", selection.displayLanguage().code());
        assertEquals("古池塘—— / 青蛙跃入， / 水声。", selection.displayText());
        assertEquals("古池や 蛙飛びこむ 水の音", selection.entry().passageOriginal());
        assertEquals("ja", selection.entry().originalLanguage());
        assertTrue(selection.showOriginalSeparately());
    }

    @Test
    void chineseInputUsesChineseVersionAndKeepsRussianOriginal() {
        OracleEntry russianOriginal = localizedEntry(13,
                "Я вас любил: любовь еще, быть может, / В душе моей угасла не совсем;", "ru",
                Map.of("en", "I loved you; and perhaps I love you still,",
                        "zh-Hans", "我曾爱过你：也许爱情 / 在我心中还没有完全熄灭；",
                        "ru", "Я вас любил: любовь еще, быть может, / В душе моей угасла не совсем;"));
        OracleService.OracleSelection selection = selectOnly(
                russianOriginal, "我在想念一个人", "zh-Hans");

        assertEquals("zh-Hans", selection.displayLanguage().code());
        assertEquals("我曾爱过你：也许爱情 / 在我心中还没有完全熄灭；", selection.displayText());
        assertEquals("ru", selection.entry().originalLanguage());
        assertTrue(selection.showOriginalSeparately());
    }

    @Test
    void englishInputUsesEnglishVersionAndKeepsLiteraryChineseOriginal() {
        OracleEntry chineseOriginal = localizedEntry(14, "长风破浪会有时，直挂云帆济沧海。", "lzh",
                Map.of("en", "A time will come to ride the wind and cleave the waves.",
                        "zh-Hans", "长风破浪会有时，直挂云帆济沧海。"));
        OracleService.OracleSelection selection = selectOnly(chineseOriginal, "I need hope", "en");

        assertEquals("en", selection.displayLanguage().code());
        assertEquals("A time will come to ride the wind and cleave the waves.", selection.displayText());
        assertEquals("lzh", selection.entry().originalLanguage());
        assertEquals("文言", selection.originalLanguage().name());
        assertTrue(selection.showOriginalSeparately());
    }

    @Test
    void matchingDisplayAndOriginalDoesNotRequestDuplicateBlock() {
        OracleEntry englishOriginal = localizedEntry(15, "The same passage", "en",
                Map.of("en", "The same passage", "zh-Hans", "同一段文字"));
        OracleService.OracleSelection selection = selectOnly(englishOriginal, "I need hope", "en");

        assertEquals("en", selection.displayLanguage().code());
        assertFalse(selection.showOriginalSeparately());
    }

    @Test
    void differingDisplayAndOriginalRequestsBothTextBlocks() {
        OracleEntry englishOriginal = localizedEntry(16, "The original passage", "en",
                Map.of("en", "The original passage", "zh-Hans", "译文"));
        OracleService.OracleSelection selection = selectOnly(
                englishOriginal, "我需要希望", "zh-Hans");

        assertTrue(selection.showOriginalSeparately());
    }

    @Test
    void manualLanguageRemainsSelectedForResultAndDetails() {
        OracleEntry entry = localizedEntry(17, "Original text", "en",
                Map.of("en", "Original text", "fr", "Texte français"));
        OracleService.OracleSelection selection = customService(List.of(entry), () -> 0.5)
                .select("This input is clearly written in English", 50, List.of(), "fr", "en-US");

        assertEquals("fr", selection.displayLanguage().code());
        assertEquals("MANUAL", selection.displayLanguage().source());
        assertEquals("Texte français", selection.displayText());
        assertEquals("Contexte fr", selection.localized().contextNote());
    }

    @TestFactory
    Stream<DynamicTest> everySupportedLanguageRoutesToItsOwnArchiveLocalization() {
        return languageCatalog.supportedLanguages().stream().map(language -> DynamicTest.dynamicTest(
                language.code(), () -> {
                    OracleController.OracleResponse response = new OracleController(service).ask(
                            new OracleController.OracleRequest(
                                    "A sufficiently long input for deterministic manual language routing.",
                                    50, List.of(), language.code(), "en-US"));
                    OracleEntry selected = repository.entries().stream()
                            .filter(entry -> entry.id() == response.id()).findFirst().orElseThrow();
                    LocalizedArchiveContent expected = selected.localizations().get(language.code());
                    LocalizedArchiveContent english = selected.localizations().get("en");

                    assertEquals(language.code(), response.displayLanguage());
                    assertEquals(language.name(), response.displayLanguageName());
                    assertEquals(language.englishName(), response.displayLanguageEnglishName());
                    assertEquals(language.direction(), response.displayDirection());
                    assertEquals(expected.workTitle(), response.localizedWorkTitle());
                    assertEquals(expected.contextNote(), response.localizedContextNote());
                    assertEquals(expected.authorBio(), response.localizedAuthorBio());
                    assertEquals(expected.translationNote(), response.localizedTranslationNote());
                    assertEquals(selected.originalLanguage().equals(language.code())
                            ? selected.passageOriginal() : expected.passage(), response.displayText());
                    assertEquals(english.workTitle(), response.englishWorkTitle());
                    assertEquals(english.contextNote(), response.englishContextNote());
                    assertEquals(english.authorBio(), response.englishAuthorBio());
                    assertEquals(english.translationNote(), response.englishTranslationNote());
                }));
    }

    private OracleEntry entry(long id, String theme) {
        return localizedEntry(id, "original", "en", Map.of("en", "original"), theme);
    }

    private OracleEntry localizedEntry(long id, String original, String originalLanguage,
            Map<String, String> passages) {
        return localizedEntry(id, original, originalLanguage, passages, "hope");
    }

    private OracleEntry localizedEntry(long id, String original, String originalLanguage,
            Map<String, String> passages, String theme) {
        Map<String, LocalizedArchiveContent> localizations = new LinkedHashMap<>();
        for (SupportedLanguage language : languageCatalog.supportedLanguages()) {
            String passage = passages.getOrDefault(language.code(),
                    passages.getOrDefault("en", "Localized passage " + language.code()));
            localizations.put(language.code(), new LocalizedArchiveContent(passage,
                    "Work " + language.code(), "Contexte " + language.code(),
                    "Author biography " + language.code(), "Translation note " + language.code()));
        }
        return new OracleEntry(id, original, originalLanguage, Map.copyOf(localizations), "Author",
                "Original work", null, "POEM", null, List.of(theme), "PUBLIC_DOMAIN");
    }

    private OracleEntry semanticEntry(long id, String passage, String title, String context,
            List<String> themes) {
        return semanticEntry(id, passage, title, context, themes, Map.of());
    }

    private OracleEntry semanticEntry(long id, String passage, String title, String context,
            List<String> themes, Map<String, String> translatedPassages) {
        Map<String, LocalizedArchiveContent> localizations = new LinkedHashMap<>();
        for (SupportedLanguage language : languageCatalog.supportedLanguages()) {
            String localizedPassage = translatedPassages.getOrDefault(language.code(),
                    "en".equals(language.code()) ? passage
                            : "Neutral rendering " + id + " " + language.code());
            localizations.put(language.code(), new LocalizedArchiveContent(localizedPassage,
                    title, context, "Author biography", "Translation note"));
        }
        return new OracleEntry(id, passage, "en", Map.copyOf(localizations), "Author " + id,
                title, null, "POEM", null, List.copyOf(themes), "PUBLIC_DOMAIN");
    }

    private OracleEntry semanticEntryWithLocalization(long id, List<String> themes,
            String languageCode, LocalizedArchiveContent override) {
        Map<String, LocalizedArchiveContent> localizations = new LinkedHashMap<>();
        for (SupportedLanguage language : languageCatalog.supportedLanguages()) {
            LocalizedArchiveContent localized = language.code().equals(languageCode)
                    ? override
                    : new LocalizedArchiveContent("Neutral rendering " + id + " " + language.code(),
                            "Neutral title " + id, "Neutral context " + id,
                            "Author biography", "Translation note");
            localizations.put(language.code(), localized);
        }
        return new OracleEntry(id, "Neutral original " + id, "en", Map.copyOf(localizations),
                "Author " + id, "Neutral original title " + id, null, "POEM", null,
                List.copyOf(themes), "PUBLIC_DOMAIN");
    }

    private DoubleSupplier sequence(double... values) {
        AtomicInteger index = new AtomicInteger();
        return () -> values[Math.min(index.getAndIncrement(), values.length - 1)];
    }

    private OracleService customService(List<OracleEntry> entries, DoubleSupplier random) {
        return customService(entries, random, fallbackRetriever);
    }

    private OracleService customService(List<OracleEntry> entries, DoubleSupplier random,
            SemanticRetriever retriever) {
        return new OracleService(detector, entries, random, languageDetectionService,
                languageCatalog, retriever, safetyAssessmentService);
    }

    private SemanticRetriever retriever(SemanticRetriever.SemanticMode mode,
            SemanticRetriever.RankedCandidate... candidates) {
        return (input, language, available) ->
                new SemanticRetriever.RetrievalResult(mode, List.of(candidates));
    }

    private OracleService.OracleSelection selectOnly(OracleEntry entry, String text, String language) {
        return customService(List.of(entry), () -> 0.5)
                .select(text, 50, List.of(), language, "en-US");
    }

    private record SemanticCase(String text, long expectedId, List<String> expectedThemes) {
    }

    private record MultilingualSemanticCase(String text, String language, long expectedId,
            List<String> expectedThemes, LocalizedArchiveContent target,
            LocalizedArchiveContent foil) {
    }
}

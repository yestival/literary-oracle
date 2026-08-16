package com.literaryoracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class OracleControllerApiTests {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ArchiveRepository archiveRepository;
    @Autowired
    private ObjectMapper objectMapper;
    @MockitoBean
    private SemanticRetriever semanticRetriever;
    @MockitoBean
    private SafetyAssessmentService safetyAssessmentService;

    @BeforeEach
    void disableRealExternalCalls() {
        when(semanticRetriever.retrieve(anyString(), anyString(), anyList()))
                .thenReturn(SemanticRetriever.RetrievalResult.localFallback());
        when(safetyAssessmentService.assess(anyString()))
                .thenReturn(SafetyAssessmentService.SafetyAssessment.SAFE);
        clearInvocations(semanticRetriever, safetyAssessmentService);
    }

    @TestFactory
    Stream<DynamicTest> blankAndOversizedInputsAreRejectedBeforeSafetyOrRetrieval() {
        return Stream.of(
                Map.entry("empty", ""),
                Map.entry("whitespace", " \n\t "),
                Map.entry("over-limit", "x".repeat(4001)))
                .map(sample -> DynamicTest.dynamicTest(sample.getKey(), () -> {
                    mockMvc.perform(post("/api/oracle")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "text", sample.getValue(),
                                    "chance", 50,
                                    "language", "auto"))))
                            .andExpect(status().isBadRequest());

                    verifyNoInteractions(safetyAssessmentService, semanticRetriever);
                    reset(safetyAssessmentService, semanticRetriever);
                }));
    }

    @Test
    void multilineEmojiAndMixedLanguageInputReturnsACompleteLiteraryResult() throws Exception {
        String mixedInput = "A road changes direction.\n我还在想下一步 🌿";

        mockMvc.perform(post("/api/oracle")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "text", mixedInput,
                        "chance", 50,
                        "language", "en"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.crisis").value(false))
                .andExpect(jsonPath("$.safetyConfirmationRequired").value(false))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.displayText").isNotEmpty())
                .andExpect(jsonPath("$.passageOriginal").isNotEmpty())
                .andExpect(jsonPath("$.canonicalAuthor").isNotEmpty())
                .andExpect(jsonPath("$.localizedWorkTitle").isNotEmpty())
                .andExpect(jsonPath("$.sourceUrl").isNotEmpty())
                .andExpect(jsonPath("$.publicDomainStatus").isNotEmpty())
                .andExpect(jsonPath("$.semanticMode").value("LOCAL_FALLBACK"));

        verify(safetyAssessmentService, times(1)).assess(mixedInput);
        verify(semanticRetriever, times(1)).retrieve(anyString(), anyString(), anyList());
    }

    @Test
    void apiHonorsManualLanguageAndReturnsOnlySelectedLocalization() throws Exception {
        mockMvc.perform(post("/api/oracle")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "text": "This sentence is written in English but French is selected.",
                          "chance": 50,
                          "excludedIds": [],
                          "language": "fr",
                          "browserLanguage": "en-US"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.crisis").value(false))
                .andExpect(jsonPath("$.safetyConfirmationRequired").value(false))
                .andExpect(jsonPath("$.semanticMode").value("LOCAL_FALLBACK"))
                .andExpect(jsonPath("$.displayLanguage").value("fr"))
                .andExpect(jsonPath("$.displayLanguageName").value("Français"))
                .andExpect(jsonPath("$.displayLanguageEnglishName").value("French"))
                .andExpect(jsonPath("$.displayDirection").value("ltr"))
                .andExpect(jsonPath("$.languageCertain").value(true))
                .andExpect(jsonPath("$.languageSource").value("MANUAL"))
                .andExpect(jsonPath("$.displayText").isNotEmpty())
                .andExpect(jsonPath("$.passageOriginal").isNotEmpty())
                .andExpect(jsonPath("$.originalLanguage").isNotEmpty())
                .andExpect(jsonPath("$.originalLanguageName").isNotEmpty())
                .andExpect(jsonPath("$.originalLanguageEnglishName").isNotEmpty())
                .andExpect(jsonPath("$.originalWorkTitle").isNotEmpty())
                .andExpect(jsonPath("$.localizedWorkTitle").isNotEmpty())
                .andExpect(jsonPath("$.localizedContextNote").isNotEmpty())
                .andExpect(jsonPath("$.localizedAuthorBio").isNotEmpty())
                .andExpect(jsonPath("$.localizedTranslationNote").isNotEmpty())
                .andExpect(jsonPath("$.englishWorkTitle").isNotEmpty())
                .andExpect(jsonPath("$.englishContextNote").isNotEmpty())
                .andExpect(jsonPath("$.englishAuthorBio").isNotEmpty())
                .andExpect(jsonPath("$.englishTranslationNote").isNotEmpty())
                .andExpect(jsonPath("$.canonicalAuthor").isNotEmpty())
                .andExpect(jsonPath("$.localizedThemes").isArray())
                .andExpect(jsonPath("$.localizations").doesNotExist())
                .andExpect(jsonPath("$.passageEnglish").doesNotExist())
                .andExpect(jsonPath("$.passageChinese").doesNotExist());
    }

    @Test
    void autoDetectedSimplifiedChineseReturnsTheSelectedChinesePassage() throws Exception {
        assertApiUsesSelectedLocalization(
                "我最近很迷茫，不知道应该怎么办", "auto", "en-US", "zh-Hans", true);
    }

    @Test
    void autoDetectedTraditionalChineseReturnsTheSelectedTraditionalPassage() throws Exception {
        assertApiUsesSelectedLocalization(
                "我最近很迷惘，不知道應該怎麼辦", "auto", "en-US", "zh-Hant", true);
    }

    @Test
    void shortChineseInputDoesNotFallBackToAnEnglishBrowser() throws Exception {
        assertApiUsesSelectedLocalization("很孤独", "auto", "en-US", "zh-Hans", true);
    }

    @Test
    void latinInputDoesNotUseTheChineseBrowserLanguageAsItsFallback() throws Exception {
        assertApiUsesSelectedLocalization("hello", "auto", "zh-CN", "en", false);
    }

    @Test
    void manualChineseSelectionStillOverridesLatinInput() throws Exception {
        assertApiUsesSelectedLocalization("hello", "zh-Hans", "en-US", "zh-Hans", true);
    }

    @TestFactory
    Stream<DynamicTest> manualLanguageAlwaysSelectsThatArchiveLocalization() {
        return Stream.of("zh-Hans", "ja", "ru").map(code -> DynamicTest.dynamicTest(
                "manual " + code, () -> assertApiUsesSelectedLocalization(
                        "This English text must not override the manual selection.", code,
                        "en-US", code, true)));
    }

    @TestFactory
    Stream<DynamicTest> scriptDetectedJapaneseAndRussianUseTheirOwnPassages() {
        return Stream.of(
                Map.entry("ja", "どうすればいいのかわからない"),
                Map.entry("ru", "Я не знаю, что мне делать"))
                .map(sample -> DynamicTest.dynamicTest("auto " + sample.getKey(),
                        () -> assertApiUsesSelectedLocalization(sample.getValue(), "auto",
                                "en-US", sample.getKey(), true)));
    }

    @Test
    void EnglishInputStillReturnsTheEnglishArchivePassage() throws Exception {
        assertApiUsesSelectedLocalization(
                "I feel uncertain about what I should do next.", "auto", "en-US", "en", false);
    }

    @Test
    void apiAutoModeReturnsDetectionMetadata() throws Exception {
        mockMvc.perform(post("/api/oracle")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "text": "最近我一直在思考未來應該怎樣改變自己的生活。",
                          "chance": 20,
                          "language": "auto",
                          "browserLanguage": "zh-TW"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayLanguage").value("zh-Hant"))
                .andExpect(jsonPath("$.displayLanguageName").value("繁體中文"))
                .andExpect(jsonPath("$.languageSource").value("AUTO"))
                .andExpect(jsonPath("$.matchedThemes").isArray())
                .andExpect(jsonPath("$.candidatePoolSize").isNumber());
    }

    @Test
    void apiExposesJinaRerankedModeForAMockedRetrieval() throws Exception {
        long candidateId = archiveRepository.entries().get(0).id();
        when(semanticRetriever.retrieve(anyString(), anyString(), anyList())).thenReturn(
                retrieval(SemanticRetriever.SemanticMode.JINA_RERANKED, candidateId));

        mockMvc.perform(post("/api/oracle")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"text":"Perhaps the closed sky will open later.","chance":20,"language":"en"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.crisis").value(false))
                .andExpect(jsonPath("$.semanticMode").value("JINA_RERANKED"))
                .andExpect(jsonPath("$.id").value(candidateId));
    }

    @Test
    void apiExposesJinaEmbeddingOnlyModeWhenRerankingIsUnavailable() throws Exception {
        long candidateId = archiveRepository.entries().get(1).id();
        when(semanticRetriever.retrieve(anyString(), anyString(), anyList())).thenReturn(
                retrieval(SemanticRetriever.SemanticMode.JINA_EMBEDDING_ONLY, candidateId));

        mockMvc.perform(post("/api/oracle")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"text":"The road ahead has no signposts.","chance":20,"language":"en"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.crisis").value(false))
                .andExpect(jsonPath("$.semanticMode").value("JINA_EMBEDDING_ONLY"))
                .andExpect(jsonPath("$.id").value(candidateId));
    }

    @Test
    void apiExposesLocalFallbackModeWhenJinaIsUnavailable() throws Exception {
        mockMvc.perform(post("/api/oracle")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"text":"I am considering what comes next.","chance":20,"language":"en"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.crisis").value(false))
                .andExpect(jsonPath("$.semanticMode").value("LOCAL_FALLBACK"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void semanticStatusReturnsExactlySixSafeFieldsWithoutRunningRetrieval() throws Exception {
        when(semanticRetriever.status()).thenReturn(new SemanticRetriever.SemanticStatus(
                true, true, "jina-embeddings-v3", "jina-reranker-v3", true, "RERANKED"));
        clearInvocations(semanticRetriever);

        MvcResult result = mockMvc.perform(get("/api/semantic/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.reachable").value(true))
                .andExpect(jsonPath("$.embeddingModel").value("jina-embeddings-v3"))
                .andExpect(jsonPath("$.rerankerModel").value("jina-reranker-v3"))
                .andExpect(jsonPath("$.archiveEmbeddingsReady").value(true))
                .andExpect(jsonPath("$.lastCallStatus").value("RERANKED"))
                .andReturn();

        Map<String, Object> response = objectMapper.readValue(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8), Map.class);
        assertEquals(Set.of("configured", "reachable", "embeddingModel", "rerankerModel",
                "archiveEmbeddingsReady", "lastCallStatus"), response.keySet());
        verify(semanticRetriever).status();
        verify(semanticRetriever, never()).retrieve(anyString(), anyString(), anyList());
    }

    @Test
    void crisisDetectionStillShortCircuitsLiterarySelection() throws Exception {
        String immediateRisk = "I have decided to take the pills beside me tonight.";
        when(safetyAssessmentService.assess(immediateRisk))
                .thenReturn(SafetyAssessmentService.SafetyAssessment.IMMEDIATE_RISK);

        mockMvc.perform(post("/api/oracle")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "text", immediateRisk,
                        "chance", 50,
                        "language", "en"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.crisis").value(true))
                .andExpect(jsonPath("$.safetyConfirmationRequired").value(false))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("local emergency services")))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("someone you trust")))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("https://findahelpline.com")))
                .andExpect(jsonPath("$.displayLanguage").value("en"))
                .andExpect(jsonPath("$.displayLanguageName").value("English"))
                .andExpect(jsonPath("$.displayDirection").value("ltr"))
                .andExpect(jsonPath("$.semanticMode").value("LOCAL_FALLBACK"))
                .andExpect(jsonPath("$.id").doesNotExist());
        verifyNoInteractions(semanticRetriever);
        verify(safetyAssessmentService, times(1)).assess(immediateRisk);
    }

    @Test
    void crisisResponseCarriesAutoDetectedChineseVariantWithoutSelectingAnEntry() throws Exception {
        String immediateRisk = "我已经决定今晚吞药自杀，药片就在我身边";
        when(safetyAssessmentService.assess(immediateRisk))
                .thenReturn(SafetyAssessmentService.SafetyAssessment.IMMEDIATE_RISK);

        mockMvc.perform(post("/api/oracle")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "text", immediateRisk,
                        "chance", 50,
                        "language", "auto",
                        "browserLanguage", "zh-TW"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.crisis").value(true))
                .andExpect(jsonPath("$.displayLanguage").value("zh-Hant"))
                .andExpect(jsonPath("$.displayLanguageName").value("繁體中文"))
                .andExpect(jsonPath("$.languageSource").value("AUTO"))
                .andExpect(jsonPath("$.semanticMode").value("LOCAL_FALLBACK"))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.displayText").doesNotExist());
        verifyNoInteractions(semanticRetriever);
        verify(safetyAssessmentService, times(1)).assess(immediateRisk);
    }

    @Test
    void concerningAssessmentRequiresConfirmationWithoutCallingJinaOrReturningLiterature()
            throws Exception {
        String concerning = "i wanna go die";
        when(safetyAssessmentService.assess(concerning))
                .thenReturn(SafetyAssessmentService.SafetyAssessment.CONCERNING);

        mockMvc.perform(post("/api/oracle")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "text", concerning,
                        "chance", 20,
                        "language", "en"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.crisis").value(false))
                .andExpect(jsonPath("$.safetyConfirmationRequired").value(true))
                .andExpect(jsonPath("$.message").value(
                        "If you may act on thoughts of hurting yourself, immediate human support matters more than a literary passage."))
                .andExpect(jsonPath("$.displayLanguage").value("en"))
                .andExpect(jsonPath("$.displayLanguageName").value("English"))
                .andExpect(jsonPath("$.displayLanguageEnglishName").value("English"))
                .andExpect(jsonPath("$.displayDirection").value("ltr"))
                .andExpect(jsonPath("$.languageSource").value("MANUAL"))
                .andExpect(jsonPath("$.semanticMode").value("LOCAL_FALLBACK"))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.displayText").doesNotExist())
                .andExpect(jsonPath("$.passageOriginal").doesNotExist());

        verify(safetyAssessmentService, times(1)).assess(concerning);
        verifyNoInteractions(semanticRetriever);
    }

    @Test
    void allRequestedAmbiguousPhrasesReceiveSafetyConfirmation() throws Exception {
        List<String> concerningInputs = List.of(
                "我感觉我活不下去了",
                "i wanna go die",
                "i am going to die");
        for (String concerning : concerningInputs) {
            when(safetyAssessmentService.assess(concerning))
                    .thenReturn(SafetyAssessmentService.SafetyAssessment.CONCERNING);

            mockMvc.perform(post("/api/oracle")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(
                            "text", concerning,
                            "chance", 50,
                            "language", "auto"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.crisis").value(false))
                    .andExpect(jsonPath("$.safetyConfirmationRequired").value(true))
                    .andExpect(jsonPath("$.id").doesNotExist())
                    .andExpect(jsonPath("$.displayText").doesNotExist());
        }

        for (String concerning : concerningInputs) {
            verify(safetyAssessmentService, times(1)).assess(concerning);
        }
        verifyNoInteractions(semanticRetriever);
    }

    @Test
    void acknowledgedConcerningAssessmentContinuesThroughJinaAndReturnsLiterature()
            throws Exception {
        String concerning = "I don't think I can keep living";
        long candidateId = archiveRepository.entries().get(2).id();
        when(safetyAssessmentService.assess(concerning))
                .thenReturn(SafetyAssessmentService.SafetyAssessment.CONCERNING);
        when(semanticRetriever.retrieve(anyString(), anyString(), anyList())).thenReturn(
                retrieval(SemanticRetriever.SemanticMode.JINA_RERANKED, candidateId));

        mockMvc.perform(post("/api/oracle")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "text", concerning,
                        "chance", 20,
                        "language", "en",
                        "safetyAcknowledged", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.crisis").value(false))
                .andExpect(jsonPath("$.safetyConfirmationRequired").value(false))
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.semanticMode").value("JINA_RERANKED"))
                .andExpect(jsonPath("$.id").value(candidateId))
                .andExpect(jsonPath("$.displayText").isNotEmpty());

        verify(safetyAssessmentService, times(1)).assess(concerning);
        verify(semanticRetriever, times(1)).retrieve(anyString(), anyString(), anyList());
    }

    @Test
    void safetyAcknowledgementCannotBypassImmediateRisk() throws Exception {
        List<String> immediateInputs = List.of("我马上要去死", "我要去自杀");
        for (String immediate : immediateInputs) {
            when(safetyAssessmentService.assess(immediate))
                    .thenReturn(SafetyAssessmentService.SafetyAssessment.IMMEDIATE_RISK);

            mockMvc.perform(post("/api/oracle")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(
                            "text", immediate,
                            "chance", 50,
                            "language", "auto",
                            "safetyAcknowledged", true))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.crisis").value(true))
                    .andExpect(jsonPath("$.safetyConfirmationRequired").value(false))
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("https://findahelpline.com")))
                    .andExpect(jsonPath("$.id").doesNotExist())
                    .andExpect(jsonPath("$.displayText").doesNotExist());
        }

        for (String immediate : immediateInputs) {
            verify(safetyAssessmentService, times(1)).assess(immediate);
        }
        verifyNoInteractions(semanticRetriever);
    }

    @SuppressWarnings("unchecked")
    private void assertApiUsesSelectedLocalization(String text, String language,
            String browserLanguage, String expectedLanguage, boolean mustDifferFromEnglish)
            throws Exception {
        String requestJson = objectMapper.writeValueAsString(Map.of(
                "text", text,
                "chance", 50,
                "excludedIds", java.util.List.of(),
                "language", language,
                "browserLanguage", browserLanguage));
        MvcResult result = mockMvc.perform(post("/api/oracle")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.crisis").value(false))
                .andExpect(jsonPath("$.displayLanguage").value(expectedLanguage))
                .andReturn();

        Map<String, Object> response = objectMapper.readValue(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8), Map.class);
        long selectedId = ((Number) response.get("id")).longValue();
        OracleEntry selected = archiveRepository.entries().stream()
                .filter(entry -> entry.id() == selectedId).findFirst().orElseThrow();
        LocalizedArchiveContent localized = selected.localizations().get(expectedLanguage);
        LocalizedArchiveContent english = selected.localizations().get("en");
        String expectedPassage = selected.originalLanguage().equals(expectedLanguage)
                ? selected.passageOriginal() : localized.passage();
        String englishPassage = selected.originalLanguage().equals("en")
                ? selected.passageOriginal() : english.passage();

        assertEquals(expectedPassage, response.get("displayText"));
        if (mustDifferFromEnglish) {
            assertNotEquals(englishPassage, response.get("displayText"));
        }
        assertEquals(english.workTitle(), response.get("englishWorkTitle"));
        assertEquals(english.contextNote(), response.get("englishContextNote"));
        assertEquals(english.authorBio(), response.get("englishAuthorBio"));
        assertEquals(english.translationNote(), response.get("englishTranslationNote"));
    }

    private static SemanticRetriever.RetrievalResult retrieval(
            SemanticRetriever.SemanticMode mode, long candidateId) {
        return new SemanticRetriever.RetrievalResult(mode,
                List.of(new SemanticRetriever.RankedCandidate(candidateId, 1.0)));
    }
}

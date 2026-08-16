package com.literaryoracle;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/oracle")
public class OracleController {
    private static final String CRISIS_MESSAGE = "I'm glad you said something. You deserve immediate human support, not a literary answer. "
            + "If you may act now or are in immediate danger, contact your local emergency services now. "
            + "Move away from anything you could use to hurt yourself and, if possible, contact or stay with someone you trust. "
            + "Find local crisis support at https://findahelpline.com.";
    private static final String SAFETY_CONFIRMATION_MESSAGE =
            "If you may act on thoughts of hurting yourself, immediate human support matters more than a literary passage.";

    private final OracleService oracleService;

    public OracleController(OracleService oracleService) { this.oracleService = oracleService; }

    @PostMapping
    public OracleResponse ask(@RequestBody OracleRequest request) {
        if (request == null || request.text() == null || request.text().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text must not be blank");
        if (request.text().length() > 4000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text must be 4000 characters or fewer");
        int chance = request.chance() == null ? 50 : request.chance();
        if (chance < 0 || chance > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "chance must be between 0 and 100");
        SafetyAssessmentService.SafetyAssessment assessment = oracleService.assessSafety(
                request.text());
        LanguageDetectionService.LanguageResolution language = oracleService.resolveLanguage(
                request.text(), request.language(), request.browserLanguage());
        if (assessment == SafetyAssessmentService.SafetyAssessment.IMMEDIATE_RISK) {
            return OracleResponse.crisis(CRISIS_MESSAGE, language);
        }
        if (assessment == SafetyAssessmentService.SafetyAssessment.CONCERNING
                && !Boolean.TRUE.equals(request.safetyAcknowledged())) {
            return OracleResponse.safetyConfirmation(SAFETY_CONFIRMATION_MESSAGE, language);
        }

        OracleService.OracleSelection selection = oracleService.selectAfterSafetyAssessment(
                request.text(), chance, request.excludedIds(), language, assessment);
        return OracleResponse.from(selection);
    }

    public record OracleRequest(String text, Integer chance, List<Long> excludedIds,
            String language, String browserLanguage, Boolean safetyAcknowledged) {
        /** Compatibility constructor for callers using the language-aware request shape. */
        public OracleRequest(String text, Integer chance, List<Long> excludedIds,
                String language, String browserLanguage) {
            this(text, chance, excludedIds, language, browserLanguage, false);
        }

        /** Compatibility constructor for callers using the original request shape. */
        public OracleRequest(String text, Integer chance, List<Long> excludedIds) {
            this(text, chance, excludedIds, "auto", null, false);
        }

        @Override
        public String toString() {
            return "OracleRequest[text=<redacted>, chance=" + chance
                    + ", excludedIds=" + excludedIds + ", language=" + language
                    + ", browserLanguage=" + browserLanguage
                    + ", safetyAcknowledged=" + safetyAcknowledged + "]";
        }
    }

    public record OracleResponse(
            boolean crisis,
            boolean safetyConfirmationRequired,
            String message,
            String displayText,
            String displayLanguage,
            String displayLanguageName,
            String displayDirection,
            Boolean languageCertain,
            Double languageConfidence,
            String languageSource,
            boolean showOriginalSeparately,
            Long id,
            String passageOriginal,
            String originalLanguage,
            String originalLanguageName,
            String originalDirection,
            String originalWorkTitle,
            String localizedWorkTitle,
            String canonicalAuthor,
            Integer year,
            String type,
            String sourceUrl,
            String publicDomainStatus,
            String localizedContextNote,
            String localizedAuthorBio,
            String localizedTranslationNote,
            List<ThemeDetector.LocalizedTheme> localizedThemes,
            // Compatibility aliases retained without exposing all localizations.
            String passageTranslation,
            String author,
            String workTitle,
            List<String> themes,
            String contextNote,
            String authorBio,
            String translationNote,
            List<String> matchedThemes,
            String chanceLevel,
            Integer candidatePoolSize,
            Double semanticScore,
            Double finalScore,
            // English-only labels and record copy for the fixed-English interface.
            String displayLanguageEnglishName,
            String originalLanguageEnglishName,
            String englishWorkTitle,
            String englishContextNote,
            String englishAuthorBio,
            String englishTranslationNote,
            SemanticRetriever.SemanticMode semanticMode) {

        static OracleResponse crisis(String message,
                LanguageDetectionService.LanguageResolution language) {
            return withoutLiterature(true, false, message, language);
        }

        static OracleResponse safetyConfirmation(String message,
                LanguageDetectionService.LanguageResolution language) {
            return withoutLiterature(false, true, message, language);
        }

        private static OracleResponse withoutLiterature(boolean crisis,
                boolean safetyConfirmationRequired, String message,
                LanguageDetectionService.LanguageResolution language) {
            return new OracleResponse(crisis, safetyConfirmationRequired, message, null,
                    language.code(), language.name(),
                    language.direction(), language.certain(), language.confidence(), language.source(),
                    false, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, List.of(), null, null, null, List.of(), null, null,
                    null, List.of(), null, null, null, null, language.englishName(), null,
                    null, null, null, null,
                    SemanticRetriever.SemanticMode.LOCAL_FALLBACK);
        }

        static OracleResponse from(OracleService.OracleSelection selection) {
            OracleEntry entry = selection.entry();
            LocalizedArchiveContent localized = selection.localized();
            LocalizedArchiveContent english = entry.localizations().get("en");
            if (english == null) {
                throw new IllegalStateException("Archive entry " + entry.id()
                        + " has no English localization");
            }
            LanguageDetectionService.LanguageResolution display = selection.displayLanguage();
            SupportedLanguage original = selection.originalLanguage();
            return new OracleResponse(false, false, null, selection.displayText(), display.code(),
                    display.name(), display.direction(), display.certain(), display.confidence(),
                    display.source(), selection.showOriginalSeparately(), entry.id(),
                    entry.passageOriginal(), entry.originalLanguage(), original.name(),
                    original.direction(), entry.originalWorkTitle(), localized.workTitle(), entry.author(),
                    entry.year(), entry.type(), entry.sourceUrl(), entry.publicDomainStatus(),
                    localized.contextNote(), localized.authorBio(), localized.translationNote(),
                    selection.localizedThemes(), selection.displayText(), entry.author(),
                    localized.workTitle(), entry.themes(), localized.contextNote(), localized.authorBio(),
                    localized.translationNote(), selection.matchedThemes(), selection.chanceLevel(),
                    selection.candidatePoolSize(), selection.semanticScore(), selection.finalScore(),
                    display.englishName(), original.englishName(), english.workTitle(),
                    english.contextNote(), english.authorBio(), english.translationNote(),
                    selection.semanticMode());
        }
    }
}

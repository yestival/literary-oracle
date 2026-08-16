package com.literaryoracle;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class FrontendRenderingContractTests {
    private static final Path STATIC_ROOT = Path.of("src", "main", "resources", "static");
    private static final Path I18N_ROOT = STATIC_ROOT.resolve("i18n");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void frontendAssetsUseVersionedUrls() throws IOException {
        String html = read(STATIC_ROOT.resolve("index.html"));

        assertAll(
                () -> assertTrue(html.contains(
                        "href=\"style.css?v=20260816-ios-card-fix\"")),
                () -> assertTrue(html.contains(
                        "src=\"app.js?v=20260816-bilingual-language-names\"")),
                () -> assertFalse(html.contains("href=\"style.css\"")),
                () -> assertFalse(html.contains("src=\"app.js\"")));
    }

    @Test
    void homeShowsArchiveScaleFromCheckedAggregateResources() throws IOException {
        String html = read(STATIC_ROOT.resolve("index.html"));
        String script = read(STATIC_ROOT.resolve("app.js"));
        String css = read(STATIC_ROOT.resolve("style.css"));
        Map<String, Object> summary = readJson(
                STATIC_ROOT.resolve("config/archive-summary.json"));
        Map<String, Object> languages = readJson(
                STATIC_ROOT.resolve("config/supported-languages.json"));

        assertAll(
                () -> assertEquals(440, ((Number) summary.get("passages")).intValue()),
                () -> assertEquals(39, ((Number) summary.get("authors")).intValue()),
                () -> assertEquals(19, objectList(languages.get("languages")).size()),
                () -> assertTrue(html.contains(
                        "id=\"archiveScale\" class=\"archive-scale\">440 passages · 39 authors · 19 languages</p>")),
                () -> assertTrue(script.contains(
                        "const ARCHIVE_SUMMARY_URL = \"/config/archive-summary.json\"")),
                () -> assertTrue(script.contains("loadArchiveSummary()")),
                () -> assertTrue(script.contains("supportedLanguages.length} languages")),
                () -> assertTrue(script.contains("archiveScale.textContent =")),
                () -> assertTrue(css.contains(".archive-scale {")),
                () -> assertTrue(between(css, ".archive-scale {", ".oracle-form {")
                        .contains("overflow-wrap: anywhere")),
                () -> assertFalse(between(css, ".archive-scale {", ".oracle-form {")
                        .contains("white-space: nowrap")));
    }

    @Test
    void interfaceIsPermanentlyEnglishAndOnlyLoadsTheEnglishResource() throws IOException {
        String html = read(STATIC_ROOT.resolve("index.html"));
        String script = read(STATIC_ROOT.resolve("app.js"));

        Set<String> resources;
        try (Stream<Path> files = Files.list(I18N_ROOT)) {
            resources = files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(path -> path.getFileName().toString())
                    .collect(java.util.stream.Collectors.toSet());
        }

        assertAll(
                () -> assertEquals(Set.of("en.json"), resources),
                () -> assertTrue(html.contains("<html lang=\"en\" dir=\"ltr\">")),
                () -> assertTrue(html.contains("What has been on your mind?")),
                () -> assertTrue(html.contains("placeholder=\"Write here…\"")),
                () -> assertTrue(html.contains(">Result<") && html.contains(">Details<")),
                () -> assertTrue(script.contains("const ENGLISH_UI_URL = \"/i18n/en.json\"")),
                () -> assertFalse(script.contains("setInterfaceLanguage")),
                () -> assertFalse(script.contains("document.documentElement.lang")),
                () -> assertFalse(script.contains("document.documentElement.dir")),
                () -> assertFalse(script.contains("`/i18n/${")));
    }

    @Test
    void languageControlUsesSharedConfigurationOnlyForPassageLanguage() throws IOException {
        Map<String, Object> config = readJson(STATIC_ROOT.resolve("config/supported-languages.json"));
        Map<String, String> nativeNames = objectList(config.get("languages")).stream()
                .collect(java.util.stream.Collectors.toMap(
                        language -> String.valueOf(language.get("code")),
                        language -> String.valueOf(language.get("name"))));
        String html = read(STATIC_ROOT.resolve("index.html"));
        String script = read(STATIC_ROOT.resolve("app.js"));
        String css = read(STATIC_ROOT.resolve("style.css"));

        assertAll(
                () -> assertEquals(19, objectList(config.get("languages")).size()),
                () -> assertTrue(html.contains(
                        "<label for=\"languageSelect\">Show passage in:</label>")),
                () -> assertTrue(html.contains("aria-label=\"Show passage in\"")),
                () -> assertTrue(html.contains("id=\"languageStatus\"") && html.contains("aria-live=\"polite\"")),
                () -> assertTrue(html.contains(">English</output>")),
                () -> assertTrue(script.contains("const CONFIG_URL = \"/config/supported-languages.json\"")),
                () -> assertTrue(script.contains(
                        "autoOption.textContent = \"Based on input\"")),
                () -> assertFalse(script.contains("autoOption.textContent = \"Auto\"")),
                () -> assertTrue(script.contains("nativeName: String(language.name)")),
                () -> assertTrue(script.contains("language.code === FALLBACK_LANGUAGE")),
                () -> assertTrue(script.contains("`${language.nativeName} · ${language.englishName}`")),
                () -> assertEquals("简体中文", nativeNames.get("zh-Hans")),
                () -> assertEquals("日本語", nativeNames.get("ja")),
                () -> assertEquals("العربية", nativeNames.get("ar")),
                () -> assertTrue(script.contains("\"zh-Hans\": \"Simplified Chinese\"")),
                () -> assertTrue(script.contains("ja: \"Japanese\"")),
                () -> assertTrue(script.contains("ar: \"Arabic\"")),
                () -> assertTrue(script.contains("option.dir = \"ltr\"")),
                () -> assertFalse(script.contains(".reverse()")),
                () -> assertTrue(script.contains("language: manualLanguage")),
                () -> assertTrue(script.contains("browserLanguage")),
                () -> assertTrue(script.contains("manualLanguage !== \"auto\"")),
                () -> assertTrue(script.contains("languageStatus.hidden = true")),
                () -> assertTrue(script.contains("languageStatus.hidden = false")),
                () -> assertTrue(script.contains(
                        "languageStatus.textContent = languageNameFor(browserLanguage)")),
                () -> assertTrue(script.contains(
                        "languageStatus.textContent = `${languageNameFor(autoLanguage)}${uncertainty}`")),
                () -> assertTrue(css.contains(".language-control output::before")),
                () -> assertTrue(css.contains("content: \"·\"")),
                () -> assertFalse(script.contains("Selected: ${languageNameFor")),
                () -> assertFalse(script.contains("Detected: ${languageNameFor")));
    }

    @Test
    void cardFrontRendersOnlyApiDisplayPassageAndEnglishBibliographicCopy() throws IOException {
        String html = read(STATIC_ROOT.resolve("index.html"));
        String script = read(STATIC_ROOT.resolve("app.js"));
        String front = between(html, "<section id=\"cardFront\"", "<section id=\"cardBack\"");

        assertAll(
                () -> assertTrue(front.contains("id=\"displayLanguageLabel\"")),
                () -> assertTrue(front.contains("id=\"displayText\"")),
                () -> assertTrue(front.contains("id=\"frontAuthor\"")),
                () -> assertTrue(front.contains("id=\"frontWork\" lang=\"en\"")),
                () -> assertTrue(front.contains("id=\"typeStamp\"")),
                () -> assertFalse(front.contains("themeTags")),
                () -> assertFalse(front.contains("originalFrontBlock")),
                () -> assertFalse(front.contains("frontOriginalText")),
                () -> assertFalse(front.contains("frontOriginalLabel")),
                () -> assertFalse(front.contains("id=\"prompt\"")),
                () -> assertTrue(script.contains("const displayTextValue = String(data.displayText)")),
                () -> assertTrue(script.contains("setText(\"displayText\", displayTextValue)")),
                () -> assertTrue(script.contains("const englishWorkTitle = data.englishWorkTitle")),
                () -> assertFalse(script.contains("data.localizedWorkTitle ||")),
                () -> assertFalse(script.contains("setText(\"displayText\", text")));
    }

    @Test
    void detailsSideUsesTheRequiredOrderAndEnglishContextFields() throws IOException {
        String html = read(STATIC_ROOT.resolve("index.html"));
        String script = read(STATIC_ROOT.resolve("app.js"));
        String back = between(html, "<section id=\"cardBack\"", "</article>");

        int original = back.indexOf(">Original<");
        int source = back.indexOf(">Source<");
        int context = back.indexOf(">About this passage<");
        int author = back.indexOf(">About the author<");

        assertAll(
                () -> assertTrue(original >= 0 && original < source && source < context && context < author),
                () -> assertTrue(back.contains("id=\"originalText\"")),
                () -> assertTrue(back.contains("id=\"originalLanguageLabel\"")),
                () -> assertTrue(back.contains("id=\"originalWorkTitle\"")),
                () -> assertTrue(back.contains("id=\"originalAuthor\"")),
                () -> assertTrue(back.contains("id=\"originalYear\"")),
                () -> assertTrue(back.contains("id=\"originalType\"")),
                () -> assertTrue(back.contains("id=\"source\"")),
                () -> assertTrue(back.contains("class=\"record-secondary-metadata\"")),
                () -> assertTrue(script.contains("setText(\"contextNote\", data.englishContextNote")),
                () -> assertTrue(script.contains("setText(\"authorBio\", data.englishAuthorBio")),
                () -> assertTrue(script.contains("formatTranslationNote(data.englishTranslationNote)")),
                () -> assertTrue(script.contains("Machine-assisted project translation.")),
                () -> assertFalse(script.contains("data.localizedContextNote ||")),
                () -> assertFalse(script.contains("data.localizedAuthorBio ||")));
    }

    @Test
    void resultViewNumbersRemainRemovedWhileSavedRegisterUsesArchiveIds() throws IOException {
        String html = read(STATIC_ROOT.resolve("index.html"));
        String script = read(STATIC_ROOT.resolve("app.js"));
        String resultView = between(html, "<section id=\"resultView\"", "<section id=\"savedView\"");

        assertAll(
                () -> assertFalse(resultView.contains("Request card")),
                () -> assertFalse(resultView.contains("No. 01")),
                () -> assertFalse(resultView.contains("No. 0001")),
                () -> assertFalse(resultView.contains("recordNumber")),
                () -> assertFalse(script.contains("formatRecordId")),
                () -> assertTrue(script.contains("recordNumber.className = \"saved-card-number\"")),
                () -> assertTrue(script.contains(
                        "`No. ${String(passage.id).padStart(4, \"0\")}`")),
                () -> assertTrue(script.contains("excludedIds: readRecentIds()")),
                () -> assertTrue(script.contains("rememberResult(data.id)")),
                () -> assertTrue(script.contains(".slice(0, 10)")));
    }

    @Test
    void resultMetadataShowsOriginalLanguageWithoutThemeOrSelectionReason() throws IOException {
        String html = read(STATIC_ROOT.resolve("index.html"));
        String script = read(STATIC_ROOT.resolve("app.js"));
        String css = read(STATIC_ROOT.resolve("style.css"));
        Map<String, Object> englishResource = readJson(I18N_ROOT.resolve("en.json"));
        Map<String, Object> resultTranslations = objectMap(englishResource.get("result"));
        String metadata = between(html, "<div id=\"drawExplanation\"", "</div>");
        String staticFrontend = html + script + read(I18N_ROOT.resolve("en.json"));

        int originalLanguage = metadata.indexOf(">Original language<");
        int chanceLevel = metadata.indexOf(">Chance level<");
        int archiveOpenedTo = metadata.indexOf(">Archive opened to<");

        assertAll(
                () -> assertEquals(3, count(metadata, "<p>")),
                () -> assertTrue(originalLanguage >= 0
                        && originalLanguage < chanceLevel
                        && chanceLevel < archiveOpenedTo),
                () -> assertTrue(metadata.contains("id=\"resultOriginalLanguage\"")),
                () -> assertTrue(metadata.contains("id=\"chanceLevel\"")),
                () -> assertTrue(metadata.contains("id=\"candidatePoolSize\"")),
                () -> assertTrue(html.contains("class=\"draw-explanation\" aria-label=\"Passage details\"")),
                () -> assertTrue(css.contains(".draw-explanation {")),
                () -> assertTrue(css.contains("grid-template-columns: repeat(3, 1fr)")),
                () -> assertTrue(css.contains(".draw-explanation p + p")),
                () -> assertEquals(Set.of("originalLanguageLabel", "chanceLevelLabel",
                        "archiveOpenedToLabel", "detailsAriaLabel"),
                        resultTranslations.keySet()),
                () -> assertEquals("Passage details",
                        resultTranslations.get("detailsAriaLabel")),
                () -> assertFalse(englishResource.containsKey("themes")),
                () -> assertTrue(script.contains(
                        "const originalLanguage = String(data.originalLanguage || \"\")")),
                () -> assertTrue(script.contains(
                        "const originalLanguageName = languageNameFor(originalLanguage)")),
                () -> assertTrue(script.contains(
                        "setText(\"resultOriginalLanguage\", originalLanguageName")),
                () -> assertTrue(script.contains(
                        "languageByCode.get(code)?.englishName || ENGLISH_LANGUAGE_NAMES[code]")),
                () -> assertTrue(script.contains("en: \"English\"")),
                () -> assertTrue(script.contains("ja: \"Japanese\"")),
                () -> assertTrue(script.contains("ru: \"Russian\"")),
                () -> assertTrue(script.contains("lzh: \"Literary Chinese\"")),
                () -> assertTrue(script.contains("fa: \"Persian\"")),
                () -> assertTrue(script.contains("grc: \"Ancient Greek\"")),
                () -> assertTrue(script.contains("la: \"Latin\"")),
                () -> assertTrue(script.contains("t(\"result.detailsAriaLabel\"")),
                () -> assertFalse(staticFrontend.contains("Found through")),
                () -> assertFalse(staticFrontend.contains("In this passage")),
                () -> assertFalse(staticFrontend.contains("No explicit theme")),
                () -> assertFalse(script.contains("matchedThemes")),
                () -> assertFalse(script.contains("normalizeThemes")),
                () -> assertFalse(script.contains("themes.${")),
                () -> assertFalse(html.contains("Selection details")));
    }

    @Test
    void privacyAndCrisisGuidanceRemainExplicitAndSecretsStayServerSide() throws IOException {
        String html = read(STATIC_ROOT.resolve("index.html"));
        String script = read(STATIC_ROOT.resolve("app.js"));
        String englishResource = read(I18N_ROOT.resolve("en.json"));
        String staticFrontend = html + script + englishResource;
        String crisisGuidance = "If you may hurt yourself or are in immediate danger, "
                + "contact your local emergency services now. Contact someone you trust and ask them "
                + "to stay with you while you reach support. You can also find confidential support "
                + "in your country through Find a Helpline.";

        assertAll(
                () -> assertEquals(1, count(html,
                        "Input may be sent to external services for semantic matching and safety checks")),
                () -> assertEquals(1, count(html, "this project does not store the input")),
                () -> assertEquals(3, count(staticFrontend, crisisGuidance)),
                () -> assertTrue(html.contains("href=\"https://findahelpline.com\"")),
                () -> assertTrue(html.contains(
                        "aria-label=\"Find confidential crisis support in your country\"")),
                () -> assertFalse(html.contains("Google Gemini")),
                () -> assertFalse(html.contains("GEMINI_API_KEY")),
                () -> assertFalse(html.contains("JINA_API_KEY")),
                () -> assertFalse(staticFrontend.contains("CONTENT_SAFETY_KEY")),
                () -> assertFalse(staticFrontend.contains("CONTENT_SAFETY_ENDPOINT")),
                () -> assertFalse(html.contains("Input is used for the current draw and is not stored.")),
                () -> assertFalse(englishResource.contains("Input is used for the current draw")),
                () -> assertFalse(html.contains("privacyNote")),
                () -> assertEquals(1, count(html, "Let words find you across time.")),
                () -> assertTrue(html.contains("<p class=\"home-intro\">Let words find you across time.</p>")));
    }

    @Test
    void concerningInputRequiresAnInMemorySafetyConfirmationBeforeRetrying() throws IOException {
        String html = read(STATIC_ROOT.resolve("index.html"));
        String script = read(STATIC_ROOT.resolve("app.js"));
        Map<String, Object> englishResource = readJson(I18N_ROOT.resolve("en.json"));
        Map<String, Object> safetyCopy = objectMap(englishResource.get("safetyConfirmation"));
        String confirmation = between(html, "<section id=\"safetyConfirmationView\"", "</section>");

        assertAll(
                () -> assertTrue(confirmation.contains("aria-labelledby=\"safetyConfirmationTitle\"")),
                () -> assertTrue(confirmation.contains("aria-live=\"polite\"")),
                () -> assertTrue(confirmation.contains(">Are you safe right now?</h2>")),
                () -> assertTrue(confirmation.contains(
                        "If you may act on thoughts of hurting yourself, immediate human support matters more than a literary passage.")),
                () -> assertTrue(confirmation.contains(">I need immediate support</button>")),
                () -> assertTrue(confirmation.contains(">I’m safe right now — continue</button>")),
                () -> assertEquals("Are you safe right now?", safetyCopy.get("title")),
                () -> assertEquals("If you may act on thoughts of hurting yourself, immediate human support matters more than a literary passage.",
                        safetyCopy.get("message")),
                () -> assertTrue(script.contains("data.safetyConfirmationRequired === true")),
                () -> assertTrue(script.contains("let pendingSafetyRequest = null")),
                () -> assertTrue(script.contains("pendingSafetyRequest = { ...requestPayload }")),
                () -> assertTrue(script.contains("{ ...pendingSafetyRequest, safetyAcknowledged: true }")),
                () -> assertTrue(script.contains("void beginDraw(acknowledgedRequest)")),
                () -> assertTrue(script.contains("pendingSafetyRequest = null;\n    renderCrisis()")),
                () -> assertFalse(script.contains("localStorage.setItem(\"pendingSafetyRequest")),
                () -> assertFalse(script.contains("localStorage.getItem(\"pendingSafetyRequest")));
    }

    @Test
    void savedPassagesExposeTheRequiredTextControlsAndEmptyState() throws IOException {
        String html = read(STATIC_ROOT.resolve("index.html"));
        String savedView = between(html, "<section id=\"savedView\"", "<section id=\"safetyConfirmationView\"");
        String resultActions = between(html, "<div class=\"result-actions\">", "</div>");

        assertAll(
                () -> assertTrue(html.contains("id=\"savedPassagesButton\"")),
                () -> assertTrue(html.contains("id=\"savedPassagesLabel\"")),
                () -> assertTrue(html.contains("id=\"savedPassagesSeparator\"")),
                () -> assertTrue(html.contains("id=\"savedPassagesCount\">0</span>")),
                () -> assertTrue(html.contains(">Saved passages</span>")),
                () -> assertTrue(resultActions.contains("id=\"savePassageButton\"")),
                () -> assertTrue(resultActions.contains("aria-pressed=\"false\"")),
                () -> assertTrue(resultActions.contains(">Save passage</button>")),
                () -> assertTrue(savedView.contains("aria-labelledby=\"savedTitle\"")),
                () -> assertTrue(savedView.contains("id=\"savedGrid\"")
                        && savedView.contains("role=\"list\"")),
                () -> assertTrue(savedView.contains(
                        "id=\"savedViewCount\" aria-live=\"polite\" aria-label=\"0 saved records\">0 records</output>")),
                () -> assertTrue(savedView.contains("No saved passages yet.")),
                () -> assertTrue(savedView.contains("Save a passage when one finds you.")),
                () -> assertTrue(savedView.contains(">Back to the Archive</button>")),
                () -> assertTrue(savedView.contains(
                        "Saved only in this browser. Clearing browser data will remove these passages.")),
                () -> assertFalse(savedView.contains("Clear all")),
                () -> assertFalse(savedView.contains("<svg")),
                () -> assertFalse(savedView.contains("bookmark")));
    }

    @Test
    void savedPassageStorageUsesAnExplicitPrivacyPreservingWhitelist() throws IOException {
        String script = read(STATIC_ROOT.resolve("app.js"));
        String creator = between(script, "function createSavedPassage", "function readSavedPassages");
        String normalizer = between(script, "function normalizeSavedPassage", "function savedString");
        List<String> requiredFields = List.of("id", "displayLanguage", "displayText",
                "passageOriginal", "originalLanguage", "author", "workTitle",
                "originalWorkTitle", "year", "type", "sourceUrl", "publicDomainStatus", "englishContext",
                "englishAuthorBio", "themes", "savedAt");
        List<String> forbiddenFields = List.of("requestPayload", "pendingSafetyRequest", "promptInput",
                "chance", "semanticScore", "finalScore", "matchedThemes", "semanticMode",
                "languageConfidence", "safetyAcknowledged", "crisis", "message", "apiKey");

        assertAll(
                () -> assertTrue(script.contains(
                        "const SAVED_STORAGE_KEY = \"literaryOracle.saved.v1\"")),
                () -> requiredFields.forEach(field -> assertTrue(creator.contains(field), field)),
                () -> requiredFields.forEach(field -> assertTrue(normalizer.contains(field), field)),
                () -> forbiddenFields.forEach(field -> assertFalse(creator.contains(field), field)),
                () -> assertTrue(creator.contains("themes: data.themes")),
                () -> assertTrue(creator.contains(
                        "originalWorkTitle: data.originalWorkTitle || workTitle")),
                () -> assertTrue(normalizer.contains(
                        "const originalWorkTitle = savedString(value.originalWorkTitle, 1000) || workTitle")),
                () -> assertTrue(script.contains("originalWorkTitle: passage.originalWorkTitle")),
                () -> assertTrue(script.contains(
                        "window.localStorage.setItem(SAVED_STORAGE_KEY, JSON.stringify(passages))")),
                () -> assertFalse(script.contains("JSON.stringify(currentResult")),
                () -> assertFalse(script.contains("JSON.stringify(data)")),
                () -> assertTrue(script.contains("[\"http:\", \"https:\"].includes(url.protocol)")),
                () -> assertTrue(script.contains("excerpt.textContent = passage.displayText")),
                () -> assertTrue(script.contains("author.textContent = passage.author")),
                () -> assertTrue(script.contains("work.textContent = passage.workTitle")),
                () -> assertFalse(script.contains("window.alert(")),
                () -> assertFalse(script.contains("alert(")));
    }

    @Test
    void savedPassageInteractionsCoverPersistenceDeduplicationRemovalAndFullCardOpening()
            throws IOException {
        String html = read(STATIC_ROOT.resolve("index.html"));
        String script = read(STATIC_ROOT.resolve("app.js"));

        assertAll(
                () -> assertTrue(script.contains("savedPassages = readSavedPassages()")),
                () -> assertTrue(script.contains("window.addEventListener(\"storage\", handleSavedStorageChange)")),
                () -> assertTrue(script.contains("savedPassages.some(passage => passage.id === id)")),
                () -> assertTrue(script.contains("savedPassages.filter(passage => passage.id !== id)")),
                () -> assertTrue(script.contains("normalized.sort((left, right) => right.savedAt - left.savedAt)")),
                () -> assertTrue(script.contains("const ids = new Set()")),
                () -> assertTrue(script.contains(
                        "savedViewCount.textContent = `${count} ${count === 1 ? \"record\" : \"records\"}`")),
                () -> assertTrue(script.contains("savePassageButton.setAttribute(\"aria-pressed\"")),
                () -> assertTrue(script.contains("isSaved ? \"Saved\" : \"Save passage\"")),
                () -> assertTrue(script.contains("event.stopPropagation()")),
                () -> assertTrue(script.contains("openButton.type = \"button\"")),
                () -> assertTrue(script.contains("remove.type = \"button\"")),
                () -> assertTrue(script.contains("sides.className = \"saved-card-sides\"")),
                () -> assertTrue(script.contains("front.className = \"saved-card-front\"")),
                () -> assertTrue(script.contains("back.className = \"saved-card-back\"")),
                () -> assertTrue(script.contains("front.append(frontType, frontLanguage, excerpt, credit)")),
                () -> assertTrue(script.contains("back.append(originalHeading, original, metadata, backDetails)")),
                () -> assertTrue(script.contains("createSavedBackSection(\"About this passage\"")),
                () -> assertTrue(script.contains("createSavedBackSection(\"About the author\"")),
                () -> assertTrue(script.contains("createSavedBackSection(\"Source\"")),
                () -> assertTrue(script.contains("createSavedBackSection(\"Copyright status\"")),
                () -> assertTrue(script.contains("renderResult(savedPassageToResult(passage), "
                        + "{ fromSaved: true, savedRecord: passage })")),
                () -> assertTrue(script.contains("document.querySelector(\"#drawExplanation\").hidden = fromSaved")),
                () -> assertTrue(script.contains("drawAgainButton.hidden = fromSaved")),
                () -> assertTrue(script.contains("resultReturnView === \"saved\"")),
                () -> assertTrue(script.contains("savedEmptyState.hidden = savedPassages.length > 0")),
                () -> assertTrue(script.contains("if (!views.saved.hidden)")),
                () -> assertTrue(script.contains("savedPassagesLabel.textContent = onSavedView "
                        + "? \"Back to the Archive\" : \"Saved passages\"")),
                () -> assertTrue(script.contains("savedPassagesSeparator.hidden = onSavedView")),
                () -> assertTrue(script.contains("savedPassagesCount.hidden = onSavedView")),
                () -> assertTrue(script.contains("savedPassagesButton.removeAttribute(\"aria-current\")")),
                () -> assertTrue(html.contains("id=\"saveStorageError\"")
                        && html.contains("id=\"savedStorageError\"")),
                () -> assertTrue(script.contains("Saved passages are unavailable in this browser.")),
                () -> assertTrue(script.contains("Some saved passages could not be read.")),
                () -> assertTrue(script.contains("Browser storage may be full.")));
    }

    @Test
    void shareCardHasBothKeyboardAccessibleEntrypointsAndAnIndependentPreviewView()
            throws IOException {
        String html = read(STATIC_ROOT.resolve("index.html"));
        String script = read(STATIC_ROOT.resolve("app.js"));
        String resultActions = between(html, "<div class=\"result-actions\">", "</div>");
        String shareView = between(html, "<section id=\"shareView\"",
                "<section id=\"safetyConfirmationView\"");
        String safetyViews = between(html, "<section id=\"safetyConfirmationView\"", "</main>");

        assertAll(
                () -> assertTrue(resultActions.contains(
                        "id=\"shareCardButton\" class=\"secondary-button share-card-button\" type=\"button\"")),
                () -> assertTrue(resultActions.contains(">Share card</button>")),
                () -> assertTrue(script.contains("share.className = \"saved-card-share\"")),
                () -> assertTrue(script.contains("share.type = \"button\"")),
                () -> assertTrue(script.contains("share.textContent = \"Share card\"")),
                () -> assertTrue(script.contains("event.stopPropagation()")),
                () -> assertTrue(script.contains(
                        "void prepareShareCard(savedPassageToResult(passage), \"saved\", share)")),
                () -> assertTrue(shareView.contains("aria-labelledby=\"shareTitle\"")),
                () -> assertTrue(shareView.contains("id=\"sharePreparingStatus\"")),
                () -> assertTrue(shareView.contains("role=\"status\"")),
                () -> assertTrue(shareView.contains("Preparing card")),
                () -> assertTrue(shareView.contains(
                        "id=\"sharePreviewImage\" class=\"share-preview-image\"")),
                () -> assertTrue(shareView.contains(
                        "id=\"nativeShareButton\" class=\"primary-button compact\" type=\"button\" hidden>Share</button>")),
                () -> assertTrue(shareView.contains(
                        "id=\"downloadShareButton\" class=\"secondary-button\" type=\"button\" disabled>Download image</button>")),
                () -> assertTrue(shareView.contains(
                        "id=\"shareBackButton\" class=\"text-button\" type=\"button\">Back</button>")),
                () -> assertTrue(shareView.contains(
                        "Created in your browser. Your words are never included.")),
                () -> assertTrue(shareView.contains("id=\"shareError\"")
                        && shareView.contains("role=\"alert\"")),
                () -> assertFalse(safetyViews.contains("Share card")),
                () -> assertFalse(safetyViews.contains("shareCardButton")),
                () -> assertFalse(safetyViews.contains("saved-card-share")));
    }

    @Test
    void sharePayloadIsAValidatedPublicArchiveFieldWhitelist() throws IOException {
        String html = read(STATIC_ROOT.resolve("index.html"));
        String script = read(STATIC_ROOT.resolve("app.js"));
        String payloadCreator = between(script, "function createSharePayload", "function shareString");
        String sharing = between(script, "async function sharePreparedCard", "function downloadPreparedCard");
        String shareFeature = between(script, "async function prepareShareCard", "function renderCrisis");
        List<String> requiredFields = List.of("id", "displayLanguage", "displayText",
                "originalLanguage", "passageOriginal", "author", "localizedWorkTitle",
                "originalWorkTitle", "year", "sourceUrl", "sourceLabel");
        List<String> forbiddenFields = List.of("prompt", "promptInput", "requestPayload",
                "pendingSafetyRequest", "safetyAcknowledged", "safetyAssessment", "crisis",
                "matchedThemes", "themes", "chance", "semanticScore", "finalScore",
                "candidatePoolSize", "semanticMode", "savedAt", "apiKey");

        assertAll(
                () -> requiredFields.forEach(field -> assertTrue(payloadCreator.contains(field), field)),
                () -> forbiddenFields.forEach(field -> assertFalse(payloadCreator.contains(field), field)),
                () -> assertTrue(payloadCreator.contains("return Object.freeze({")),
                () -> assertTrue(payloadCreator.contains("data.canonicalAuthor || data.author")),
                () -> assertTrue(payloadCreator.contains("data.originalWorkTitle")),
                () -> assertTrue(sharing.contains("files: [shareState.file]")),
                () -> assertTrue(sharing.contains("payload.author")),
                () -> assertTrue(sharing.contains("payload.localizedWorkTitle")),
                () -> assertTrue(sharing.contains("payload.year")),
                () -> assertTrue(sharing.contains("payload.sourceUrl")),
                () -> assertTrue(sharing.contains("Shared from Literary Oracle")),
                () -> forbiddenFields.forEach(field -> assertFalse(sharing.contains(field), field)),
                () -> assertFalse(shareFeature.contains("localStorage")),
                () -> assertFalse(shareFeature.contains("sessionStorage")),
                () -> assertFalse(shareFeature.contains("fetch(")),
                () -> assertFalse(script.contains("innerHTML")),
                () -> assertEquals(1, count(html, "<script")),
                () -> assertTrue(html.contains("<script src=\"app.js?v=20260816-bilingual-language-names\"></script>")),
                () -> assertFalse(html.contains("html2canvas")),
                () -> assertFalse(html.contains("dom-to-image")));
    }

    @Test
    void shareCanvasIncludesTheCompleteOriginalOnlyWhenLanguagesDiffer() throws IOException {
        String script = read(STATIC_ROOT.resolve("app.js"));
        String renderer = between(script, "function renderShareCanvas", "function wrapCanvasText");
        String drawer = between(script, "function drawShareCard", "function drawShareBackground");
        String background = between(script, "function drawShareBackground",
                "function roundedShareRect");
        String wrapper = between(script, "function wrapCanvasText", "function canvasWrapSegments");

        assertAll(
                () -> assertTrue(script.contains("const SHARE_CARD_WIDTH = 1080")),
                () -> assertTrue(script.contains("const SHARE_CARD_MIN_HEIGHT = 1350")),
                () -> assertTrue(script.contains("const SHARE_CARD_MAX_HEIGHT = 1920")),
                () -> assertTrue(renderer.contains(
                        "const includeOriginal = payload.displayLanguage !== payload.originalLanguage")),
                () -> assertTrue(renderer.contains("includeOriginal\n      ? wrapCanvasText(context, "
                        + "payload.passageOriginal")),
                () -> assertTrue(renderer.contains(": []")),
                () -> assertTrue(renderer.contains("for (let displayFontSize = 52; displayFontSize >= 34;")),
                () -> assertTrue(renderer.contains("const originalFontSize = Math.max(27")),
                () -> assertTrue(renderer.contains("candidate.requiredHeight <= SHARE_CARD_MIN_HEIGHT")),
                () -> assertTrue(renderer.contains(
                        "minimumReadableLayout.requiredHeight <= SHARE_CARD_MAX_HEIGHT")),
                () -> assertTrue(renderer.contains("Math.max(SHARE_CARD_MIN_HEIGHT")),
                () -> assertTrue(drawer.contains("if (layout.includeOriginal)")),
                () -> assertTrue(drawer.contains("layout.originalLines")),
                () -> assertTrue(drawer.contains("Project translation")),
                () -> assertTrue(drawer.contains("layout.localizedTitleLines")),
                () -> assertTrue(drawer.contains("payload.originalWorkTitle")
                        || renderer.contains("payload.originalWorkTitle")),
                () -> assertTrue(renderer.contains("Original language:")),
                () -> assertTrue(renderer.contains("Source: ${payload.sourceLabel}")),
                () -> assertTrue(renderer.contains("originalLanguageLines")),
                () -> assertTrue(renderer.contains("sourceLabelLines")),
                () -> assertTrue(drawer.contains("context.fillStyle = \"#E5E8E5\"")),
                () -> assertTrue(drawer.contains("context.shadowColor")),
                () -> assertTrue(drawer.contains("context.lineWidth = 3")),
                () -> assertTrue(background.contains("context.fillStyle = \"#56635A\"")),
                () -> assertTrue(background.contains("tile.width = 52")
                        && background.contains("tile.height = 52")),
                () -> assertTrue(background.contains("rgba(196, 202, 197, .075)")),
                () -> assertTrue(background.contains("for (let petal = 0; petal < 4;")),
                () -> assertTrue(background.contains("tileContext.bezierCurveTo")),
                () -> assertTrue(background.contains("context.createPattern(tile, \"repeat\")")),
                () -> assertFalse(script.contains("createLinearGradient")),
                () -> assertFalse(script.contains("createRadialGradient")),
                () -> assertFalse(script.contains("drawImage(")),
                () -> assertTrue(wrapper.contains("replaceAll(\"\\r\\n\", \"\\n\")")),
                () -> assertTrue(wrapper.contains("split(\"\\n\")")),
                () -> assertTrue(wrapper.contains("lines.push(\"\")")),
                () -> assertFalse(wrapper.contains("slice(")),
                () -> assertFalse(wrapper.contains("substring(")),
                () -> assertFalse(wrapper.contains("substr(")));
    }

    @Test
    void shareSourceAndFilenameAreSafeAndDoNotExposeLongUrlsInTheImage() throws IOException {
        String script = read(STATIC_ROOT.resolve("app.js"));
        String sourceNormalizer = between(script, "function normalizeShareSource",
                "function shareSourceLabel");
        String sourceLabels = between(script, "function shareSourceLabel",
                "function shareCardFilename");
        String filename = between(script, "function shareCardFilename",
                "async function waitForShareFonts");
        String drawer = between(script, "function drawShareCard", "function drawShareBackground");
        String sharing = between(script, "async function sharePreparedCard", "function downloadPreparedCard");

        assertAll(
                () -> assertTrue(sourceNormalizer.contains("new URL(value)")),
                () -> assertTrue(sourceNormalizer.contains("[\"http:\", \"https:\"].includes(url.protocol)")),
                () -> assertTrue(sourceNormalizer.contains("!url.hostname")),
                () -> assertTrue(sourceNormalizer.contains("url.username || url.password")),
                () -> assertTrue(sourceNormalizer.contains("catch")),
                () -> assertTrue(sourceNormalizer.contains("return null")),
                () -> assertTrue(sourceLabels.contains("wikisource.org")
                        && sourceLabels.contains("Wikisource")),
                () -> assertTrue(sourceLabels.contains("gutenberg.org")
                        && sourceLabels.contains("Project Gutenberg")),
                () -> assertTrue(sourceLabels.contains("gutenberg.net.au")
                        && sourceLabels.contains("Project Gutenberg Australia")),
                () -> assertTrue(sourceLabels.contains("projekt-gutenberg.org")
                        && sourceLabels.contains("Projekt Gutenberg-DE")),
                () -> assertTrue(sourceLabels.contains("aozora.gr.jp")
                        && sourceLabels.contains("Aozora Bunko")),
                () -> assertTrue(sourceLabels.contains("replace(/^www\\./, \"\")")),
                () -> assertTrue(filename.contains(
                        "`literary-oracle-no-${String(id).padStart(4, \"0\")}.png`")),
                () -> assertFalse(filename.contains("author")),
                () -> assertFalse(filename.contains("displayText")),
                () -> assertFalse(filename.contains("prompt")),
                () -> assertTrue(script.contains("Source: ${payload.sourceLabel}")),
                () -> assertFalse(drawer.contains("payload.sourceUrl")),
                () -> assertTrue(sharing.contains("payload.sourceUrl")));
    }

    @Test
    void shareCanvasWaitsForFontsAndWrapsMultilingualTextWithoutBreakingGraphemes()
            throws IOException {
        String script = read(STATIC_ROOT.resolve("app.js"));
        String fontWait = between(script, "async function waitForShareFonts",
                "function renderShareCanvas");
        String segments = between(script, "function canvasWrapSegments",
                "function isCjkShareLanguage");
        String cjk = between(script, "function isCjkShareLanguage", "function shareFontFamily");
        String fonts = between(script, "function shareFontFamily", "function setShareCanvasFont");
        String direction = between(script, "function isRtlShareLanguage", "function drawShareCard");
        String lineDrawer = between(script, "function drawShareLines", "function canvasToPngBlob");

        assertAll(
                () -> assertTrue(fontWait.contains("await document.fonts.ready")),
                () -> assertTrue(fontWait.contains("catch")),
                () -> assertTrue(segments.contains("granularity: \"word\"")),
                () -> assertTrue(segments.contains("granularity: \"grapheme\"")),
                () -> assertTrue(segments.contains("text.match(/\\S+\\s*|\\s+/gu)")),
                () -> assertTrue(segments.contains("normalize(\"NFC\")")),
                () -> assertTrue(segments.contains("\\p{Mark}")),
                () -> assertTrue(segments.contains("\\uFE00-\\uFE0F")),
                () -> assertTrue(segments.contains("\\u200D")),
                () -> assertTrue(cjk.contains("\"zh-Hans\"")
                        && cjk.contains("\"zh-Hant\"")
                        && cjk.contains("\"ja\"")
                        && cjk.contains("\"ko\"")),
                () -> assertFalse(cjk.contains("\"de\"")),
                () -> assertTrue(fonts.contains("Noto Serif SC")),
                () -> assertTrue(fonts.contains("Noto Serif TC")),
                () -> assertTrue(fonts.contains("Noto Serif JP")),
                () -> assertTrue(fonts.contains("Noto Serif KR")),
                () -> assertTrue(fonts.contains("Noto Naskh Arabic")),
                () -> assertTrue(fonts.contains("Noto Serif Devanagari")),
                () -> assertTrue(fonts.contains("Noto Serif Bengali")),
                () -> assertTrue(fonts.contains("Noto Serif Thai")),
                () -> assertTrue(fonts.contains("EB Garamond")
                        && fonts.contains("Libre Caslon Text")
                        && fonts.contains("Times New Roman")),
                () -> assertTrue(direction.contains("[\"ar\", \"fa\"].includes(language)")),
                () -> assertTrue(lineDrawer.contains("context.direction = rtl ? \"rtl\" : \"ltr\"")),
                () -> assertTrue(lineDrawer.contains("context.textAlign = rtl ? \"right\" : \"left\"")),
                () -> assertFalse(lineDrawer.contains("reverse(")));
    }

    @Test
    void shareGenerationFailureNativeShareFallbackCancellationAndObjectUrlsAreHandled()
            throws IOException {
        String script = read(STATIC_ROOT.resolve("app.js"));
        String prepare = between(script, "async function prepareShareCard",
                "function createSharePayload");
        String blobConversion = between(script, "function canvasToPngBlob",
                "function canSharePreparedFile");
        String support = between(script, "function canSharePreparedFile",
                "async function sharePreparedCard");
        String nativeShare = between(script, "async function sharePreparedCard",
                "function downloadPreparedCard");
        String download = between(script, "function downloadPreparedCard",
                "function setSharePreparing");
        String cleanup = between(script, "function returnFromSharePreview", "function renderCrisis");

        assertAll(
                () -> assertTrue(prepare.contains("if (sharePreparing) return")),
                () -> assertTrue(prepare.contains("generation !== shareGeneration")),
                () -> assertTrue(prepare.contains("document.createElement(\"canvas\")")
                        || script.contains("document.createElement(\"canvas\")")),
                () -> assertTrue(prepare.contains("canvasToPngBlob(canvas)")),
                () -> assertTrue(prepare.contains("new File([blob], filename")),
                () -> assertTrue(prepare.contains("URL.createObjectURL(blob)")),
                () -> assertTrue(script.contains("typeof sharePreviewImage.decode === \"function\"")),
                () -> assertTrue(script.contains("sharePreviewImage.decode()")),
                () -> assertTrue(prepare.contains("sharePreviewImage.alt =")),
                () -> assertTrue(prepare.contains("nativeShareButton.hidden = !canSharePreparedFile(file)")),
                () -> assertTrue(prepare.contains("downloadShareButton.disabled = false")),
                () -> assertFalse(prepare.contains("setCardFlipped(")),
                () -> assertFalse(prepare.contains("toggleCard(")),
                () -> assertTrue(prepare.contains("The image could not be generated in this browser.")),
                () -> assertTrue(blobConversion.contains("canvas.toBlob(blob =>")),
                () -> assertTrue(blobConversion.contains("blob && blob.type === \"image/png\"")),
                () -> assertTrue(blobConversion.contains("reject(new Error(")),
                () -> assertTrue(support.contains("typeof navigator.share !== \"function\"")),
                () -> assertTrue(support.contains("typeof navigator.canShare !== \"function\"")),
                () -> assertTrue(support.contains("navigator.canShare({ files: [file] })")),
                () -> assertTrue(support.contains("return false")),
                () -> assertTrue(nativeShare.contains("await navigator.share({")),
                () -> assertTrue(nativeShare.contains("error?.name !== \"AbortError\"")),
                () -> assertTrue(nativeShare.contains("nativeShareButton.disabled = false")),
                () -> assertTrue(download.contains("link.download = shareCardFilename(")),
                () -> assertTrue(download.contains("link.click()")),
                () -> assertTrue(download.contains("link.remove()")),
                () -> assertTrue(download.contains("URL.revokeObjectURL(downloadUrl)")),
                () -> assertTrue(download.contains("URL.revokeObjectURL(downloadUrl), 1000")),
                () -> assertTrue(cleanup.contains("URL.revokeObjectURL(shareState.previewUrl)")),
                () -> assertTrue(cleanup.contains("showView(returnView)")),
                () -> assertTrue(script.contains(
                        "if (name !== \"share\" && !views.share.hidden) resetSharePreview()")),
                () -> assertTrue(script.contains("window.addEventListener(\"pagehide\", resetSharePreview)")),
                () -> assertFalse(script.contains("window.alert(")),
                () -> assertFalse(script.contains("alert(")));
    }

    @Test
    void sharePreviewRemainsContainedAtThreeHundredNinetyPixels() throws IOException {
        String css = read(STATIC_ROOT.resolve("style.css"));
        String view = between(css, ".share-view {", ".share-heading {");
        String frame = between(css, ".share-preview-frame {", ".share-preview-frame::before");
        String image = between(css, ".share-preview-image {", ".share-error {");
        String mobile = between(css, "@media (max-width: 620px)",
                "@media (prefers-reduced-motion: reduce)");

        assertAll(
                () -> assertTrue(css.contains("overflow-x: hidden")),
                () -> assertTrue(view.contains("width: min(100%, 920px)")),
                () -> assertTrue(view.contains("min-width: 0")),
                () -> assertTrue(frame.contains("width: min(100%, 620px)")),
                () -> assertTrue(frame.contains("min-width: 0")),
                () -> assertTrue(frame.contains("overflow: hidden")),
                () -> assertTrue(image.contains("max-width: 100%")),
                () -> assertTrue(image.contains("height: auto")),
                () -> assertTrue(image.contains("object-fit: contain")),
                () -> assertTrue(mobile.contains(".share-preview-frame")),
                () -> assertTrue(mobile.contains("width: 100%")),
                () -> assertTrue(mobile.contains(".share-preview-image")),
                () -> assertTrue(mobile.contains("max-width: 100%")),
                () -> assertTrue(mobile.contains(".share-actions > *")));
    }

    @Test
    void savedRegisterUsesOneRecordPerRowWithSideBySideCardFaces() throws IOException {
        String css = read(STATIC_ROOT.resolve("style.css"));
        String album = between(css, ".saved-album {", ".saved-album::before");
        String grid = between(css, ".saved-grid {", ".saved-grid:empty");
        String card = between(css, ".saved-card {", ".saved-card-open");
        String sides = between(css, ".saved-card-sides {", ".saved-card-front,");
        String front = between(css, ".saved-card-front {", ".saved-card-back {");
        String passage = between(css, ".saved-card-passage {", ".saved-card-credit");
        String backText = between(css, ".saved-card-original,", ".saved-card-original {");
        String responsiveSides = between(css, "@media (max-width: 760px)",
                "@media (max-width: 620px)");
        String mobile = between(css, "@media (max-width: 620px)",
                "@media (prefers-reduced-motion: reduce)");

        assertAll(
                () -> assertTrue(album.contains("background: var(--surface)")),
                () -> assertTrue(album.contains("border: 1px solid var(--ink)")),
                () -> assertTrue(album.contains("border-radius: 0")),
                () -> assertTrue(album.contains("box-shadow: none")),
                () -> assertTrue(css.contains(".saved-album::before")),
                () -> assertTrue(css.contains("border: 1px solid rgba(111, 125, 130, .68)")),
                () -> assertTrue(grid.contains("grid-template-columns: minmax(0, 1fr)")),
                () -> assertFalse(grid.contains("repeat(2")),
                () -> assertTrue(card.contains("border: 0")),
                () -> assertTrue(card.contains("border-bottom: 1px solid")),
                () -> assertTrue(card.contains("border-radius: 0")),
                () -> assertTrue(card.contains("background: transparent")),
                () -> assertTrue(card.contains("box-shadow: none")),
                () -> assertFalse(card.contains("transform:")),
                () -> assertTrue(sides.contains("grid-template-columns: minmax(0, .92fr) minmax(0, 1.08fr)")),
                () -> assertTrue(front.contains("justify-content: center")),
                () -> assertTrue(front.contains("padding-block: 2px clamp(42px, 5vw, 64px)")),
                () -> assertTrue(passage.contains("font-size: clamp(1.45rem, 2.3vw, 1.9rem)")),
                () -> assertTrue(backText.contains("line-height: 1.38")),
                () -> assertTrue(css.contains(".saved-card-front,")),
                () -> assertTrue(css.contains(".saved-card-back {")),
                () -> assertTrue(css.contains(".saved-card-number {")),
                () -> assertTrue(css.contains("-webkit-line-clamp: 4")),
                () -> assertTrue(responsiveSides.contains(".saved-card-sides")),
                () -> assertTrue(responsiveSides.contains("grid-template-columns: minmax(0, 1fr)")),
                () -> assertTrue(responsiveSides.contains("border-inline-start: 0")),
                () -> assertTrue(mobile.contains("font-size: 1.4rem")),
                () -> assertTrue(mobile.contains("box-shadow: none")));
    }

    @Test
    void cardSupportsPointerAndKeyboardFlipWithoutCapturingLinksOrTextSelection() throws IOException {
        String html = read(STATIC_ROOT.resolve("index.html"));
        String script = read(STATIC_ROOT.resolve("app.js"));

        assertAll(
                () -> assertTrue(html.contains("id=\"archiveCard\"") && html.contains("role=\"button\"")),
                () -> assertTrue(html.contains("tabindex=\"0\"") && html.contains("aria-expanded=\"false\"")),
                () -> assertTrue(html.contains("id=\"cardStatus\"") && html.contains("aria-live=\"polite\"")),
                () -> assertTrue(script.contains("archiveCard.addEventListener(\"pointerdown\", handleCardPointerDown)")),
                () -> assertTrue(script.contains("archiveCard.addEventListener(\"pointermove\", handleCardPointerMove)")),
                () -> assertTrue(script.contains("archiveCard.addEventListener(\"click\", handleCardClick)")),
                () -> assertTrue(script.contains("archiveCard.addEventListener(\"keydown\", handleCardKeydown)")),
                () -> assertTrue(script.contains("event.key !== \"Enter\"") && script.contains("event.key !== \" \"")),
                () -> assertTrue(script.contains("\"a, button, input, select, textarea, summary")),
                () -> assertFalse(script.contains("event.target !== cardBack")),
                () -> assertTrue(script.contains("const selection = window.getSelection()")),
                () -> assertTrue(script.contains("selection && !selection.isCollapsed")),
                () -> assertTrue(script.contains("cardPointerMoved && hasActiveTextSelection()")),
                () -> assertTrue(script.contains("archiveCard.setAttribute(\"role\", flipped ? \"group\" : \"button\")")),
                () -> assertTrue(script.contains("cardFront.toggleAttribute(\"inert\", flipped)")));
    }

    @Test
    void passageDirectionChangesWithoutChangingTheEnglishPageDirection() throws IOException {
        Map<String, Object> config = readJson(STATIC_ROOT.resolve("config/supported-languages.json"));
        String html = read(STATIC_ROOT.resolve("index.html"));
        String script = read(STATIC_ROOT.resolve("app.js"));

        Map<String, Object> arabic = objectList(config.get("languages")).stream()
                .filter(language -> "ar".equals(language.get("code")))
                .findFirst().orElseThrow();

        assertAll(
                () -> assertEquals("rtl", arabic.get("direction")),
                () -> assertTrue(html.contains("<html lang=\"en\" dir=\"ltr\">")),
                () -> assertTrue(script.contains("setLanguageAttributes(document.querySelector(\"#displayText\"), displayLanguage)")),
                () -> assertTrue(script.contains("element.dir = \"auto\"")),
                () -> assertTrue(html.contains("id=\"displayText\" class=\"passage\" dir=\"auto\"")),
                () -> assertTrue(html.contains("id=\"originalText\" class=\"original-passage\" dir=\"auto\"")),
                () -> assertFalse(script.contains("document.documentElement.dir")),
                () -> assertTrue(script.contains("link.dir = \"ltr\"")));
    }

    @Test
    void keyboardFocusAndSharePreviewEscapeRemainAccessible() throws IOException {
        String html = read(STATIC_ROOT.resolve("index.html"));
        String script = read(STATIC_ROOT.resolve("app.js"));
        String css = read(STATIC_ROOT.resolve("style.css"));
        String escapeHandler = between(script, "function handleDocumentKeydown",
                "function resetSharePreview");
        String textareaRule = between(css, "textarea {", "textarea::placeholder");
        String archiveCardRule = between(css, ".archive-card {", ".card-inner {");

        assertAll(
                () -> assertTrue(script.contains(
                        "document.addEventListener(\"keydown\", handleDocumentKeydown)")),
                () -> assertTrue(escapeHandler.contains("event.key !== \"Escape\"")),
                () -> assertTrue(escapeHandler.contains("views.share.hidden")),
                () -> assertTrue(escapeHandler.contains("returnFromSharePreview()")),
                () -> assertTrue(script.contains("returnFocus.focus({ preventScroll: true })")),
                () -> assertTrue(html.contains(
                        "aria-label=\"Passage side shown; open archive details\"")),
                () -> assertTrue(script.contains(
                        "\"Details side shown; return to the passage\"")),
                () -> assertTrue(script.contains(
                        "\"Passage side shown; open archive details\"")),
                () -> assertFalse(textareaRule.contains("outline:")),
                () -> assertFalse(archiveCardRule.contains("outline:")),
                () -> assertTrue(css.contains("button:focus-visible")),
                () -> assertTrue(css.contains(".archive-card:focus-visible")),
                () -> assertTrue(css.contains("@media (prefers-reduced-motion: reduce)")),
                () -> assertTrue(html.contains("id=\"cardStatus\"")
                        && html.contains("role=\"status\"")));
    }

    @Test
    void floralWallpaperUsesFourPointedPathsWithoutEllipseOrCentreDot() throws IOException {
        String css = read(STATIC_ROOT.resolve("style.css"));

        assertAll(
                () -> assertTrue(css.contains("width='64' height='64'")),
                () -> assertEquals(4, count(css, "%3Cpath")),
                () -> assertFalse(css.contains("%3Cellipse")),
                () -> assertFalse(css.contains("%3Ccircle")),
                () -> assertTrue(css.contains("background-size: 64px 64px")),
                () -> assertTrue(css.contains("fill-opacity='.055'")));
    }

    @Test
    void visibleUppercaseGenerationRemainsRemoved() throws IOException {
        String html = read(STATIC_ROOT.resolve("index.html"));
        String script = read(STATIC_ROOT.resolve("app.js"));
        String css = read(STATIC_ROOT.resolve("style.css"));

        assertAll(
                () -> assertFalse(Pattern.compile("text-transform\\s*:\\s*uppercase", Pattern.CASE_INSENSITIVE)
                        .matcher(css).find()),
                () -> assertFalse(Pattern.compile("\\.to(?:Locale)?UpperCase\\s*\\(").matcher(script).find()),
                () -> assertFalse(html.contains("ARCHIVE COPY")),
                () -> assertFalse(html.contains("PASSAGES")),
                () -> assertFalse(html.contains(">POEM<")),
                () -> assertFalse(html.contains("LO-")));
    }

    @Test
    void reducedMotionAndLongContentRemainUsable() throws IOException {
        String script = read(STATIC_ROOT.resolve("app.js"));
        String css = read(STATIC_ROOT.resolve("style.css"));

        assertAll(
                () -> assertTrue(css.contains("@media (prefers-reduced-motion: reduce)")),
                () -> assertTrue(css.contains(".card-inner.is-flipped") && css.contains("transform: none")),
                () -> assertTrue(script.contains("activeFace.scrollHeight")),
                () -> assertTrue(script.contains("ResizeObserver")),
                () -> assertTrue(css.contains("overflow-x: hidden")),
                () -> assertTrue(css.contains("overflow-wrap: anywhere")),
                () -> assertTrue(css.contains("word-break: break-word")),
                () -> assertTrue(css.contains("@media (max-width: 620px)")));
    }

    @Test
    void cardFlipHasWebKitTransformsAndAnExclusiveIosFallback() throws IOException {
        String css = read(STATIC_ROOT.resolve("style.css"));
        String script = read(STATIC_ROOT.resolve("app.js"));
        String inner = between(css, ".card-inner {", ".card-inner.is-flipped {");
        String flipped = between(css, ".card-inner.is-flipped {", ".card-face {");
        String front = between(css, ".card-front {", ".card-back {");
        String back = between(css, ".card-back {", "@supports (-webkit-touch-callout: none)");
        String ios = between(css, "@supports (-webkit-touch-callout: none) {",
                ".card-metadata,");
        String iosFront = between(ios, ".card-front {", ".card-back {");
        String iosBack = between(ios, ".card-back {",
                ".card-inner.is-flipped .card-front {");
        String iosFlippedFront = between(ios, ".card-inner.is-flipped .card-front {",
                ".card-inner.is-flipped .card-back {");
        String iosFlippedBack = ios.substring(
                ios.indexOf(".card-inner.is-flipped .card-back {"));

        assertAll(
                () -> assertTrue(css.contains("-webkit-perspective: 1600px")),
                () -> assertTrue(inner.contains("-webkit-transform-style: preserve-3d")),
                () -> assertTrue(flipped.contains("-webkit-transform: rotateY(180deg)")),
                () -> assertTrue(flipped.contains("transform: rotateY(180deg)")),
                () -> assertTrue(front.contains("-webkit-transform: rotateY(0deg)")),
                () -> assertTrue(front.contains("transform: rotateY(0deg)")),
                () -> assertTrue(back.contains("-webkit-transform: rotateY(180deg)")),
                () -> assertTrue(back.contains("transform: rotateY(180deg)")),
                () -> assertTrue(css.contains(".card-face::before,")
                        && css.contains("-webkit-backface-visibility: hidden")),
                () -> assertTrue(ios.contains("-webkit-transform: none")),
                () -> assertTrue(ios.contains("transform-style: flat")),
                () -> assertTrue(ios.contains("transition: opacity .25s ease")),
                () -> assertTrue(iosFront.contains("visibility: visible")
                        && iosFront.contains("pointer-events: auto")),
                () -> assertTrue(iosBack.contains("visibility: hidden")
                        && iosBack.contains("pointer-events: none")),
                () -> assertTrue(iosFlippedFront.contains("visibility: hidden")
                        && iosFlippedFront.contains("pointer-events: none")),
                () -> assertTrue(iosFlippedBack.contains("visibility: visible")
                        && iosFlippedBack.contains("pointer-events: auto")),
                () -> assertTrue(script.contains("activeFace.scrollHeight")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJson(Path path) throws IOException {
        return OBJECT_MAPPER.readValue(path.toFile(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objectList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex + start.length());
        return source.substring(startIndex, endIndex);
    }

    private static int count(String source, String needle) {
        int occurrences = 0;
        int from = 0;
        while ((from = source.indexOf(needle, from)) >= 0) {
            occurrences++;
            from += needle.length();
        }
        return occurrences;
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}

package com.literaryoracle;

import java.util.EnumMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.github.pemistahl.lingua.api.Language;
import com.github.pemistahl.lingua.api.LanguageDetector;
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder;

@Service
public class LanguageDetectionService {
    private static final int MINIMUM_LETTER_COUNT = 4;
    private static final double MINIMUM_CONFIDENCE_MARGIN = 0.12;
    private static final double MIXED_SCRIPT_SHARE = 0.30;

    /*
     * Non-Latin scripts are resolved deterministically in detectByScript before
     * statistical detection. Loading their Lingua models as well only duplicates
     * that work and consumes substantial memory, especially in constrained JVMs.
     */
    private static final Language[] DETECTION_LANGUAGES = {
            Language.ENGLISH, Language.SPANISH, Language.FRENCH, Language.GERMAN,
            Language.ITALIAN, Language.PORTUGUESE, Language.SWEDISH,
            Language.INDONESIAN, Language.TURKISH, Language.VIETNAMESE
    };

    private static final Map<Language, String> LANGUAGE_CODES = languageCodes();
    private static final String TRADITIONAL_HINTS =
            "體臺灣國學時來這個為與說們後裡還發現從將會應讓無處開關於問題認識覺得願書讀長風雲"
                    + "獨難辦麼該傷愛離歸屬嗎";
    private static final String SIMPLIFIED_HINTS =
            "体台湾国学时来这个为与说们后里还发现在从将会应让无处开关于问题认识觉得愿书读长风云"
                    + "独难办么该伤爱离归属吗";

    private final SupportedLanguageCatalog languageCatalog;
    private final LanguageDetector detector;

    @Autowired
    public LanguageDetectionService(SupportedLanguageCatalog languageCatalog) {
        this(languageCatalog, DetectorHolder.INSTANCE);
    }

    LanguageDetectionService(SupportedLanguageCatalog languageCatalog, LanguageDetector detector) {
        this.languageCatalog = languageCatalog;
        this.detector = detector;
    }

    public LanguageResolution resolve(String text, String requestedLanguage, String browserLanguage) {
        Optional<String> browserCode = languageCatalog.normalizeSupportedCode(browserLanguage);
        String fallbackCode = browserCode.orElse("en");

        if (requestedLanguage != null && !requestedLanguage.isBlank()
                && !"auto".equalsIgnoreCase(requestedLanguage.strip())) {
            Optional<String> manualCode = languageCatalog.normalizeSupportedCode(requestedLanguage);
            if (manualCode.isPresent()) {
                return resolution(manualCode.get(), true, 1.0, ResolutionSource.MANUAL);
            }
            return resolution(fallbackCode, false, 0.0,
                    browserCode.isPresent() ? ResolutionSource.BROWSER_FALLBACK
                            : ResolutionSource.ENGLISH_FALLBACK);
        }

        Optional<ScriptDetection> scriptDetection = detectByScript(text, browserLanguage);
        if (scriptDetection.isPresent()) {
            ScriptDetection detected = scriptDetection.get();
            return resolution(detected.code(), detected.certain(), detected.confidence(),
                    ResolutionSource.AUTO);
        }

        if (!hasEnoughLanguageInformation(text) || hasSeverelyMixedScripts(text)) {
            return automaticFallback(text, browserCode, 0.0);
        }

        Map<Language, Double> values = detector.computeLanguageConfidenceValues(text);
        if (values.isEmpty()) {
            return automaticFallback(text, browserCode, 0.0);
        }

        Iterator<Map.Entry<Language, Double>> iterator = values.entrySet().iterator();
        Map.Entry<Language, Double> first = iterator.next();
        double secondConfidence = iterator.hasNext() ? iterator.next().getValue() : 0.0;
        double confidenceMargin = Math.max(0.0, first.getValue() - secondConfidence);
        String detectedCode = LANGUAGE_CODES.get(first.getKey());
        if (detectedCode == null || confidenceMargin < MINIMUM_CONFIDENCE_MARGIN) {
            return automaticFallback(text, browserCode, confidenceMargin);
        }

        if ("zh-Hans".equals(detectedCode)) {
            ChineseVariant variant = chineseVariant(text, browserLanguage);
            return resolution(variant.code(), variant.certain(), confidenceMargin,
                    ResolutionSource.AUTO);
        }
        return resolution(detectedCode, true, confidenceMargin, ResolutionSource.AUTO);
    }

    private LanguageResolution automaticFallback(String text, Optional<String> browserCode,
            double confidence) {
        if (containsLatinLetters(text)) {
            return resolution("en", false, confidence, ResolutionSource.ENGLISH_FALLBACK);
        }
        String fallbackCode = browserCode.orElse("en");
        return resolution(fallbackCode, false, confidence,
                browserCode.isPresent() ? ResolutionSource.BROWSER_FALLBACK
                        : ResolutionSource.ENGLISH_FALLBACK);
    }

    private LanguageResolution resolution(String code, boolean certain, double confidence,
            ResolutionSource source) {
        SupportedLanguage language = languageCatalog.requireSupported(code);
        return new LanguageResolution(language.code(), language.name(), language.englishName(),
                language.direction(), certain, confidence, source.name());
    }

    /**
     * Detects scripts whose language mapping is dependable before applying the
     * minimum-length and statistical-confidence rules. This is important for
     * the short phrases people naturally enter into the oracle.
     */
    private Optional<ScriptDetection> detectByScript(String text, String browserLanguage) {
        if (text == null || text.isBlank()) return Optional.empty();

        EnumMap<Character.UnicodeScript, Integer> counts = new EnumMap<>(Character.UnicodeScript.class);
        int letterCount = 0;
        for (int codePoint : text.codePoints().toArray()) {
            if (!Character.isLetter(codePoint)) continue;
            letterCount++;
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            counts.merge(script, 1, Integer::sum);
        }
        if (letterCount == 0) return Optional.empty();

        int hangul = scriptCount(counts, Character.UnicodeScript.HANGUL);
        int hiragana = scriptCount(counts, Character.UnicodeScript.HIRAGANA);
        int katakana = scriptCount(counts, Character.UnicodeScript.KATAKANA);
        int han = scriptCount(counts, Character.UnicodeScript.HAN);

        if (hangul > 0) return Optional.of(scriptDetection("ko", hangul, letterCount));
        if (hiragana + katakana > 0) {
            return Optional.of(scriptDetection("ja", hiragana + katakana, letterCount));
        }
        if (han > 0 && han * 2 >= letterCount) {
            ChineseVariant variant = chineseVariant(text, browserLanguage);
            return Optional.of(new ScriptDetection(variant.code(), variant.certain(),
                    scriptConfidence(han, letterCount)));
        }

        ScriptDetection direct = firstPresentScript(counts, letterCount);
        return Optional.ofNullable(direct);
    }

    private ScriptDetection firstPresentScript(EnumMap<Character.UnicodeScript, Integer> counts,
            int letterCount) {
        int count = scriptCount(counts, Character.UnicodeScript.CYRILLIC);
        if (count > 0) return scriptDetection("ru", count, letterCount);
        count = scriptCount(counts, Character.UnicodeScript.ARABIC);
        if (count > 0) return scriptDetection("ar", count, letterCount);
        count = scriptCount(counts, Character.UnicodeScript.DEVANAGARI);
        if (count > 0) return scriptDetection("hi", count, letterCount);
        count = scriptCount(counts, Character.UnicodeScript.BENGALI);
        if (count > 0) return scriptDetection("bn", count, letterCount);
        count = scriptCount(counts, Character.UnicodeScript.THAI);
        if (count > 0) return scriptDetection("th", count, letterCount);
        return null;
    }

    private static int scriptCount(Map<Character.UnicodeScript, Integer> counts,
            Character.UnicodeScript script) {
        return counts.getOrDefault(script, 0);
    }

    private static ScriptDetection scriptDetection(String code, int scriptLetters, int letterCount) {
        return new ScriptDetection(code, true, scriptConfidence(scriptLetters, letterCount));
    }

    private static double scriptConfidence(int scriptLetters, int letterCount) {
        return Math.max(0.85, scriptLetters / (double) letterCount);
    }

    private ChineseVariant chineseVariant(String text, String browserLanguage) {
        String browser = browserLanguage == null ? "" : browserLanguage.strip().replace('_', '-');
        String lowerBrowser = browser.toLowerCase(Locale.ROOT);
        if (lowerBrowser.equals("zh-hant") || lowerBrowser.startsWith("zh-hant-")
                || lowerBrowser.equals("zh-tw") || lowerBrowser.startsWith("zh-tw-")
                || lowerBrowser.equals("zh-hk") || lowerBrowser.startsWith("zh-hk-")
                || lowerBrowser.equals("zh-mo") || lowerBrowser.startsWith("zh-mo-")) {
            return new ChineseVariant("zh-Hant", true);
        }
        if (lowerBrowser.equals("zh-hans") || lowerBrowser.startsWith("zh-hans-")
                || lowerBrowser.equals("zh-cn") || lowerBrowser.startsWith("zh-cn-")
                || lowerBrowser.equals("zh-sg") || lowerBrowser.startsWith("zh-sg-")) {
            return new ChineseVariant("zh-Hans", true);
        }

        int traditional = countHints(text, TRADITIONAL_HINTS);
        int simplified = countHints(text, SIMPLIFIED_HINTS);
        if (traditional > simplified) return new ChineseVariant("zh-Hant", true);
        if (simplified > traditional) return new ChineseVariant("zh-Hans", true);
        return new ChineseVariant("zh-Hans", false);
    }

    private static int countHints(String text, String hints) {
        if (text == null) return 0;
        int count = 0;
        for (int index = 0; index < text.length(); index++) {
            if (hints.indexOf(text.charAt(index)) >= 0) count++;
        }
        return count;
    }

    private static boolean hasEnoughLanguageInformation(String text) {
        if (text == null || text.isBlank()) return false;
        return text.codePoints().filter(Character::isLetter).count() >= MINIMUM_LETTER_COUNT;
    }

    private static boolean containsLatinLetters(String text) {
        return text != null && text.codePoints()
                .anyMatch(codePoint -> Character.isLetter(codePoint)
                        && Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN);
    }

    private static boolean hasSeverelyMixedScripts(String text) {
        EnumMap<ScriptGroup, Integer> counts = new EnumMap<>(ScriptGroup.class);
        for (ScriptGroup group : ScriptGroup.values()) counts.put(group, 0);
        text.codePoints().filter(Character::isLetter).forEach(codePoint -> {
            ScriptGroup group = scriptGroup(codePoint);
            if (group != null) counts.compute(group, (ignored, count) -> count + 1);
        });
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        if (total < 8) return false;
        long substantialGroups = counts.values().stream()
                .filter(count -> count / (double) total >= MIXED_SCRIPT_SHARE).count();
        return substantialGroups > 1;
    }

    private static ScriptGroup scriptGroup(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return switch (script) {
            case LATIN -> ScriptGroup.LATIN;
            case CYRILLIC -> ScriptGroup.CYRILLIC;
            case ARABIC -> ScriptGroup.ARABIC;
            case DEVANAGARI -> ScriptGroup.DEVANAGARI;
            case BENGALI -> ScriptGroup.BENGALI;
            case THAI -> ScriptGroup.THAI;
            case HANGUL -> ScriptGroup.HANGUL;
            case HAN, HIRAGANA, KATAKANA -> ScriptGroup.HAN_KANA;
            default -> null;
        };
    }

    private static Map<Language, String> languageCodes() {
        EnumMap<Language, String> codes = new EnumMap<>(Language.class);
        codes.put(Language.ENGLISH, "en");
        codes.put(Language.CHINESE, "zh-Hans");
        codes.put(Language.JAPANESE, "ja");
        codes.put(Language.KOREAN, "ko");
        codes.put(Language.SPANISH, "es");
        codes.put(Language.FRENCH, "fr");
        codes.put(Language.GERMAN, "de");
        codes.put(Language.ITALIAN, "it");
        codes.put(Language.PORTUGUESE, "pt");
        codes.put(Language.RUSSIAN, "ru");
        codes.put(Language.SWEDISH, "sv");
        codes.put(Language.ARABIC, "ar");
        codes.put(Language.HINDI, "hi");
        codes.put(Language.BENGALI, "bn");
        codes.put(Language.INDONESIAN, "id");
        codes.put(Language.TURKISH, "tr");
        codes.put(Language.VIETNAMESE, "vi");
        codes.put(Language.THAI, "th");
        return Map.copyOf(codes);
    }

    public record LanguageResolution(String code, String name, String englishName, String direction,
            boolean certain, double confidence, String source) {
    }

    private record ChineseVariant(String code, boolean certain) {
    }

    private record ScriptDetection(String code, boolean certain, double confidence) {
    }

    private enum ScriptGroup {
        LATIN, CYRILLIC, ARABIC, DEVANAGARI, BENGALI, THAI, HANGUL, HAN_KANA
    }

    private enum ResolutionSource {
        MANUAL, AUTO, BROWSER_FALLBACK, ENGLISH_FALLBACK
    }

    /** Loads the immutable Lingua model set once across Spring and direct test instances. */
    private static final class DetectorHolder {
        private static final LanguageDetector INSTANCE =
                LanguageDetectorBuilder.fromLanguages(DETECTION_LANGUAGES)
                        .withLowAccuracyMode()
                        .build();

        private DetectorHolder() {
        }
    }
}

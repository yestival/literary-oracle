package com.literaryoracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import tools.jackson.databind.ObjectMapper;

class LanguageDetectionServiceTests {
    private final SupportedLanguageCatalog catalog = new SupportedLanguageCatalog(new ObjectMapper());
    private final LanguageDetectionService service = new LanguageDetectionService(catalog);

    @TestFactory
    Stream<DynamicTest> detectsRepresentativeInputForAllNineteenTargetCodes() {
        Map<String, String> samples = new LinkedHashMap<>();
        samples.put("en", "I have been thinking carefully about the future and the choices that I need to make.");
        samples.put("zh-Hans", "最近我一直在思考未来应该怎样改变自己的生活和方向。");
        samples.put("zh-Hant", "最近我一直在思考未來應該怎樣改變自己的生活與方向。");
        samples.put("ja", "最近、私は自分の将来と人生をどのように変えるべきか考えています。");
        samples.put("ko", "요즘 나는 나의 미래와 삶을 어떻게 바꾸어야 하는지 진지하게 생각하고 있습니다.");
        samples.put("es", "Últimamente pienso mucho en mi futuro y en las decisiones que debo tomar para cambiar mi vida.");
        samples.put("fr", "Ces derniers temps, je réfléchis beaucoup à mon avenir et aux décisions que je dois prendre.");
        samples.put("de", "In letzter Zeit denke ich sorgfältig über meine Zukunft und die notwendigen Entscheidungen nach.");
        samples.put("it", "Ultimamente penso molto al mio futuro e alle decisioni che devo prendere per cambiare vita.");
        samples.put("pt", "Ultimamente tenho pensado muito no meu futuro e nas decisões que preciso tomar na vida.");
        samples.put("ru", "В последнее время я много думаю о своём будущем и о решениях, которые мне нужно принять.");
        samples.put("sv", "På sistone har jag tänkt mycket på min framtid och på de beslut som jag behöver fatta.");
        samples.put("ar", "في الآونة الأخيرة أفكر كثيرًا في مستقبلي وفي القرارات التي يجب أن أتخذها في حياتي.");
        samples.put("hi", "हाल में मैं अपने भविष्य और जीवन में लिए जाने वाले निर्णयों के बारे में बहुत सोच रहा हूँ।");
        samples.put("bn", "ইদানীং আমি আমার ভবিষ্যৎ এবং জীবনে যে সিদ্ধান্তগুলো নিতে হবে সেগুলো নিয়ে অনেক ভাবছি।");
        samples.put("id", "Akhir-akhir ini saya banyak memikirkan masa depan dan keputusan yang harus saya ambil dalam hidup.");
        samples.put("tr", "Son zamanlarda geleceğim ve hayatımda vermem gereken kararlar hakkında çok düşünüyorum.");
        samples.put("vi", "Gần đây tôi suy nghĩ rất nhiều về tương lai và những quyết định cần đưa ra trong cuộc sống.");
        samples.put("th", "ช่วงนี้ฉันคิดมากเกี่ยวกับอนาคตและการตัดสินใจที่ต้องทำในชีวิตของฉัน");

        return samples.entrySet().stream().map(sample -> DynamicTest.dynamicTest(sample.getKey(), () -> {
            LanguageDetectionService.LanguageResolution result = service.resolve(
                    sample.getValue(), "auto", null);
            assertEquals(sample.getKey(), result.code());
            assertTrue(result.certain(), () -> "Expected a certain result for " + sample.getKey());
            assertEquals("AUTO", result.source());
        }));
    }

    @Test
    void manualLanguageOverridesAutomaticDetection() {
        var result = service.resolve("This sentence is unmistakably written in English.", "sv", "en-US");
        assertEquals("sv", result.code());
        assertEquals("Svenska", result.name());
        assertEquals("MANUAL", result.source());
        assertTrue(result.certain());
    }

    @TestFactory
    Stream<DynamicTest> reliableScriptsAreDetectedBeforeShortTextFallback() {
        Map<String, String> samples = new LinkedHashMap<>();
        samples.put("ko", "외로워");
        samples.put("ja", "どうしよう");
        samples.put("zh-Hans", "很孤独");
        samples.put("zh-Hant", "很孤獨");
        samples.put("ru", "страшно");
        samples.put("ar", "حزين");
        samples.put("hi", "दुखी हूँ");
        samples.put("bn", "আমি একা");
        samples.put("th", "เหงา");

        return samples.entrySet().stream().map(sample -> DynamicTest.dynamicTest(
                "short " + sample.getKey(), () -> {
                    var result = service.resolve(sample.getValue(), "auto", "en-US");
                    assertEquals(sample.getKey(), result.code());
                    assertEquals("AUTO", result.source());
                }));
    }

    @Test
    void manualLanguageStillOverridesReliableScriptDetection() {
        var result = service.resolve("我很孤独", "ja", "zh-CN");
        assertEquals("ja", result.code());
        assertEquals("MANUAL", result.source());
    }

    @Test
    void shortLatinTextFallsBackToEnglishInsteadOfTheChineseBrowserLanguage() {
        var result = service.resolve("hi", "auto", "zh-CN");
        assertEquals("en", result.code());
        assertEquals("ENGLISH_FALLBACK", result.source());
        assertFalse(result.certain());
    }

    @Test
    void manualChineseStillOverridesLatinText() {
        var result = service.resolve("hello", "zh-Hans", "en-US");
        assertEquals("zh-Hans", result.code());
        assertEquals("MANUAL", result.source());
    }

    @Test
    void autoModeUsesSupportedBrowserLanguageWhenInputIsUncertain() {
        var result = service.resolve("123 ... ?", "auto", "fr-FR");
        assertEquals("fr", result.code());
        assertEquals("BROWSER_FALLBACK", result.source());
        assertFalse(result.certain());
    }

    @Test
    void unsupportedBrowserLanguageFallsBackToEnglish() {
        var result = service.resolve("...", null, "nl-NL");
        assertEquals("en", result.code());
        assertEquals("ENGLISH_FALLBACK", result.source());
        assertFalse(result.certain());
    }

    @Test
    void simplifiedAndTraditionalChineseAreRoutedSeparately() {
        assertEquals("zh-Hans", service.resolve(
                "我最近在思考未来应该怎样改变自己的生活。", "auto", null).code());
        assertEquals("zh-Hant", service.resolve(
                "我最近在思考未來應該怎樣改變自己的生活。", "auto", null).code());
    }

    @Test
    void browserChineseVariantTakesPriorityInAutoMode() {
        assertEquals("zh-Hant", service.resolve(
                "我最近在思考未来应该怎样改变自己的生活。", "auto", "zh-TW").code());
        assertEquals("zh-Hans", service.resolve(
                "我最近在思考未來應該怎樣改變自己的生活。", "auto", "zh-CN").code());
    }

    @Test
    void severeMixedScriptInputDoesNotPretendToBeCertain() {
        var result = service.resolve("Thinking about tomorrow 我也不知道未来应该怎样", "auto", "de-DE");
        assertEquals("en", result.code());
        assertEquals("ENGLISH_FALLBACK", result.source());
        assertFalse(result.certain());
    }

    @Test
    void arabicResolutionCarriesRtlDirection() {
        var result = service.resolve(
                "أفكر كثيرًا في مستقبلي والقرارات المهمة التي يجب أن أتخذها.", "auto", null);
        assertEquals("ar", result.code());
        assertEquals("rtl", result.direction());
    }
}

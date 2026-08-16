package com.literaryoracle;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.mockito.ArgumentCaptor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class SafetyAssessmentServiceTests {
    private static final String FAKE_KEY = "unit-test-content-safety-key";
    private static final String BASE_ENDPOINT = "https://mock.invalid";
    private static final URI ANALYZE_ENDPOINT = URI.create(
            BASE_ENDPOINT + SafetyAssessmentService.API_PATH);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void azureZeroSeverityKeepsBenignTextSafe() throws Exception {
        HttpClient httpClient = clientReturning(200, azureResponse(0));
        SafetyAssessmentService service = service(httpClient, FAKE_KEY, BASE_ENDPOINT);

        assertEquals(SafetyAssessmentService.SafetyAssessment.SAFE,
                service.assess("I am choosing what to cook for dinner."));
        verify(httpClient).send(any(HttpRequest.class), anyStringBodyHandler());
    }

    @Test
    void azureLowSeverityMarksPassiveDeathWishConcerningWithoutBlocking() throws Exception {
        HttpClient httpClient = clientReturning(200, azureResponse(2));
        SafetyAssessmentService service = service(httpClient, FAKE_KEY, BASE_ENDPOINT);

        assertEquals(SafetyAssessmentService.SafetyAssessment.CONCERNING,
                service.assess("Sometimes I want to die, but I have no plan to act."));
    }

    @Test
    void azureHighSeverityAloneDoesNotCreateImmediateRisk() throws Exception {
        HttpClient httpClient = clientReturning(200, azureResponse(6));
        SafetyAssessmentService service = service(httpClient, FAKE_KEY, BASE_ENDPOINT);

        assertAll(
                () -> assertEquals(SafetyAssessmentService.SafetyAssessment.CONCERNING,
                        service.assess("I feel hopeless and life has no meaning.")),
                () -> assertEquals(SafetyAssessmentService.SafetyAssessment.CONCERNING,
                        service.assess("I want to die.")),
                () -> assertEquals(SafetyAssessmentService.SafetyAssessment.CONCERNING,
                        service.assess("I am thinking about killing myself.")));
        verify(httpClient, times(3)).send(any(HttpRequest.class), anyStringBodyHandler());
    }

    @TestFactory
    Stream<DynamicTest> explicitFirstPersonSuicideIntentIsImmediateWithoutPlanDetails() {
        return Stream.of(
                new NamedInput("Chinese immediate death", "我马上要去死"),
                new NamedInput("Chinese suicide intent", "我要去自杀"),
                new NamedInput("Chinese current suicide intent", "我现在要自杀"),
                new NamedInput("Chinese preparing to die", "我准备去死"),
                new NamedInput("English going to kill myself",
                        "I’m going to kill myself"),
                new NamedInput("English going to end my life",
                        "I'm going to end my life"),
                new NamedInput("English will kill myself", "I will kill myself"),
                new NamedInput("English about to kill myself",
                        "I'm about to kill myself"),
                new NamedInput("English explicit plan", "I plan to end my life"),
                new NamedInput("Chinese natural variant", "我已经决定去自杀"))
                .map(testCase -> DynamicTest.dynamicTest(testCase.name(), () -> {
                    HttpClient httpClient = mock(HttpClient.class);
                    SafetyAssessmentService service = service(httpClient, "", BASE_ENDPOINT);

                    assertEquals(SafetyAssessmentService.SafetyAssessment.IMMEDIATE_RISK,
                            service.assess(testCase.input()));
                    verifyNoInteractions(httpClient);
                }));
    }

    @TestFactory
    Stream<DynamicTest> ambiguousSevereDistressIsConcerningWithoutAzure() {
        return Stream.of(
                new NamedInput("Chinese cannot keep living", "我感觉我活不下去了"),
                new NamedInput("Chinese cannot hold on", "我撑不下去了"),
                new NamedInput("Chinese wish to die", "我想死"),
                new NamedInput("English wanna die", "i wanna go die"),
                new NamedInput("English going to die", "i am going to die"),
                new NamedInput("English cannot go on", "I can’t go on"),
                new NamedInput("English cannot keep living",
                        "I don’t think I can keep living"))
                .map(testCase -> DynamicTest.dynamicTest(testCase.name(), () -> {
                    HttpClient httpClient = mock(HttpClient.class);
                    SafetyAssessmentService service = service(httpClient, "", BASE_ENDPOINT);

                    assertEquals(SafetyAssessmentService.SafetyAssessment.CONCERNING,
                            service.assess(testCase.input()));
                    verifyNoInteractions(httpClient);
                }));
    }

    @Test
    void explicitCurrentActionIsImmediateEvenWhenAzureScoresZero() throws Exception {
        HttpClient httpClient = clientReturning(200, azureResponse(0));
        SafetyAssessmentService service = service(httpClient, FAKE_KEY, BASE_ENDPOINT);

        assertEquals(SafetyAssessmentService.SafetyAssessment.IMMEDIATE_RISK,
                service.assess("I am cutting myself right now."));
    }

    @Test
    void decidedNearTermPlanWithMethodAndAvailableMeansIsImmediate() throws Exception {
        HttpClient httpClient = clientReturning(200, azureResponse(6));
        SafetyAssessmentService service = service(httpClient, FAKE_KEY, BASE_ENDPOINT);

        assertEquals(SafetyAssessmentService.SafetyAssessment.IMMEDIATE_RISK,
                service.assess("I have decided to kill myself tonight, and I have pills beside me."));
    }

    @Test
    void inabilityToGuaranteeCurrentSafetyIsImmediate() throws Exception {
        HttpClient httpClient = clientReturning(200, azureResponse(4));
        SafetyAssessmentService service = service(httpClient, FAKE_KEY, BASE_ENDPOINT);

        assertEquals(SafetyAssessmentService.SafetyAssessment.IMMEDIATE_RISK,
                service.assess("I cannot guarantee I will be safe right now."));
    }

    @TestFactory
    Stream<DynamicTest> excludedContextsAreNeverHardBlockedEvenAtHighSeverity() {
        return Stream.of(
                new NamedInput("negation",
                        "I don't want to hurt myself and I am safe."),
                new NamedInput("past and now safe",
                        "I hurt myself years ago, but I am safe now."),
                new NamedInput("third-person report",
                        "My friend said, \"I decided to kill myself tonight and have pills ready.\""),
                new NamedInput("literary quotation",
                        "\"I decided to kill myself tonight with pills,\" says the narrator."),
                new NamedInput("research quotation",
                        "For a research paper, I am quoting the sentence 'I want to die'."),
                new NamedInput("hypothetical question",
                        "Hypothetically, what should someone do if they want to die?"),
                new NamedInput("news report",
                        "The news article describes a person who died by suicide."))
                .map(testCase -> DynamicTest.dynamicTest(testCase.name(), () -> {
                    HttpClient httpClient = clientReturning(200, azureResponse(6));
                    SafetyAssessmentService service = service(
                            httpClient, FAKE_KEY, BASE_ENDPOINT);

                    assertNotEquals(SafetyAssessmentService.SafetyAssessment.IMMEDIATE_RISK,
                            service.assess(testCase.input()));
                }));
    }

    @TestFactory
    Stream<DynamicTest> exclusionsStaySafeEvenWhenAzureScoresHigh() {
        return Stream.of(
                new NamedInput("explicit negation",
                        "I'm not going to kill myself."),
                new NamedInput("past experience and safe now",
                        "Years ago I was going to kill myself, but I am safe now."),
                new NamedInput("third-person report",
                        "My friend says she is thinking about suicide."),
                new NamedInput("plain third-person intent",
                        "She is going to kill herself tonight."),
                new NamedInput("literary context",
                        "In the novel, the narrator says, 'I'm going to kill myself.'"),
                new NamedInput("die laughing", "I'm going to die laughing."),
                new NamedInput("embarrassment idiom",
                        "I could die of embarrassment."),
                new NamedInput("exam hyperbole", "This exam is killing me."),
                new NamedInput("illness mortality",
                        "I am going to die from cancer."),
                new NamedInput("accidental mortality",
                        "I am going to die in a car crash."))
                .map(testCase -> DynamicTest.dynamicTest(testCase.name(), () -> {
                    HttpClient httpClient = clientReturning(200, azureResponse(6));
                    SafetyAssessmentService service = service(
                            httpClient, FAKE_KEY, BASE_ENDPOINT);

                    assertEquals(SafetyAssessmentService.SafetyAssessment.SAFE,
                            service.assess(testCase.input()));
                }));
    }

    @Test
    void timeoutFallsBackToStrictLocalRulesWithoutFailingTheRequest() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), anyStringBodyHandler()))
                .thenThrow(new HttpTimeoutException("unit-test timeout"));
        SafetyAssessmentService service = service(httpClient, FAKE_KEY, BASE_ENDPOINT);

        assertAll(
                () -> assertEquals(SafetyAssessmentService.SafetyAssessment.IMMEDIATE_RISK,
                        service.assess("I am hurting myself right now.")),
                () -> assertEquals(SafetyAssessmentService.SafetyAssessment.SAFE,
                        service.assess("I feel sad and lonely today.")));
        verify(httpClient, times(2)).send(any(HttpRequest.class), anyStringBodyHandler());
    }

    @TestFactory
    Stream<DynamicTest> httpFailuresUseTheLocalFallback() {
        return Stream.of(401, 429, 500, 503).map(status -> DynamicTest.dynamicTest(
                "HTTP " + status, () -> {
                    HttpClient httpClient = clientReturning(status, "{}");
                    SafetyAssessmentService service = service(
                            httpClient, FAKE_KEY, BASE_ENDPOINT);

                    assertAll(
                            () -> assertEquals(
                                    SafetyAssessmentService.SafetyAssessment.IMMEDIATE_RISK,
                                    service.assess("I am cutting myself right now.")),
                            () -> assertEquals(
                                    SafetyAssessmentService.SafetyAssessment.CONCERNING,
                                    service.assess("I want to die.")),
                            () -> assertEquals(SafetyAssessmentService.SafetyAssessment.SAFE,
                                    service.assess("I feel sad and lonely.")));
                    verify(httpClient, times(3)).send(
                            any(HttpRequest.class), anyStringBodyHandler());
                }));
    }

    @Test
    void networkIoFailureUsesTheLocalFallback() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), anyStringBodyHandler()))
                .thenThrow(new IOException("synthetic network failure"));
        SafetyAssessmentService service = service(httpClient, FAKE_KEY, BASE_ENDPOINT);

        assertAll(
                () -> assertEquals(SafetyAssessmentService.SafetyAssessment.IMMEDIATE_RISK,
                        service.assess("I am hurting myself right now.")),
                () -> assertEquals(SafetyAssessmentService.SafetyAssessment.CONCERNING,
                        service.assess("I want to die.")),
                () -> assertEquals(SafetyAssessmentService.SafetyAssessment.SAFE,
                        service.assess("I feel sad and lonely.")));
        verify(httpClient, times(3)).send(any(HttpRequest.class), anyStringBodyHandler());
    }

    @TestFactory
    Stream<DynamicTest> invalidAzureResponsesUseTheLocalFallback() {
        return Stream.of(
                new NamedResponse("non-JSON", "not-json"),
                new NamedResponse("missing analysis", "{}"),
                new NamedResponse("invalid four-level severity",
                        "{\"categoriesAnalysis\":[{\"category\":\"SelfHarm\",\"severity\":5}]}"),
                new NamedResponse("duplicate SelfHarm analyses",
                        "{\"categoriesAnalysis\":[{\"category\":\"SelfHarm\",\"severity\":6},"
                                + "{\"category\":\"SelfHarm\",\"severity\":2}]}"))
                .map(testCase -> DynamicTest.dynamicTest(testCase.name(), () -> {
                    HttpClient httpClient = clientReturning(200, testCase.body());
                    SafetyAssessmentService service = service(
                            httpClient, FAKE_KEY, BASE_ENDPOINT);

                    assertAll(
                            () -> assertEquals(
                                    SafetyAssessmentService.SafetyAssessment.IMMEDIATE_RISK,
                                    service.assess("I cannot keep myself safe right now.")),
                            () -> assertEquals(SafetyAssessmentService.SafetyAssessment.SAFE,
                                    service.assess("I don't want to hurt myself.")));
                }));
    }

    @TestFactory
    Stream<DynamicTest> missingConfigurationNeverUsesHttpAndStillAppliesLocalRules() {
        return Stream.of(
                new NamedConfiguration("missing key", "", BASE_ENDPOINT),
                new NamedConfiguration("missing endpoint", FAKE_KEY, ""),
                new NamedConfiguration("non-HTTPS endpoint", FAKE_KEY,
                        "http://not-https.invalid"))
                .map(configuration -> DynamicTest.dynamicTest(configuration.name(), () -> {
                    HttpClient httpClient = mock(HttpClient.class);
                    SafetyAssessmentService service = service(httpClient,
                            configuration.key(), configuration.endpoint());

                    assertAll(
                            () -> assertFalse(service.configured()),
                            () -> assertEquals(
                                    SafetyAssessmentService.SafetyAssessment.IMMEDIATE_RISK,
                                    service.assess("I am cutting myself right now.")),
                            () -> assertEquals(SafetyAssessmentService.SafetyAssessment.SAFE,
                                    service.assess("I don't want to hurt myself.")));
                    verifyNoInteractions(httpClient);
                }));
    }

    @Test
    void unconfiguredFallbackKeepsChineseImmediateAndExcludedContextsDistinct() {
        HttpClient httpClient = mock(HttpClient.class);
        SafetyAssessmentService service = service(httpClient, "", BASE_ENDPOINT);

        assertAll(
                () -> assertEquals(SafetyAssessmentService.SafetyAssessment.IMMEDIATE_RISK,
                        service.assess("我现在正在伤害自己。")),
                () -> assertEquals(SafetyAssessmentService.SafetyAssessment.IMMEDIATE_RISK,
                        service.assess("我已经决定今晚服药自杀，药片就在我身边。")),
                () -> assertEquals(SafetyAssessmentService.SafetyAssessment.IMMEDIATE_RISK,
                        service.assess("我现在无法保证自己安全。")),
                () -> assertNotEquals(SafetyAssessmentService.SafetyAssessment.IMMEDIATE_RISK,
                        service.assess("我不想伤害自己。")),
                () -> assertNotEquals(SafetyAssessmentService.SafetyAssessment.IMMEDIATE_RISK,
                        service.assess("我以前伤害自己，但我现在安全。")),
                () -> assertNotEquals(SafetyAssessmentService.SafetyAssessment.IMMEDIATE_RISK,
                        service.assess("我朋友说她今晚想自杀，我该怎么帮助她。")));
        verifyNoInteractions(httpClient);
    }

    @Test
    void requestUsesOnlySelfHarmFourLevelsAndKeepsTheKeyOutOfTheBody() throws Exception {
        HttpClient httpClient = clientReturning(200, azureResponse(0));
        SafetyAssessmentService service = service(httpClient, FAKE_KEY, BASE_ENDPOINT);
        String privateInput = "private-input-sentinel";

        service.assess(privateInput);

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), anyStringBodyHandler());
        HttpRequest request = captor.getValue();
        String body = requestBody(request);
        JsonNode json = objectMapper.readTree(body);

        assertAll(
                () -> assertEquals(ANALYZE_ENDPOINT, request.uri()),
                () -> assertEquals(SafetyAssessmentService.REQUEST_TIMEOUT,
                        request.timeout().orElseThrow()),
                () -> assertEquals("application/json", request.headers()
                        .firstValue("Content-Type").orElseThrow()),
                () -> assertEquals(FAKE_KEY, request.headers()
                        .firstValue("Ocp-Apim-Subscription-Key").orElseThrow()),
                () -> assertEquals(Set.of("text", "categories", "outputType"),
                        json.properties().stream().map(Map.Entry::getKey)
                                .collect(java.util.stream.Collectors.toSet())),
                () -> assertEquals(privateInput, json.path("text").asText()),
                () -> assertEquals(1, json.path("categories").size()),
                () -> assertEquals("SelfHarm", json.path("categories").get(0).asText()),
                () -> assertEquals("FourSeverityLevels", json.path("outputType").asText()),
                () -> assertFalse(request.uri().toString().contains(FAKE_KEY)),
                () -> assertFalse(body.contains(FAKE_KEY)));
    }

    private SafetyAssessmentService service(HttpClient httpClient, String apiKey,
            String endpoint) {
        return new SafetyAssessmentService(httpClient, objectMapper, apiKey, endpoint);
    }

    private HttpClient clientReturning(int status, String body) throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> httpResponse = response(status, body);
        when(httpClient.send(any(HttpRequest.class), anyStringBodyHandler()))
                .thenReturn(httpResponse);
        return httpClient;
    }

    private String azureResponse(int severity) throws Exception {
        return objectMapper.writeValueAsString(Map.of("categoriesAnalysis", List.of(Map.of(
                "category", "SelfHarm",
                "severity", severity))));
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    private static HttpResponse.BodyHandler<String> anyStringBodyHandler() {
        return any();
    }

    private static String requestBody(HttpRequest request) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CompletableFuture<Void> completed = new CompletableFuture<>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer buffer) {
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                output.writeBytes(bytes);
            }

            @Override
            public void onError(Throwable throwable) {
                completed.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                completed.complete(null);
            }
        });
        completed.get(1, TimeUnit.SECONDS);
        return output.toString(StandardCharsets.UTF_8);
    }

    private record NamedInput(String name, String input) {
    }

    private record NamedResponse(String name, String body) {
    }

    private record NamedConfiguration(String name, String key, String endpoint) {
    }
}

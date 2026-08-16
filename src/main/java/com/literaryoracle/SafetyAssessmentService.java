package com.literaryoracle;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

/**
 * Assesses immediate self-harm risk without treating general distress or every
 * non-zero content-safety score as an emergency.
 */
@Service
public final class SafetyAssessmentService {
    static final String KEY_ENVIRONMENT_VARIABLE = "CONTENT_SAFETY_KEY";
    static final String ENDPOINT_ENVIRONMENT_VARIABLE = "CONTENT_SAFETY_ENDPOINT";
    static final String API_PATH = "/contentsafety/text:analyze?api-version=2024-09-01";
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern FIRST_PERSON = pattern(
            "\\b(?:i|i'm|i am|i've|i have|me|my|myself)\\b|(?:^|[\\s,，])我(?:自己)?");
    private static final Pattern SELF_HARM = pattern(
            "\\b(?:kill myself|end my life|take my own life|die by suicide|suicide|"
                    + "hurt(?:ing)? myself|harm(?:ing)? myself|cut(?:ting)? myself|"
                    + "burn(?:ing)? myself|overdos(?:e|ing)|"
                    + "hang myself|shoot myself|slit my wrists?|jump off)\\b|"
                    + "(?:自杀|自殺|轻生|輕生|结束生命|結束生命|伤害自己|傷害自己|"
                    + "自残|自殘|割腕|割脉|割脈|跳楼|跳樓|跳桥|跳橋|服药自杀|"
                    + "服藥自殺|吞药|吞藥|上吊)");
    private static final Pattern SELF_DIRECTED_HARM = pattern(
            "\\b(?:kill myself|end my life|take my own life|die by suicide|"
                    + "commit suicide|hurt(?:ing)? myself|harm(?:ing)? myself|"
                    + "cut(?:ting)? myself|burn(?:ing)? myself|overdos(?:e|ing)|"
                    + "hang myself|shoot myself|slit my wrists?|jump off)\\b|"
                    + "(?:自杀|自殺|轻生|輕生|结束生命|結束生命|伤害自己|傷害自己|"
                    + "自残|自殘|割腕|割脉|割脈|跳楼|跳樓|跳桥|跳橋|服药自杀|"
                    + "服藥自殺|吞药|吞藥|上吊)");
    private static final Pattern PASSIVE_DEATH_WISH = pattern(
            "\\b(?:want to die|wish i (?:were|was) dead|do not want to (?:live|be alive)|"
                    + "don't want to (?:live|be alive)|better off dead|never wake up|"
                    + "life (?:has|holds) no meaning)\\b|"
                    + "(?:想死|不想活|不想活着|不想活著|希望自己死|活着没有意义|"
                    + "活著沒有意義)");
    private static final Pattern EXPLICIT_IMMEDIATE_SUICIDE_INTENT = pattern(
            "\\b(?:(?:i(?:'m| am)\\s+(?:(?:going|about|preparing|ready)\\s+to|gonna))|"
                    + "(?:i\\s+(?:will|shall))|"
                    + "(?:i\\s+(?:plan|intend)\\s+to)|"
                    + "(?:i(?:'ve| have)\\s+(?:(?:decided|resolved)\\s+to|a\\s+plan\\s+to)))"
                    + "\\s+(?:go\\s+)?(?:kill myself|end my life|take my own life|"
                    + "die by suicide|commit suicide|end it all)\\b|"
                    + "(?:^|[\\s,，;；])我(?:现在|現在|马上|馬上|立刻|即刻|很快|就)?"
                    + "(?:要|准备|準備|打算|计划|計劃|决定|決定|已经决定|已經決定)"
                    + "(?:现在|現在|马上|馬上|立刻|即刻|很快|就)?(?:要)?(?:去)?"
                    + "(?:自杀|自殺|轻生|輕生|结束生命|結束生命|去死)");
    private static final Pattern AMBIGUOUS_SEVERE_DISTRESS = pattern(
            "\\b(?:i\\s+(?:wanna|want to)\\s+go\\s+die|"
                    + "i(?:'m| am)\\s+going\\s+to\\s+die|"
                    + "i\\s+(?:cannot|can't|can not)\\s+(?:go on|keep living|go on living)|"
                    + "i\\s+(?:do not|don't)\\s+think\\s+i\\s+can\\s+"
                    + "(?:keep living|go on(?: living)?))\\b|"
                    + "(?:^|[\\s,，;；])我(?:(?:感觉|感覺|觉得|覺得)我?)?活不下去了?|"
                    + "(?:^|[\\s,，;；])我(?:真的)?(?:撑|撐)不下去了?|"
                    + "(?:^|[\\s,，;；])我想死");
    private static final Pattern ACTIVE_SELF_HARM = pattern(
            "\\b(?:i am|i'm)\\s+(?:(?:right )?now\\s+|currently\\s+)?"
                    + "(?:cutting|hurting|harming|burning|strangling|poisoning)\\s+myself\\b|"
                    + "\\b(?:i am|i'm)\\s+(?:currently\\s+)?overdosing\\b|"
                    + "\\b(?:i have|i've|i)\\s+(?:just\\s+|already\\s+)?"
                    + "(?:taken|took|swallowed|ingested)\\s+(?:too many|all of|a handful of|"
                    + "a bottle of)\\s+(?:pills?|tablets?|medication)\\b|"
                    + "我(?:正在|现在正在|現在正在|刚刚|剛剛|已经|已經)"
                    + "(?:伤害自己|傷害自己|自残|自殘|割腕|割脉|割脈|吞下|服下)");
    private static final Pattern CANNOT_STAY_SAFE = pattern(
            "\\bi\\s+(?:cannot|can't|can not)\\s+(?:promise|guarantee|ensure)"
                    + "(?:\\s+that)?\\s+(?:i\\s+)?(?:will\\s+)?(?:be|stay|keep myself)\\s+safe\\b|"
                    + "\\bi\\s+(?:cannot|can't|can not)\\s+(?:promise|guarantee)"
                    + "(?:\\s+that)?\\s+i\\s+(?:won't|will not)\\s+(?:hurt|harm|kill)\\s+myself\\b|"
                    + "\\bi\\s+(?:cannot|can't|can not|do not|don't)\\s+"
                    + "(?:keep myself safe|trust myself to (?:be|stay) safe)\\b|"
                    + "\\bi(?:'m| am)\\s+not safe(?:\\s+with myself)?\\s+"
                    + "(?:right now|now|tonight)\\b|"
                    + "我(?:现在|現在)?(?:无法|無法|不能)保证(?:自己)?(?:现在|現在)?安全|"
                    + "我(?:现在|現在)?(?:无法|無法|不能)保证(?:不|不会|不會)"
                    + "(?:伤害自己|傷害自己|自残|自殘|自杀|自殺)|"
                    + "我(?:现在|現在)(?:不能保证自己安全|無法保證自己安全|不安全)");
    private static final Pattern COMMITTED_INTENT = pattern(
            "\\b(?:i have|i've|i)\\s+(?:decided|made up my mind|made a plan)\\b|"
                    + "\\bi\\s+have\\s+a\\s+plan\\s+to\\b|"
                    + "\\bi(?:'m| am)\\s+(?:going|about|preparing)\\s+to\\b|"
                    + "\\bi\\s+(?:will|am ready to)\\b|"
                    + "我(?:已经|已經)?(?:决定|決定|下定决心|下定決心|准备|準備|马上要|"
                    + "馬上要|立刻要|现在要|現在要)");
    private static final Pattern WEAK_INTENT = pattern(
            "\\bi\\s+(?:plan|intend)\\s+to\\b|"
                    + "\\bi(?:'m| am)\\s+(?:thinking (?:of|about)|considering)\\b|"
                    + "我(?:计划|計劃|打算|考虑|考慮)");
    private static final Pattern NEAR_TIME = pattern(
            "\\b(?:right now|now|tonight|today|this (?:evening|morning|afternoon)|"
                    + "in (?:a few|\\d+) (?:minutes?|hours?)|within (?:a few|\\d+) "
                    + "(?:minutes?|hours?)|very soon|tomorrow morning)\\b|"
                    + "(?:现在|現在|马上|馬上|立刻|今晚|今天|今早|明早|一会儿|一會兒|"
                    + "几分钟后|幾分鐘後|几小时内|幾小時內)");
    private static final Pattern DISTANT_OR_HYPOTHETICAL_TIME = pattern(
            "\\b(?:someday|one day|eventually|in (?:a few|several|\\d+) years?|"
                    + "if i ever|might someday|maybe someday)\\b|"
                    + "(?:有一天|将来某天|將來某天|几年后|幾年後|如果有一天)");
    private static final Pattern METHOD_OR_TOOL = pattern(
            "\\b(?:overdos(?:e|ing)|hang(?:ing)?|shoot(?:ing)?|jump(?:ing)? off|"
                    + "slit(?:ting)? (?:my )?wrists?|pills?|tablets?|medication|poison|"
                    + "rope|gun|firearm|knife|blade|bridge|roof|carbon monoxide)\\b|"
                    + "(?:过量服药|過量服藥|吞药|吞藥|药片|藥片|绳子|繩子|枪|槍|刀|"
                    + "刀片|割腕|割脉|割脈|跳楼|跳樓|跳桥|跳橋|上吊)");
    private static final Pattern AVAILABLE_MEANS = pattern(
            "\\b(?:i have|i've got|i got|i can get|i have access to|with me|"
                    + "within reach|next to me|beside me|ready)\\b.{0,60}"
                    + "(?:pills?|tablets?|medication|poison|rope|gun|firearm|knife|blade)\\b|"
                    + "\\b(?:pills?|tablets?|medication|poison|rope|gun|firearm|knife|blade)"
                    + "\\b.{0,40}\\b(?:with me|within reach|next to me|beside me|ready)\\b|"
                    + "我(?:有|拿着|拿著|已经准备好|已經準備好|能拿到|身边有|身邊有)"
                    + ".{0,30}(?:药|藥|药片|藥片|绳子|繩子|枪|槍|刀|刀片)");
    private static final Pattern NEGATED_RISK = pattern(
            "\\bi\\s+(?:do not|don't|never)\\s+(?:want|intend|plan)\\s+to\\s+"
                    + "(?:die|kill myself|end my life|take my own life|commit suicide|"
                    + "hurt myself|harm myself|cut myself)\\b|"
                    + "\\bi(?:'m| am)\\s+not\\s+going\\s+to\\s+"
                    + "(?:die|kill myself|end my life|take my own life|commit suicide|"
                    + "hurt myself|harm myself|cut myself)\\b|"
                    + "\\bi\\s+(?:will not|won't|would never)\\s+"
                    + "(?:kill myself|end my life|take my own life|commit suicide|"
                    + "hurt myself|harm myself|cut myself)\\b|"
                    + "\\bi(?:'m| am)\\s+not\\s+suicidal\\b|"
                    + "我(?:不想|不会|不會|绝不会|絕不會)(?:去)?"
                    + "(?:死|自杀|自殺|伤害自己|傷害自己|自残|自殘)");
    private static final Pattern PAST_DISTANT = pattern(
            "\\b(?:years? ago|last year|in the past|used to|previously|when i was "
                    + "(?:younger|a child|a teenager))\\b|"
                    + "(?:几年前|幾年前|去年|过去|過去|以前|曾经|曾經|小时候|小時候)");
    private static final Pattern NOW_SAFE = pattern(
            "\\b(?:i am|i'm|feel)\\s+(?:safe now|now safe)|"
                    + "\\b(?:now|currently)\\s+(?:i am|i'm)\\s+safe\\b|"
                    + "\\bno longer\\s+(?:suicidal|want(?:ing)? to (?:die|hurt myself))\\b|"
                    + "(?:我现在安全|我現在安全|现在已经安全|現在已經安全|现在没事|"
                    + "現在沒事|已经没事|已經沒事|不再想自杀|不再想自殺)");
    private static final Pattern FICTION_RESEARCH_OR_HELP = pattern(
            "\\b(?:novel|fiction|story|character|protagonist|lyrics?|song|poem|"
                    + "quotation|quoted?|research|study|paper|survey|news|article|"
                    + "hypothetical(?:ly)?|screenplay|role-play|roleplay|narrator|"
                    + "passage|excerpt|book|play|said|says|"
                    + "write about|writing about|talk about|discuss(?:ing)?|prevention)\\b|"
                    + "\\b(?:how (?:can|do) i help|help (?:my|a) (?:friend|patient|client)|"
                    + "support someone)\\b|"
                    + "(?:小说|小說|虚构|虛構|角色|人物|歌词|歌詞|诗歌|詩歌|引用|"
                    + "研究|论文|論文|新闻|新聞|假设|假設|如何帮助|如何幫助)");
    private static final Pattern THIRD_PERSON = pattern(
            "\\b(?:my friend|a friend|my (?:child|partner|relative)|he|she|they|"
                    + "someone|another person|a patient|a client|a woman|the woman|"
                    + "a man|the man|a teenager|the teenager|a person|the person)\\b|"
                    + "(?:我的朋友|我朋友|他|她|他们|他們|别人|別人|某人|患者|当事人|當事人)");
    private static final Pattern THIRD_PERSON_HARM_LANGUAGE = pattern(
            "\\b(?:die|dying|dead|death|suicide|suicidal|kill(?:ing)?|hurt(?:ing)?|"
                    + "harm(?:ing)?|cut(?:ting)?|overdos(?:e|ing)|"
                    + "end (?:his|her|their) life|take (?:his|her|their) own life)\\b|"
                    + "(?:死|死亡|自杀|自殺|轻生|輕生|伤害自己|傷害自己|自残|自殘)");
    private static final Pattern EUPHEMISTIC_SELF_HARM = pattern(
            "\\b(?:do it|end it all|make sure i do not wake up|"
                    + "not be here anymore)\\b");
    private static final Pattern FIGURATIVE_DEATH = pattern(
            "\\b(?:i(?:'m| am)|we(?:'re| are))?\\s*(?:going to )?die laughing\\b|"
                    + "\\b(?:i(?:'d| would| could| might)?|we(?:'d| would| could| might)?)"
                    + "\\s+die of (?:embarrassment|shame|laughter)\\b|"
                    + "\\b(?:this|that|the)\\s+(?:exam|test|homework|deadline|workload|"
                    + "meeting|class|heat|cold)\\s+is killing me\\b|"
                    + "(?:笑死我了|笑死|尴尬死了|尷尬死了|这个考试要了我的命|這個考試要了我的命)");
    private static final Pattern MEDICAL_OR_ACCIDENTAL_DEATH = pattern(
            "\\b(?:die|dying|died)\\s+(?:from|of|because of|after|in)\\s+"
                    + "(?:(?:a|an|the|my)\\s+)?(?:cancer|illness|disease|infection|"
                    + "heart attack|stroke|accident|car crash|collision|injury|injuries)\\b|"
                    + "\\b(?:cancer|illness|disease|infection|injury|injuries)\\s+"
                    + "(?:is|was|may be|might be)\\s+killing me\\b|"
                    + "(?:死于|死於)(?:癌症|疾病|感染|心脏病|心臟病|中风|中風|事故|车祸|車禍|意外)|"
                    + "(?:因为|因為|由于|由於)(?:癌症|疾病|事故|车祸|車禍|意外)"
                    + "(?:而死|死亡|去世|会死|會死)");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ObjectReader strictReader;
    private final String apiKey;
    private final URI analyzeEndpoint;

    @Autowired
    public SafetyAssessmentService(ObjectMapper objectMapper) {
        this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(), objectMapper,
                System.getenv(KEY_ENVIRONMENT_VARIABLE),
                System.getenv(ENDPOINT_ENVIRONMENT_VARIABLE));
    }

    SafetyAssessmentService(HttpClient httpClient, ObjectMapper objectMapper,
            String apiKey, String baseEndpoint) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.strictReader = objectMapper.reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.analyzeEndpoint = buildAnalyzeEndpoint(baseEndpoint);
    }

    public SafetyAssessment assess(String input) {
        LocalAssessment local = assessLocally(input);
        OptionalInt severity = analyzeWithAzure(input);

        if (local.immediateRisk()
                || severity.orElse(-1) == 6 && local.azureImmediateContext()) {
            return SafetyAssessment.IMMEDIATE_RISK;
        }
        if (local.explicitlyExcluded()) return SafetyAssessment.SAFE;
        if (local.concerning() || severity.orElse(0) >= 4) {
            return SafetyAssessment.CONCERNING;
        }
        return SafetyAssessment.SAFE;
    }

    boolean configured() {
        return !apiKey.isBlank() && analyzeEndpoint != null;
    }

    private OptionalInt analyzeWithAzure(String input) {
        if (!configured() || input == null || input.isBlank() || input.codePointCount(0,
                input.length()) > 10_000) return OptionalInt.empty();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("text", input);
            body.put("categories", List.of("SelfHarm"));
            body.put("outputType", "FourSeverityLevels");
            HttpRequest request = HttpRequest.newBuilder(analyzeEndpoint)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Ocp-Apim-Subscription-Key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200 || response.body() == null) {
                return OptionalInt.empty();
            }
            return parseSeverity(strictReader.readTree(response.body()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return OptionalInt.empty();
        } catch (IOException | RuntimeException exception) {
            return OptionalInt.empty();
        }
    }

    private OptionalInt parseSeverity(JsonNode root) {
        JsonNode analyses = root == null ? null : root.get("categoriesAnalysis");
        if (analyses == null || !analyses.isArray()) return OptionalInt.empty();
        Integer severity = null;
        for (JsonNode analysis : analyses) {
            if (!"SelfHarm".equals(analysis.path("category").asText())) continue;
            JsonNode severityNode = analysis.get("severity");
            if (severity != null || severityNode == null || !severityNode.isIntegralNumber()) {
                return OptionalInt.empty();
            }
            severity = severityNode.intValue();
        }
        if (severity == null || !List.of(0, 2, 4, 6).contains(severity)) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(severity);
    }

    private LocalAssessment assessLocally(String input) {
        if (input == null || input.isBlank()) return LocalAssessment.safe();
        String normalized = normalize(input);
        boolean concerning = false;
        boolean azureImmediateContext = false;
        boolean sawExcludedRisk = false;
        boolean sawNonExcludedRisk = false;
        boolean pendingExplicitIntent = false;

        for (String clause : normalized.split("(?<=[.!?。！？；;])|\\R")) {
            String value = clause.strip();
            if (value.isEmpty()) continue;
            boolean explicitImmediateIntent = matches(
                    EXPLICIT_IMMEDIATE_SUICIDE_INTENT, value);
            boolean ambiguousSevereDistress = matches(AMBIGUOUS_SEVERE_DISTRESS, value);
            boolean harmLanguage = matches(SELF_HARM, value)
                    || matches(PASSIVE_DEATH_WISH, value)
                    || matches(ACTIVE_SELF_HARM, value)
                    || explicitImmediateIntent
                    || ambiguousSevereDistress
                    || matches(THIRD_PERSON, value)
                            && matches(THIRD_PERSON_HARM_LANGUAGE, value);
            boolean unableToStaySafe = matches(CANNOT_STAY_SAFE, value);
            boolean clearlyNonSelfHarmContext = isClearlyNonSelfHarmContext(value);
            boolean excluded = clearlyNonSelfHarmContext || isExcluded(value, harmLanguage);

            if (unableToStaySafe && !isQuotedOrFictional(value)) {
                return LocalAssessment.immediate();
            }
            if (clearlyNonSelfHarmContext) {
                sawExcludedRisk = true;
                continue;
            }
            if (harmLanguage && excluded) {
                sawExcludedRisk = true;
                continue;
            }
            if (harmLanguage) {
                sawNonExcludedRisk = true;
                concerning = true;
            }
            if (matches(ACTIVE_SELF_HARM, value) && !excluded) {
                return LocalAssessment.immediate();
            }

            boolean firstPerson = matches(FIRST_PERSON, value);
            boolean committed = matches(COMMITTED_INTENT, value);
            boolean weakIntent = matches(WEAK_INTENT, value);
            boolean nearTime = matches(NEAR_TIME, value);
            boolean methodOrTool = matches(METHOD_OR_TOOL, value);
            boolean availableMeans = matches(AVAILABLE_MEANS, value);
            boolean planDetail = nearTime || methodOrTool || availableMeans;
            boolean distantOrHypothetical = matches(DISTANT_OR_HYPOTHETICAL_TIME, value);
            if (explicitImmediateIntent && firstPerson && !excluded
                    && !distantOrHypothetical) return LocalAssessment.immediate();
            if (pendingExplicitIntent && !excluded && !distantOrHypothetical
                    && planDetail) return LocalAssessment.immediate();
            boolean directPlan = firstPerson && matches(SELF_DIRECTED_HARM, value)
                    && !distantOrHypothetical && ((committed && planDetail)
                            || (weakIntent && (nearTime || availableMeans)));
            if (directPlan && !excluded) return LocalAssessment.immediate();

            if (!excluded && firstPerson && (matches(SELF_DIRECTED_HARM, value)
                    || matches(EUPHEMISTIC_SELF_HARM, value))
                    && (committed || weakIntent) && planDetail && !distantOrHypothetical) {
                azureImmediateContext = true;
            }
            pendingExplicitIntent = !excluded && firstPerson
                    && matches(SELF_DIRECTED_HARM, value) && (committed || weakIntent)
                    && !distantOrHypothetical;
        }
        boolean explicitlyExcluded = sawExcludedRisk && !sawNonExcludedRisk
                && !azureImmediateContext;
        return new LocalAssessment(false, concerning, azureImmediateContext,
                explicitlyExcluded);
    }

    private boolean isClearlyNonSelfHarmContext(String clause) {
        boolean directSelfHarm = matches(SELF_DIRECTED_HARM, clause)
                || matches(ACTIVE_SELF_HARM, clause)
                || matches(CANNOT_STAY_SAFE, clause);
        return !directSelfHarm && (matches(FIGURATIVE_DEATH, clause)
                || matches(MEDICAL_OR_ACCIDENTAL_DEATH, clause));
    }

    private boolean isExcluded(String clause, boolean harmLanguage) {
        if (!harmLanguage) return false;
        if (matches(NEGATED_RISK, clause) || matches(NOW_SAFE, clause)) return true;
        if (isQuotedOrFictional(clause)) return true;
        if (matches(PAST_DISTANT, clause) && !matches(NEAR_TIME, clause)
                && !matches(COMMITTED_INTENT, clause)) return true;
        return matches(THIRD_PERSON, clause) && !matches(COMMITTED_INTENT, clause)
                && !matches(WEAK_INTENT, clause);
    }

    private boolean isQuotedOrFictional(String clause) {
        if (matches(FICTION_RESEARCH_OR_HELP, clause)) return true;
        String stripped = clause.strip();
        return stripped.startsWith("\"") || stripped.length() >= 2
                && stripped.chars().filter(character -> character == '"').count() >= 2;
    }

    private static URI buildAnalyzeEndpoint(String baseEndpoint) {
        if (baseEndpoint == null || baseEndpoint.isBlank()) return null;
        try {
            String base = baseEndpoint.strip().replaceAll("/+$", "");
            URI parsed = URI.create(base);
            if (!"https".equalsIgnoreCase(parsed.getScheme()) || parsed.getHost() == null
                    || parsed.getQuery() != null || parsed.getFragment() != null) return null;
            return URI.create(base + API_PATH);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String normalize(String input) {
        return Normalizer.normalize(input, Normalizer.Form.NFKC)
                .replace('\u2018', '\'').replace('\u2019', '\'')
                .replace('\u201c', '"').replace('\u201d', '"')
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private static Pattern pattern(String expression) {
        return Pattern.compile(expression, FLAGS);
    }

    private static boolean matches(Pattern pattern, String value) {
        return pattern.matcher(value).find();
    }

    public enum SafetyAssessment {
        IMMEDIATE_RISK,
        CONCERNING,
        SAFE
    }

    private record LocalAssessment(boolean immediateRisk, boolean concerning,
            boolean azureImmediateContext, boolean explicitlyExcluded) {
        static LocalAssessment immediate() {
            return new LocalAssessment(true, true, true, false);
        }

        static LocalAssessment safe() {
            return new LocalAssessment(false, false, false, false);
        }
    }
}

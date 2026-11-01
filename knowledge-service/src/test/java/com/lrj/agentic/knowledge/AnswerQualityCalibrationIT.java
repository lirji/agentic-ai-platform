package com.lrj.agentic.knowledge;

import static com.lrj.agentic.knowledge.domain.evaluation.DecisionModels.*;
import static com.lrj.agentic.knowledge.domain.evaluation.DecisionProtocol.*;
import static com.lrj.agentic.knowledge.domain.evaluation.EvaluationModels.*;
import static com.lrj.agentic.knowledge.domain.evaluation.RunModels.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.contract.TaskStatus;
import com.lrj.agentic.knowledge.application.*;
import com.lrj.agentic.knowledge.domain.evaluation.*;
import com.lrj.agentic.knowledge.domain.processing.ProcessingClaim;
import com.lrj.agentic.knowledge.domain.processing.ProcessingRepository;
import com.lrj.agentic.security.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.Resource;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 校准样本进入真实MySQL与HTTP；仅中央执行观察为替身，没有模型、索引质量或生产效果声明。 */
@Import(AnswerQualityCalibrationIT.Configuration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "spring.main.banner-mode=off",
                "knowledge.processing.worker-enabled=false", "knowledge.evaluation.worker-enabled=false"})
class AnswerQualityCalibrationIT {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired KnowledgeService knowledge;
    @Autowired ProcessingRepository processing;
    @Autowired EvaluationRepository definitions;
    @Autowired EvaluationRunRepository reports;
    @Autowired EvaluationRunService runs;
    @Autowired CalibrationCentral central;
    @Value("${knowledge.identity.public-key}") Resource publicKey;
    @Value("${INTERNAL_PRIVATE_KEY}") Resource privateKey;
    private final Map<String, List<String>> scopeChunks = new HashMap<>();
    private final Caller owner = new Caller("answer-calibration-" + UUID.randomUUID(), "reviewer");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test void calibratedGradesPersistAndCompareThroughActualApiAndBrowser() throws Exception {
        var fixture = json.readTree(getClass().getResourceAsStream("/answer-quality-calibration.json"));
        assertThat(fixture.path("status").asText()).isEqualTo("CALIBRATED");
        var questions = new ArrayList<Question>();
        for (var sample : fixture.path("cases")) {
            var docs = new ArrayList<String>(); var gold = new ArrayList<Gold>(); var chunks = new ArrayList<String>();
            for (var text : sample.path("sourceTexts")) {
                var evidence = publish(sample.path("id").asText(), text.asText());
                docs.add(evidence.documentId()); chunks.add(evidence.chunkId());
                if (sample.path("expectation").asText().equals("ANSWERABLE")) {
                    gold.add(new Gold(evidence.documentId(), evidence.version(), evidence.chunkId(), 2));
                }
            }
            String questionId = UUID.randomUUID().toString(); scopeChunks.put(questionId, List.copyOf(chunks));
            questions.add(new Question(questionId, sample.path("question").asText(),
                    sample.path("expectation").asText(), true, sample.path("rubric").asText(),
                    sample.path("referenceAnswer").asText(), docs, gold));
        }
        var definition = new Definition("答案支撑校准", "用户确认五项口径的合成机制集", "MECHANISM", questions);
        var created = request("POST", "/datasets", "definition", definition, 201);
        String dataset = created.path("datasetId").asText();
        var baseline = finish(dataset, questions, fixture, "reference");
        var candidate = finish(dataset, questions, fixture, "candidate");
        var failed = finish(dataset, questions, fixture, "failed");
        assertThat(request("GET", "/runs/" + candidate.summary().runId() + "/reviews", null, null, 200)
                .path("eligibility").path("eligible").asBoolean()).isFalse();
        for (int i = 0; i < questions.size(); i++) {
            var question = questions.get(i); var sample = fixture.path("cases").get(i);
            var grades = json.treeToValue(sample.path("assessment"), Assessment.class);
            var good = new Assessment(grades.citationCorrect() == null ? null : Judgement.PASS,
                    grades.supported() == null ? null : Judgement.PASS, grades.coverage() == null ? null : Judgement.PASS,
                    grades.refusal() == null ? null : Judgement.PASS);
            save(baseline, question, good, "参考答案逐项按固定原文与rubric核对", 0, "reference-" + i);
            var receipt = save(candidate, question, grades, sample.path("reason").asText(), 0, "candidate-" + i);
            assertThat(save(candidate, question, grades, sample.path("reason").asText(), 0, "candidate-" + i)).isEqualTo(receipt);
            var history = request("GET", reviewPath(candidate, question) + "?manifestDigest=" + candidate.report().manifestDigest(), null, null, 200);
            assertThat(history.path("items")).hasSize(1);
            assertThat(history.path("items").get(0).path("assessment")).isEqualTo(sample.path("assessment"));
            assertThat(history.path("items").get(0).path("reviewer").asText()).isEqualTo(owner.userId());
            if (i == 0) {
                request("POST", reviewPath(failed, question), "failed-" + i,
                        new ReviewCommand(failed.report().manifestDigest(), 0L, good, "失败执行不能评分通过"), 400);
            }
        }
        var state = request("GET", "/runs/" + baseline.summary().runId() + "/reviews", null, null, 200);
        var selected = request("POST", "/datasets/" + dataset + "/revisions/1/baseline", "baseline",
                new SelectBaseline(0L, baseline.summary().runId(), baseline.report().manifestDigest(), state.path("reviewDigest").asText(),
                        "固定原文的正确参考答案，仅作校准比较基线"), 201);
        String baselineId = selected.path("baselineId").asText();
        var compared = request("GET", "/runs/" + candidate.summary().runId() + "/comparison?baselineId=" + baselineId, null, null, 200);
        assertThat(compared.path("status").asText()).isEqualTo("FAIL");
        assertThat(compared.path("questions")).hasSize(5);
        for (int i = 0; i < 5; i++) {
            assertThat(compared.path("questions").get(i).path("candidate").path("review").path("assessment"))
                    .isEqualTo(fixture.path("cases").get(i).path("assessment"));
        }
        var failedState = request("GET", "/runs/" + failed.summary().runId() + "/reviews", null, null, 200);
        assertThat(failedState.path("eligibility").path("eligible").asBoolean()).isFalse();
        assertThat(failedState.path("questions")).allSatisfy(row -> assertThat(row.path("reviewable").asBoolean()).isFalse());
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().getParent();
        Path directory = Files.createTempDirectory(root.resolve(".logs"), "s12b-calibration-");
        var browser = json.createObjectNode().put("knowledgeEndpoint", "http://127.0.0.1:" + port)
                .put("tenant", owner.tenantId()).put("owner", owner.userId()).put("backendRoot", root.toString())
                .put("datasetId", dataset).put("runId", candidate.summary().runId()).put("baselineId", baselineId);
        var samples = browser.putArray("samples");
        for (int i = 0; i < questions.size(); i++) {
            var sample = fixture.path("cases").get(i).deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) sample).put("questionId", questions.get(i).questionId()); samples.add(sample);
        }
        Files.writeString(directory.resolve("browser.json"), json.writeValueAsString(browser));
        Files.writeString(directory.resolve("comparison.json"), json.writeValueAsString(compared));
        browser(root, directory);
        Files.writeString(directory.resolve("result.json"), json.writeValueAsString(Map.of("status", "PASS", "samples", 5,
                "realDatabaseAndHttp", true, "centralObservation", "test double", "paidCalls", 0,
                "browser", "1".equals(System.getenv("AGENTIC_CALIBRATION_BROWSER")))));
    }

    /** 使用真实发布账本与原文，向量仅测试夹具；不把它当真实Embedding或检索效果。 */
    private EvidenceOption publish(String name, String text) {
        byte[] source = text.getBytes(StandardCharsets.UTF_8); String key = UUID.randomUUID().toString();
        var job = knowledge.submit(owner, key, UploadRules.validate(key, name + ".md", name, source));
        var claim = processing.claim("calibration-fixture", 60, owner.tenantId()).orElseThrow();
        var prepared = ProcessingFixture.document(job, source);
        claim = processing.prepared(claim, new ProcessingClaim.PreparedReference("fixtures/" + job.jobId(), "a".repeat(64), 128, prepared.batchDigest()), prepared);
        processing.sinkDone(claim, ProcessingClaim.Sink.VECTOR); processing.sinkDone(claim, ProcessingClaim.Sink.KEYWORD); processing.publish(claim);
        return definitions.publishedEvidence(owner, List.of(prepared.chunks().getFirst().chunkId())).getFirst();
    }

    /** 模型输出固定为校准答案，报告经现有领域校验与持久清单确认，不绕过评分API。 */
    private RunView finish(String dataset, List<Question> questions, JsonNode fixture, String mode) throws Exception {
        var accepted = request("POST", "/runs", mode, new Start(dataset, 1L, 5), 202);
        String id = accepted.path("summary").path("runId").asText(); var run = reports.get(owner, id).orElseThrow();
        observe(run, TaskStatus.RUNNING, null);
        var opened = runs.open(owner, "worker", id, new Open(run.taskId(), 1L,
                new Profile("fixed-calibration-answers", "chat-default", 512, "a".repeat(64), RunProtocol.RETRIEVAL)));
        var receipts = new ArrayList<CandidateReceipt>();
        for (int i = 0; i < questions.size(); i++) {
            var question = questions.get(i); boolean refusal = "NO_ANSWER".equals(question.expectation()); boolean failed = "failed".equals(mode);
            var published = definitions.publishedEvidence(owner, scopeChunks.get(question.questionId()));
            // 无答案题保留不含目标事实的真实资料；不能把语义拒答等同空召回。
            // 引用编号遵循校准原文顺序，不能依赖数据库返回顺序。
            var evidence = scopeChunks.get(question.questionId()).stream().map(chunk -> published.stream()
                    .filter(item -> item.chunkId().equals(chunk)).findFirst().orElseThrow()).toList();
            var hits = evidence.stream().map(e -> new Retrieved(e.documentId(), e.version(), e.chunkId(), e.textHash(), 1.0, e.source())).toList();
            var citations = new ArrayList<Citation>();
            for (int n = 0; n < evidence.size() && ("reference".equals(mode) || n == 0); n++) {
                var e = evidence.get(n); citations.add(new Citation(Integer.toString(n + 1), e.documentId(), e.version(), e.chunkId(), e.source()));
            }
            var answer = failed ? null : new Answer(RunProtocol.QA_RESULT, "ANSWERED",
                    fixture.path("cases").get(i).path("reference".equals(mode) ? "referenceAnswer" : "answer").asText(), citations);
            var report = new QuestionReport(question.questionId(), failed ? "FAILED" : "SUCCEEDED", answer, failed ? List.of() : hits,
                    refusal || failed ? null : new Metrics(1.0, 1.0, 1.0), new Timing(0L, null, 0L, 0L),
                    new Cost(0, null, null, "0", "0"), failed ? "AI_UNAVAILABLE" : null);
            receipts.add(runs.candidate(owner, "worker", id, question.questionId(), new CandidateCommand(run.taskId(), 1L, report)));
            // 清单协议只接受成功前缀及末尾一个失败题；失败后未执行题不可评审。
            if (failed) { break; }
        }
        var manifest = runs.manifest(owner, "worker", id, 1, new ManifestCommand(run.taskId(), opened.profileDigest(), !"failed".equals(mode), receipts));
        observe(run, "failed".equals(mode) ? TaskStatus.FAILED : TaskStatus.SUCCEEDED, new Completion(RunProtocol.COMPLETION, id, 1, manifest.manifestDigest()));
        return runs.detail(owner, id);
    }
    private void observe(Run run, TaskStatus status, Completion proof) {
        var task = new TaskView(run.taskId(), RunProtocol.KIND, status, proof == null ? 1 : 2, 1, 0, false,
                status == TaskStatus.FAILED ? "AI_UNAVAILABLE" : null, proof, proof == null ? null : "b".repeat(64), Instant.now(), Instant.now());
        central.observations.put(run.taskId(), new Observation(task, owner.tenantId(), owner.userId(), run.input(), RunProtocol.RUNTIME, run.graph(), 1,
                "worker", Instant.now().plusSeconds(300), null));
    }
    private String reviewPath(RunView run, Question q) { return "/runs/" + run.summary().runId() + "/questions/" + q.questionId() + "/reviews"; }
    private JsonNode save(RunView run, Question q, Assessment grades, String note, long revision, String key) throws Exception {
        return request("POST", reviewPath(run, q), key, new ReviewCommand(run.report().manifestDigest(), revision, grades, note), 201);
    }
    /** 签名只用于本机隔离HTTP，不写入fixture、日志或浏览器。 */
    private JsonNode request(String method, String path, String key, Object body, int status) throws Exception {
        var rsa = new RSAKey.Builder(JwtTrust.publicKey(publicKey)).privateKey(JwtTrust.privateKey(privateKey)).build();
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsa))); Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(IdentityProtocol.INTERNAL_ISSUER).audience(List.of(IdentityProtocol.KNOWLEDGE_AUDIENCE))
                .subject(owner.userId()).issuedAt(now).expiresAt(now.plusSeconds(60)).claim(IdentityProtocol.TENANT_CLAIM, owner.tenantId())
                .claim(IdentityProtocol.SCOPE_CLAIM, "knowledge:read knowledge:write").build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + EvaluationProtocol.ROOT + path))
                .timeout(Duration.ofSeconds(15)).header(IdentityProtocol.INTERNAL_HEADER, token).header("Content-Type", "application/json");
        if (key != null) { request.header("Idempotency-Key", key); }
        var response = http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path + " " + response.body()).isEqualTo(status);
        return json.readTree(response.body());
    }
    /** 浏览器只连接当前测试端口；有界等待后关闭测试子进程，服务生命周期仍由Spring管理。 */
    private void browser(Path root, Path directory) throws Exception {
        if (!"1".equals(System.getenv("AGENTIC_CALIBRATION_BROWSER"))) { return; }
        Path frontend = root.resolveSibling("agentic-ai-platform-web");
        var builder = new ProcessBuilder("pnpm", "exec", "playwright", "test", "tests/answer-quality-real.spec.ts", "--reporter=list",
                "--output=.logs/s12b-real-browser-artifacts").directory(frontend.toFile()).redirectErrorStream(true)
                .redirectOutput(directory.resolve("browser.log").toFile());
        builder.environment().put("PATH", frontend.resolve(".local/toolchains/node-v24.22.0-darwin-arm64/bin") + ":" + System.getenv("PATH"));
        builder.environment().put("ANSWER_QUALITY_FIXTURE", directory.resolve("browser.json").toString());
        builder.environment().put("PLAYWRIGHT_BASE_URL", "http://127.0.0.1:28186");
        Process process = builder.start();
        try { assertThat(process.waitFor(120, TimeUnit.SECONDS)).isTrue(); assertThat(process.exitValue()).as(directory.resolve("browser.log").toString()).isZero(); }
        finally { if (process.isAlive()) { process.destroy(); process.waitFor(10, TimeUnit.SECONDS); } }
    }
    @TestConfiguration
    static class Configuration {
        @Bean @Primary CalibrationCentral calibrationCentral() { return new CalibrationCentral(); }
    }
    /** 仅替换中央任务观察；真实评分接口和所有持久仓库均不替换。 */
    static final class CalibrationCentral implements EvaluationTaskGateway {
        final Map<String, Observation> observations = new ConcurrentHashMap<>();
        @Override public boolean enabled() { return true; }
        @Override public Optional<Observation> inspect(Caller caller, String taskId) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return Optional.ofNullable(observations.get(taskId))
                    .filter(value -> value.tenantId().equals(caller.tenantId()) && value.ownerId().equals(caller.userId()));
        }
        @Override public void submit(Caller caller, Run run) { throw new AssertionError("fixture does not dispatch"); }
        @Override public void cancel(Caller caller, String taskId, long revision) { throw new AssertionError("fixture does not cancel"); }
        @Override public void resume(Caller caller, String taskId, long revision) { throw new AssertionError("fixture does not resume"); }
    }

}

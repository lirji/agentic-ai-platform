package com.lrj.agentic.task.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.contract.knowledge.CitationProtocol;
import com.lrj.agentic.contract.knowledge.GenerationCitationProtocol;
import com.lrj.agentic.security.Caller;
import com.lrj.agentic.security.IdentityProtocol;
import com.lrj.agentic.security.JwtTrust;
import com.lrj.agentic.task.config.KnowledgeReaderProperties;
import com.lrj.agentic.task.domain.TaskError;
import com.lrj.agentic.task.domain.TaskFailure;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

/** Tasks仅可问当前引用是否可读；不持锁、不重试、不跨请求缓存权限。 */
@Component
public class KnowledgeCitationClient {
    private final KnowledgeReaderProperties properties;
    private final ObjectMapper json;
    private final JwtEncoder encoder;
    private final Tracer tracer;
    private final Propagator propagator;
    private final Semaphore slots = new Semaphore(CitationProtocol.MAX_CONCURRENT);
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), work -> new Thread(work, "task-knowledge-http"), new ThreadPoolExecutor.AbortPolicy());
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2)).executor(executor).followRedirects(HttpClient.Redirect.NEVER).build();

    /** 私钥仅限本服务独立角色，启动时验证配对，不共享Gateway或worker签名者。 */
    public KnowledgeCitationClient(KnowledgeReaderProperties properties, ObjectMapper json,
            Tracer tracer, Propagator propagator, MeterRegistry meters) throws IOException {
        this.properties = properties; this.json = json; this.tracer = tracer; this.propagator = propagator;
        if (properties.enabled()) {
            var publicKey = JwtTrust.publicKey(properties.publicKey()); var privateKey = JwtTrust.privateKey(properties.privateKey());
            if (!publicKey.getModulus().equals(privateKey.getModulus())) { throw new IllegalArgumentException("知识读取身份密钥不配对"); }
            encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(new RSAKey.Builder(publicKey).privateKey(privateKey).build())));
        } else { encoder = null; }
        new ExecutorServiceMetrics(executor, "task.knowledge.http", List.of()).bindTo(meters);
        meters.gauge("task.knowledge.inflight", slots, value -> CitationProtocol.MAX_CONCURRENT - value.availablePermits());
    }

    /** 整个公共读取共享同一期限；队列满和依赖不可用统一503，不能当作资源不存在。 */
    public boolean accessible(Caller owner, List<CitationProtocol.Reference> references, long deadline) {
        CitationProtocol.validateReferences(references, 0, CitationProtocol.MAX_REFERENCES);
        if (references.isEmpty()) { return true; }
        return check(owner, new CitationProtocol.Check(references), CitationProtocol.PATH, deadline);
    }

    /** 完整身份使用独立v2路径，密钥、容量与总期限仍共享原受限角色。 */
    public boolean generationsAccessible(Caller owner, List<GenerationCitationProtocol.Reference> references, long deadline) {
        GenerationCitationProtocol.normalized(references, 0, GenerationCitationProtocol.MAX_REFERENCES);
        if (references.isEmpty()) { return true; }
        return check(owner, new GenerationCitationProtocol.Check(GenerationCitationProtocol.SCHEMA, references), GenerationCitationProtocol.PATH, deadline);
    }

    private boolean check(Caller owner, Object command, String path, long deadline) {
        if (encoder == null || System.nanoTime() >= deadline || !slots.tryAcquire()) { unavailable(); }
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        var span = tracer.nextSpan().name("tasks.knowledge-citations").start();
        try (var scope = tracer.withSpan(span)) {
            Instant now = Instant.now();
            var claims = JwtClaimsSet.builder().issuer(CitationProtocol.ISSUER).audience(List.of(CitationProtocol.AUDIENCE))
                    .subject(owner.userId()).issuedAt(now).notBefore(now).expiresAt(now.plusSeconds(60))
                    .claim("azp", CitationProtocol.PARTY).claim(IdentityProtocol.TENANT_CLAIM, owner.tenantId())
                    .claim(IdentityProtocol.SCOPE_CLAIM, CitationProtocol.SCOPE).build();
            String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
            byte[] body = json.writeValueAsBytes(command);
            if (body.length > CitationProtocol.MAX_BYTES) { unavailable(); }
            long remaining = deadline - System.nanoTime(); if (remaining <= 0) { unavailable(); }
            var builder = HttpRequest.newBuilder(properties.endpoint().resolve(path)).timeout(Duration.ofNanos(remaining))
                    .header(IdentityProtocol.INTERNAL_HEADER, token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            Map<String, String> traceHeaders = new HashMap<>(); propagator.inject(span.context(), traceHeaders, Map::put);
            traceHeaders.forEach(builder::header);
            pending = client.sendAsync(builder.build(), ignored -> new LimitedBody());
            var response = pending.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (response.statusCode() != 200 || System.nanoTime() >= deadline) { unavailable(); }
            var value = json.readTree(response.body());
            if (value == null || !value.isObject() || value.size() != 1 || !value.path("accessible").isBoolean()) { unavailable(); }
            return value.path("accessible").booleanValue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new TaskFailure(TaskError.DEPENDENCY_UNAVAILABLE);
        } catch (IOException | ExecutionException | TimeoutException | RejectedExecutionException | IllegalArgumentException failure) {
            throw new TaskFailure(TaskError.DEPENDENCY_UNAVAILABLE);
        } finally {
            if (pending != null && !pending.isDone()) { pending.cancel(true); }
            span.end(); slots.release();
        }
    }
    /** 停止传输回调，不无限等待远程服务。 */
    @PreDestroy public void close() { client.shutdownNow(); executor.shutdownNow(); }
    private static void unavailable() { throw new TaskFailure(TaskError.DEPENDENCY_UNAVAILABLE); }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (bytes.size() + buffer.remaining() > CitationProtocol.MAX_BYTES) {
                    subscription.cancel(); result.completeExceptionally(new IOException("知识校验响应超过限制")); return;
                }
                var part = new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        public void onError(Throwable failure) { result.completeExceptionally(failure); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}

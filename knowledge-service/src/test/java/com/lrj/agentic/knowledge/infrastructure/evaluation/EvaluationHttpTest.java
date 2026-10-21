package com.lrj.agentic.knowledge.infrastructure.evaluation;

import static org.assertj.core.api.Assertions.*;

import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.evaluation.RunProtocol;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 真实本机传输验证全响应期限、容量拒绝、响应上界和不跟随重定向。 */
class EvaluationHttpTest {
    @Test void saturatedRequestsAreRejectedAndCapacityReturnsAfterCompletion() throws Exception {
        try (var fixture = new Fixture()) {
            var entered = new CountDownLatch(RunProtocol.HTTP_CAPACITY);
            var release = new CountDownLatch(1);
            fixture.server.createContext("/wait", exchange -> {
                entered.countDown();
                try { release.await(4, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                exchange.sendResponseHeaders(204, -1); exchange.close();
            });
            try (var callers = Executors.newFixedThreadPool(RunProtocol.HTTP_CAPACITY)) {
                var pending = new java.util.ArrayList<Future<EvaluationHttp.Reply>>();
                for (int i = 0; i < RunProtocol.HTTP_CAPACITY; i++) {
                    pending.add(callers.submit(() -> fixture.get("/wait")));
                }
                try {
                    assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                    assertFailure(KnowledgeError.BUSY, () -> fixture.get("/ok"));
                } finally { release.countDown(); }
                for (var result : pending) { assertThat(result.get(3, TimeUnit.SECONDS).status()).isEqualTo(204); }
            }
            assertThat(fixture.get("/ok").status()).isEqualTo(204);
        }
    }

    @Test void deadlineCoversSlowBodyAndReleasesTheRequestSlot() throws Exception {
        try (var fixture = new Fixture()) {
            var release = new CountDownLatch(1);
            fixture.server.createContext("/slow", exchange -> {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('x'); exchange.getResponseBody().flush();
                try { release.await(8, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                exchange.close();
            });
            long start = System.nanoTime();
            try { assertFailure(KnowledgeError.EVALUATION_TASK_UNAVAILABLE, () -> fixture.get("/slow")); }
            finally { release.countDown(); }
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isBetween(4000L, 7000L);
            assertThat(fixture.get("/ok").status()).isEqualTo(204);
        }
    }

    @Test void oversizedChunkedBodyIsRejectedWithoutRetryOrRedirect() throws Exception {
        try (var fixture = new Fixture()) {
            var requests = new AtomicInteger();
            fixture.server.createContext("/large", exchange -> {
                requests.incrementAndGet(); exchange.sendResponseHeaders(200, 0);
                try { exchange.getResponseBody().write(new byte[RunProtocol.HTTP_BYTES + 1]); }
                finally { exchange.close(); }
            });
            fixture.server.createContext("/redirect", exchange -> {
                exchange.getResponseHeaders().add("Location", fixture.uri("/large").toString());
                exchange.sendResponseHeaders(302, -1); exchange.close();
            });
            assertThat(fixture.get("/redirect").status()).isEqualTo(302);
            assertThat(requests).hasValue(0);
            assertFailure(KnowledgeError.EVALUATION_TASK_UNAVAILABLE, () -> fixture.get("/large"));
            assertThat(requests).hasValue(1);
        }
    }

    private static void assertFailure(KnowledgeError code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(KnowledgeFailure.class, failure -> assertThat(failure.error()).isEqualTo(code));
    }

    private static final class Fixture implements AutoCloseable {
        final ExecutorService serverThreads = Executors.newFixedThreadPool(4);
        final HttpServer server;
        final EvaluationHttp transport;
        final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Fixture() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
            server.setExecutor(serverThreads);
            server.createContext("/ok", exchange -> { exchange.sendResponseHeaders(204, -1); exchange.close(); });
            server.start();
            transport = new EvaluationHttp(Tracer.NOOP, Propagator.NOOP, meters);
        }
        URI uri(String path) { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path); }
        EvaluationHttp.Reply get(String path) { return transport.request("GET", uri(path), Map.of(), null); }
        @Override public void close() { transport.close(); server.stop(0); serverThreads.shutdownNow(); meters.close(); }
    }
}

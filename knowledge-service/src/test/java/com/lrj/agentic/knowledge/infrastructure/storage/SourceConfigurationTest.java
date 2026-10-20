package com.lrj.agentic.knowledge.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 本机代理故障不能把发往 loopback 桶的签名请求转发出去。 */
class SourceConfigurationTest {
    @Test
    void localS3UsesOriginEvenWhenGlobalProxyWouldRejectRequest() throws Exception {
        var origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var originCalls = new AtomicInteger();
        var proxyCalls = new AtomicInteger();
        origin.createContext("/", exchange -> {
            originCalls.incrementAndGet();
            exchange.getResponseHeaders().set("x-amz-bucket-region", "us-east-1");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        proxy.createContext("/", exchange -> {
            proxyCalls.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        var saved = new HashMap<String, String>();
        for (String key : List.of("http.proxyHost", "http.proxyPort", "http.nonProxyHosts")) {
            saved.put(key, System.getProperty(key));
        }
        try {
            origin.start();
            proxy.start();
            System.setProperty("http.proxyHost", "127.0.0.1");
            System.setProperty("http.proxyPort", Integer.toString(proxy.getAddress().getPort()));
            System.setProperty("http.nonProxyHosts", "");
            var properties = new SourceProperties(
                    URI.create("http://127.0.0.1:" + origin.getAddress().getPort()),
                    "us-east-1", "test-bucket", "test-key", "test-secret", 2, 2, 3);
            try (var client = new SourceConfiguration().sourceClient(properties)) {
                client.headBucket(request -> request.bucket(properties.bucket()));
            }
            assertThat(originCalls).hasValue(1);
            assertThat(proxyCalls).hasValue(0);
        } finally {
            saved.forEach((key, value) -> {
                if (value == null) { System.clearProperty(key); }
                else { System.setProperty(key, value); }
            });
            origin.stop(0);
            proxy.stop(0);
        }
    }
}

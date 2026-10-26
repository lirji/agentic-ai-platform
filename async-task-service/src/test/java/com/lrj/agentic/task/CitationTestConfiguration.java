package com.lrj.agentic.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.contract.knowledge.CitationProtocol.Reference;
import com.lrj.agentic.security.Caller;
import com.lrj.agentic.task.config.KnowledgeReaderProperties;
import com.lrj.agentic.task.infrastructure.KnowledgeCitationClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.io.IOException;
import java.util.List;
import java.util.function.Predicate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;

/** 只替换远程权限响应，MySQL租约/事件/事务仍使用真实实现；不增加测试框架依赖。 */
@TestConfiguration(proxyBeanMethods = false)
class CitationTestConfiguration {
    @Bean @Primary ControlledCitations controlledCitations(ObjectMapper json, Tracer tracer,
            Propagator propagator, MeterRegistry meters) throws IOException {
        return new ControlledCitations(json, tracer, propagator, meters);
    }
    static class ControlledCitations extends KnowledgeCitationClient {
        Predicate<List<Reference>> response = references -> true;
        int calls;
        ControlledCitations(ObjectMapper json, Tracer tracer, Propagator propagator, MeterRegistry meters) throws IOException {
            super(new KnowledgeReaderProperties(false, null, null, null), json, tracer, propagator, meters);
        }
        @Override public boolean accessible(Caller owner, List<Reference> refs, long deadline) {
            if (refs.isEmpty()) { return true; }
            calls++; return response.test(refs);
        }
        void reset() { calls = 0; response = references -> true; }
    }
}

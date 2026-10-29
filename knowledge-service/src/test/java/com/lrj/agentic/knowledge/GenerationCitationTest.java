package com.lrj.agentic.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.contract.knowledge.GenerationCitationProtocol;
import com.lrj.agentic.contract.knowledge.GenerationCitationProtocol.Reference;
import com.lrj.agentic.knowledge.application.GenerationCitationCheck;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.retrieval.RetrievalRepository.PublishedChunk;
import com.lrj.agentic.knowledge.infrastructure.processing.ProcessingJson;
import com.lrj.agentic.security.Caller;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 与Python共享真实DTO金标；覆盖完整身份漂移和存储异常，不冒充真实SQL验收。 */
class GenerationCitationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProcessingJson json = new ProcessingJson(mapper);

    private JsonNode golden() throws Exception {
        try (var stream = getClass().getResourceAsStream("/generation-evidence-golden.json")) {
            return mapper.readTree(stream);
        }
    }
    private GenerationCitationProtocol.Check check(JsonNode node) throws Exception {
        return json.read(mapper.writeValueAsBytes(node), GenerationCitationProtocol.Check.class, KnowledgeError.INPUT_INVALID).validated();
    }

    @Test void sharedWireAndDigestRejectPartialOrCoercedIdentity() throws Exception {
        var golden = golden();
        assertThat(check(golden.get("valid")).references()).hasSize(1);
        for (var sample : golden.get("digests")) {
            var refs = json.read(mapper.writeValueAsBytes(sample.get("references")), Reference[].class, KnowledgeError.INPUT_INVALID);
            assertThat(GenerationCitationProtocol.digest(Arrays.asList(refs))).isEqualTo(sample.get("digest").asText());
        }
        for (var sample : golden.get("invalid")) {
            assertThatThrownBy(() -> check(sample.get("body"))).as(sample.get("name").asText()).isInstanceOfAny(KnowledgeFailure.class, IllegalArgumentException.class);
        }
        byte[] duplicate = mapper.writeValueAsString(golden.get("valid")).replace("\"version\":1", "\"version\":1,\"version\":2").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(() -> json.read(duplicate, GenerationCitationProtocol.Check.class, KnowledgeError.INPUT_INVALID)).isInstanceOf(KnowledgeFailure.class);
    }

    @Test void allEightComponentsAreRequiredAndCheckedInOneRead() throws Exception {
        Reference ref = check(golden().get("valid")).references().getFirst();
        var owner = new Caller("generation-test", "owner");
        var calls = new AtomicInteger();
        var service = new GenerationCitationCheck((caller, ids) -> {
            assertThat(caller).isEqualTo(owner); assertThat(ids).containsExactly(ref.chunkId()); calls.incrementAndGet();
            return List.of(new PublishedChunk(ref.documentId(), ref.version(), ref.indexGeneration(), ref.jobId(),
                    ref.processorVersion(), ref.chunkId(), ref.batchDigest(), "原文", ref.textHash(), 0, 6, "资料", null));
        });
        assertThat(service.accessible(owner, List.of(ref))).isTrue();
        assertThat(calls.get()).isEqualTo(1);
        for (String field : List.of("documentId", "version", "indexGeneration", "jobId", "processorVersion", "batchDigest", "textHash")) {
            var tree = mapper.valueToTree(ref).deepCopy();
            var object = (com.fasterxml.jackson.databind.node.ObjectNode) tree;
            if (field.equals("version") || field.equals("indexGeneration")) object.put(field, 2);
            else if (field.equals("documentId") || field.equals("jobId")) object.put(field, "00000000-0000-0000-0000-000000000003");
            else if (field.equals("processorVersion")) object.put(field, "other-profile");
            else object.put(field, "d".repeat(64));
            Reference stale = mapper.treeToValue(tree, Reference.class).validated();
            assertThat(service.accessible(owner, List.of(stale))).as(field).isFalse();
        }
        assertThat(new GenerationCitationCheck((caller, ids) -> List.of()).accessible(owner, List.of(ref))).isFalse();
        assertThatThrownBy(() -> new GenerationCitationCheck((caller, ids) -> { throw new KnowledgeFailure(KnowledgeError.DATABASE_UNAVAILABLE); }).accessible(owner, List.of(ref)))
                .isInstanceOf(KnowledgeFailure.class);
    }
}

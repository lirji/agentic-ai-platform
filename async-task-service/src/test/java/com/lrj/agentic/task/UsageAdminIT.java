package com.lrj.agentic.task;

import com.lrj.agentic.task.usage.application.*;
import com.lrj.agentic.task.usage.contract.UsageAdminDtos.*;
import com.lrj.agentic.task.usage.domain.*;
import com.lrj.agentic.task.usage.infrastructure.UsageMapper;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static com.lrj.agentic.task.usage.contract.UsageAdminDtos.*;

/** 合成资金与冻结文件验证真实MySQL导入，保留数据库证据且不接触实际付费账本。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.main.banner-mode=off", "usage.mode=CENTRAL"})
@Import(CitationTestConfiguration.class)
class UsageAdminIT {
    @Autowired UsageAdminService admin;
    @Autowired UsageAdminFiles files;
    @Autowired UsageMapper mapper;
    @Autowired UsageValues values;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path temporary;
    private static final String OPERATOR = "local:synthetic-test";

    @Test void bootstrapIsIdempotentAndCannotCopyFundingOrChangeGrant() {
        Manifest c = bootstrap();
        assertThat(admin.execute(c, OPERATOR).currentLimit()).isEqualTo("1.000000");
        assertThat(admin.execute(c, OPERATOR).replay()).isTrue();
        Manifest changed = copy(c, c.commandId(), c.action(), c.accountId(), "2.000000", c.state(), c.expectedVersion(), c.profiles(), c.bindings(), null);
        conflict(changed);
        conflict(copy(c, uuid(), c.action(), uuid(), c.limitAmount(), c.state(), null, c.profiles(), List.of(), null));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usage_audit WHERE account_id=?", Integer.class, c.accountId())).isEqualTo(1);
    }
    @Test void configureRequiresVersionAndPreservesImmutablePricesAndAccount() {
        Manifest c = bootstrap(); var first = admin.execute(c, OPERATOR);
        Manifest freeze = copy(c, uuid(), "CONFIGURE", c.accountId(), c.limitAmount(), "FROZEN", first.currentVersion(), List.of(),
                List.of(new Binding(c.bindings().getFirst().tenantId(), c.profiles().getFirst().profileId(), c.profiles().getFirst().priceVersion(), false)), null);
        var frozen = admin.execute(freeze, OPERATOR);
        assertThat(frozen.currentState()).isEqualTo("FROZEN");
        assertThat(mapper.binding(c.bindings().getFirst().tenantId(), "EMBEDDING", c.profiles().getFirst().profileId()).enabled()).isFalse();
        assertThat(admin.execute(freeze, OPERATOR).replay()).isTrue();
        conflict(copy(freeze, uuid(), freeze.action(), c.accountId(), c.limitAmount(), "ACTIVE", first.currentVersion(), List.of(), List.of(), null));
        var original = c.profiles().getFirst();
        var modified = new Profile(original.profileId(), original.priceVersion(), price("3.000000"));
        conflict(copy(c, uuid(), "CONFIGURE", c.accountId(), c.limitAmount(), "ACTIVE", frozen.currentVersion(), List.of(modified), List.of(), null));
        assertThat(mapper.account(c.accountId()).state()).isEqualTo("FROZEN");
    }
    @Test void importCarriesUnknownAndExistingCostsWithoutIncreasingGrant() throws Exception {
        Manifest c = bootstrap(); admin.execute(c, OPERATOR);
        jdbc.update("UPDATE usage_account SET held_amount=0.200000,booked_amount=0.300000 WHERE account_id=?", c.accountId());
        Source source = source(uuid(), "0.600000", "HTTP_200");
        Manifest importing = copy(c, uuid(), "IMPORT", c.accountId(), c.limitAmount(), null, 1L, List.of(), List.of(), source);
        var result = admin.execute(importing, OPERATOR);
        assertThat(result.currentLimit()).isEqualTo("1.000000");
        assertThat(result.currentHeld()).isEqualTo("0.800000");
        assertThat(result.currentBooked()).isEqualTo("0.300000");
        assertThat(result.currentState()).isEqualTo("FROZEN");
        assertThat(jdbc.queryForMap("SELECT state,attribution FROM usage_import_entry WHERE source_ledger_id=?", source.sourceLedgerId()))
                .containsEntry("state", "UNKNOWN").containsEntry("attribution", "LEGACY_UNATTRIBUTED");
        assertThat(admin.execute(importing, OPERATOR).replay()).isTrue();
        // 丢失命令ID但仍持有同源收据也只回放；旧expectedVersion不触发第二次写入。
        assertThat(admin.execute(copy(importing, uuid(), "IMPORT", c.accountId(), c.limitAmount(), null, 1L, List.of(), List.of(), source), OPERATOR).replay()).isTrue();
        assertThat(mapper.account(c.accountId()).heldAmount()).isEqualByComparingTo("0.800000");
    }
    @Test void concurrentSameImportCreatesOneReceiptAndOneHold() throws Exception {
        Manifest c = bootstrap(); admin.execute(c, OPERATOR);
        Manifest importing = copy(c, uuid(), "IMPORT", c.accountId(), c.limitAmount(), null, 1L, List.of(), List.of(), source(uuid(), "0.250000", "RESERVED"));
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Callable<Result>> jobs = new ArrayList<>();
            for (int i = 0; i < 4; i++) jobs.add(() -> admin.execute(importing, OPERATOR));
            List<Result> results = new ArrayList<>();
            for (var result : executor.invokeAll(jobs)) results.add(result.get(10, TimeUnit.SECONDS));
            assertThat(results).filteredOn(result -> !result.replay()).hasSize(1);
        }
        assertThat(mapper.account(c.accountId()).heldAmount()).isEqualByComparingTo("0.250000");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usage_import WHERE account_id=?", Integer.class, c.accountId())).isEqualTo(1);
    }
    @Test void duplicateEntryAcrossDifferentSourcesRollsBackWholeImport() throws Exception {
        String entry = uuid(); Manifest first = bootstrap(), second = bootstrap();
        admin.execute(first, OPERATOR); admin.execute(second, OPERATOR);
        var a = copy(first, uuid(), "IMPORT", first.accountId(), first.limitAmount(), null, 1L, List.of(), List.of(), source(entry, "0.100000", "RESERVED"));
        var b = copy(second, uuid(), "IMPORT", second.accountId(), second.limitAmount(), null, 1L, List.of(), List.of(), source(entry, "0.200000", "RESERVED"));
        admin.execute(a, OPERATOR); conflict(b);
        assertThat(mapper.imported(b.source().sourceLedgerId())).isNull();
        assertThat(mapper.account(second.accountId()).heldAmount()).isZero();
    }
    @Test void alteredFrozenFileUnprivateSourceAndWrongSumAreRejected() throws Exception {
        Manifest c = bootstrap(); Source source = source(uuid(), "0.100000", "RESERVED");
        var importing = copy(c, c.commandId(), "BOOTSTRAP", c.accountId(), c.limitAmount(), "ACTIVE", null, c.profiles(), c.bindings(), source);
        Path path = Path.of(source.path()); String original = Files.readString(path);
        Files.writeString(path, original + " ");
        assertThatThrownBy(() -> admin.execute(importing, OPERATOR)).isInstanceOf(RuntimeException.class);
        assertThat(mapper.account(c.accountId())).isNull();
        Files.writeString(path, original); Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(() -> admin.execute(importing, OPERATOR)).isInstanceOf(RuntimeException.class);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        var bad = new Source(source.path(), source.proofPath(), source.sourceLedgerId(), source.sourceSha256(), "0.200000", 1, source.freezeProofRef());
        assertThatThrownBy(() -> admin.execute(copy(importing, c.commandId(), "BOOTSTRAP", c.accountId(), c.limitAmount(), "ACTIVE", null, c.profiles(), c.bindings(), bad), OPERATOR)).isInstanceOf(RuntimeException.class);
        assertThat(mapper.account(c.accountId())).isNull();
    }
    @Test void movedFrozenFilesReplaySameCommandAndDuplicateJsonIsRejected() throws Exception {
        Manifest c = bootstrap(); Source source = source(uuid(), "0.100000", "RESERVED");
        var command = copy(c, c.commandId(), "BOOTSTRAP", c.accountId(), c.limitAmount(), "ACTIVE", null, c.profiles(), c.bindings(), source);
        admin.execute(command, OPERATOR);
        Path moved = temporary.toRealPath().resolve("moved.json");
        Files.copy(Path.of(source.path()), moved); Files.copy(Path.of(source.proofPath()), Path.of(moved + ".frozen.json"));
        Files.setPosixFilePermissions(moved, PosixFilePermissions.fromString("rw-------"));
        Files.setPosixFilePermissions(Path.of(moved + ".frozen.json"), PosixFilePermissions.fromString("rw-------"));
        var relocated = new Source(moved.toString(), moved + ".frozen.json", source.sourceLedgerId(), source.sourceSha256(), source.reservedAmount(), 1, source.freezeProofRef());
        assertThat(admin.execute(copy(command, c.commandId(), "BOOTSTRAP", c.accountId(), c.limitAmount(), "ACTIVE", null, c.profiles(), c.bindings(), relocated), OPERATOR).replay()).isTrue();
        Path malformed = temporary.toRealPath().resolve("bad-manifest.json");
        privateFile(malformed, "{\"schemaVersion\":\"a\",\"schemaVersion\":\"b\"}");
        assertThatThrownBy(() -> files.manifest(malformed.toString())).isInstanceOf(RuntimeException.class);
    }
    private Manifest bootstrap() {
        String id = uuid(), profile = "p-" + id, version = "v-" + id;
        return new Manifest(SCHEMA, uuid(), "BOOTSTRAP", id, "synthetic", "fixture-" + id, "EMBEDDING", "1.000000", "ACTIVE", null,
                List.of(new Profile(profile, version, price("2.000000"))), List.of(new Binding("tenant-" + id, profile, version, true)), null);
    }
    private Source source(String entryId, String amount, String status) throws Exception {
        Path path = temporary.toRealPath().resolve(uuid() + ".json");
        String ledger = values.write(Map.of("limitCny", "1", "reservedCny", amount, "calls", List.of(Map.of("id", entryId,
                "operation", "S2_PREPARE", "inputBytes", 10, "reservedCny", amount, "status", status))));
        String source = "source-" + uuid(), digest = UsageValues.sha(ledger), proof = "proof-" + uuid();
        privateFile(path, ledger);
        privateFile(Path.of(path + ".frozen.json"), values.write(new FreezeProof(FREEZE_PROTOCOL, source, digest, "EMBEDDING", proof, "fixture-stopped", List.of(999999999L))));
        return new Source(path.toString(), path + ".frozen.json", source, digest, amount, 1, proof);
    }
    private static UsagePrice price(String rate) { return new UsagePrice("synthetic-model", "TOTAL", null, null, rate, 8192, 0, 0); }
    private static Manifest copy(Manifest c, String command, String action, String account, String limit, String state, Long version, List<Profile> profiles, List<Binding> bindings, Source source) {
        return new Manifest(c.schemaVersion(), command, action, account, c.sponsorRef(), c.fundingSourceRef(), c.kind(), limit, state, version, profiles, bindings, source);
    }
    private void conflict(Manifest command) {
        assertThatThrownBy(() -> admin.execute(command, OPERATOR)).isInstanceOfSatisfying(UsageFailure.class, error -> assertThat(error.error()).isEqualTo(UsageError.CONFLICT));
    }
    private static void privateFile(Path file, String value) throws Exception { Files.writeString(file, value); Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")); }
    private static String uuid() { return UUID.randomUUID().toString(); }
}

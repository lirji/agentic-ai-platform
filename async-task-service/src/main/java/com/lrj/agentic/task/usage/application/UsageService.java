package com.lrj.agentic.task.usage.application;

import com.lrj.agentic.task.usage.contract.UsageDtos;
import com.lrj.agentic.task.usage.contract.UsagePrincipal;
import com.lrj.agentic.task.usage.domain.*;
import com.lrj.agentic.task.usage.domain.UsageTypes.UsageAccountState;
import com.lrj.agentic.task.usage.domain.UsageTypes.Event;
import com.lrj.agentic.task.usage.infrastructure.UsageMapper;
import com.lrj.agentic.task.usage.infrastructure.UsageRows;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import java.util.List;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.stream.Collectors;
import java.util.function.Supplier;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.lrj.agentic.task.usage.domain.UsageFailure.require;

/** 用量事务只写本域表；模型外发必须在提交后由一次性许可的接收者执行。 */
@Service
public class UsageService {
    private final UsageMapper mapper;
    private final UsageValues values;
    private final UsageContexts contexts;
    private final UsageProperties properties;
    private final TransactionTemplate tx;
    private static final int MAX_TRANSACTION_ATTEMPTS = 3;

    /** 复用Tasks数据库事务，固定task→account→operation→attempt的锁顺序。 */
    public UsageService(UsageMapper mapper, UsageValues values, UsageContexts contexts,
            UsageProperties properties, PlatformTransactionManager manager) {
        this.mapper = mapper; this.values = values; this.contexts = contexts; this.properties = properties;
        tx = new TransactionTemplate(manager); tx.setTimeout(UsageProtocol.TRANSACTION_SECONDS);
    }

    /** 同attempt预留幂等，但这个响应从不包含发送许可。 */
    public UsageDtos.Attempt reserve(UsagePrincipal principal, UsageDtos.Reserve command) {
        UsageValues.principal(principal); UsageValues.reserve(command);
        require(properties.central(), UsageError.DISABLED);
        String requestHash = values.hash(command);
        return transaction(() -> {
            String actor = contexts.active(principal, command.contextType(), command.contextId(), command.epoch(),
                    command.purpose(), command.kind(), command.profileId(), command.stepKey());
            var existing = mapper.attempt(principal.caller().tenantId(), command.attemptId());
            if (existing != null) {
                source(principal, existing); require(existing.workerId().equals(principal.caller().userId())
                        && existing.requestSha256().equals(requestHash), UsageError.CONFLICT);
                return view(existing);
            }
            var selected = found(mapper.binding(principal.caller().tenantId(), command.kind(), command.profileId()), UsageError.DISABLED);
            var account = found(mapper.lockAccount(selected.accountId()), UsageError.DISABLED);
            var binding = found(mapper.lockBinding(principal.caller().tenantId(), command.kind(), command.profileId()), UsageError.DISABLED);
            require(binding.accountId().equals(account.accountId()) && binding.enabled()
                    && UsageAccountState.ACTIVE.code().equals(account.state()) && account.kind().equals(command.kind()), UsageError.DISABLED);
            var pricing = found(mapper.price(binding.priceVersion()), UsageError.PROFILE_INVALID);
            require(pricing.profileId().equals(command.profileId()) && pricing.kind().equals(command.kind()), UsageError.PROFILE_INVALID);
            var price = values.read(pricing.pricingJson(), UsagePrice.class, UsageError.PROFILE_INVALID);
            var amount = price.reserve(UsageKind.parse(command.kind()), command.inputBytes(), command.maxOutputTokens(),
                    command.queryBytes(), command.documentBytes(), command.documentCount());
            var operation = mapper.operationByKey(principal.caller().tenantId(), command.contextType(), command.contextId(), command.stepKey());
            if (operation == null) {
                operation = new UsageRows.Operation(uuid(), principal.caller().tenantId(), actor, command.contextType(),
                        command.contextId(), command.stepKey(), command.purpose(), command.kind(), command.profileId(),
                        command.inputDigest(), account.accountId(), null);
                changed(mapper.insertOperation(operation));
            } else {
                require(operation.actorId().equals(actor) && operation.purpose().equals(command.purpose())
                        && operation.kind().equals(command.kind()) && operation.profileId().equals(command.profileId())
                        && operation.inputDigest().equals(command.inputDigest()) && operation.accountId().equals(account.accountId()), UsageError.CONFLICT);
            }
            if (command.retryOf() != null) {
                var previous = found(mapper.lockAttempt(principal.caller().tenantId(), command.retryOf()), UsageError.CONFLICT);
                source(principal, previous);
                var previousState = UsageState.parse(previous.state());
                require(previous.operationId().equals(operation.operationId())
                        && (previousState.reportable() || previousState == UsageState.SETTLED), UsageError.CONFLICT);
            }
            require(mapper.hold(account.accountId(), account.version(), amount, principal.caller().userId()) == 1, UsageError.BUDGET_EXCEEDED);
            var attempt = new UsageRows.Attempt(command.attemptId(), operation.operationId(), account.accountId(),
                    principal.caller().tenantId(), principal.caller().userId(), principal.sourceRef(), command.epoch(),
                    requestHash, pricing.priceVersion(), UsageState.RESERVED.code(), amount, null, null,
                    UsageTypes.Outcome.UNKNOWN.code(), null, null, null, command.retryOf(), false, 0, null, null);
            changed(mapper.insertAttempt(attempt));
            audit(principal, attempt, Event.RESERVED, null, amount, UsageMoney.ZERO);
            return view(mapper.attempt(principal.caller().tenantId(), command.attemptId()));
        });
    }

    /** true仅在RESERVED→DISPATCHED提交的第一次响应出现；重放不能复用发送权。 */
    public UsageDtos.Dispatch dispatch(UsagePrincipal principal, String id, UsageDtos.Command command) {
        validate(principal, id); UsageValues.input(command != null); UsageValues.schema(command.schemaVersion());
        require(properties.central(), UsageError.DISABLED);
        return transaction(() -> {
            var before = read(principal, id);
            if (!UsageState.RESERVED.code().equals(before.state())) { return new UsageDtos.Dispatch(view(before), false); }
            require(before.workerId().equals(principal.caller().userId()), UsageError.LEASE_LOST);
            var operation = operation(before);
            String actor = contexts.active(principal, operation.contextType(), operation.contextId(), before.leaseEpoch(),
                    operation.purpose(), operation.kind(), operation.profileId(), operation.stepKey());
            require(actor.equals(operation.actorId()), UsageError.NOT_FOUND);
            var account = found(mapper.lockAccount(before.accountId()), UsageError.DISABLED);
            var binding = found(mapper.lockBinding(before.tenantId(), operation.kind(), operation.profileId()), UsageError.DISABLED);
            require(UsageAccountState.ACTIVE.code().equals(account.state()) && binding.enabled()
                    && binding.accountId().equals(account.accountId()), UsageError.DISABLED);
            var current = locked(principal, id);
            if (!UsageState.RESERVED.code().equals(current.state())) { return new UsageDtos.Dispatch(view(current), false); }
            var changed = copy(current, UsageState.DISPATCHED, null, null, current.outcome(), null, null, null, false);
            changed(mapper.transition(changed, current.state()));
            audit(principal, changed, Event.DISPATCHED, current.state(), UsageMoney.ZERO, UsageMoney.ZERO);
            return new UsageDtos.Dispatch(view(mapper.attempt(current.tenantId(), id)), true);
        });
    }

    /** 只释放数据库证明未曾授予发送许可的预留，不依据HTTP异常猜测免费。 */
    public UsageDtos.Attempt release(UsagePrincipal principal, String id, UsageDtos.Release command) {
        validate(principal, id); UsageValues.input(command != null); UsageValues.schema(command.schemaVersion());
        UsageValues.input(UsageProtocol.NOT_DISPATCHED.equals(command.reason()));
        return transaction(() -> {
            var before = read(principal, id);
            if (!before.workerId().equals(principal.caller().userId())) {
                var operation = operation(before);
                contexts.recoverUnsent(principal, operation.contextType(), operation.contextId(), operation.actorId());
            }
            var account = found(mapper.lockAccount(before.accountId()), UsageError.CONFLICT);
            var current = locked(principal, id);
            if (UsageState.RELEASED.code().equals(current.state())) { return view(current); }
            require(UsageState.RESERVED.code().equals(current.state()), UsageError.CONFLICT);
            BigDecimal held = account.heldAmount().subtract(current.reservedAmount());
            require(held.signum() >= 0, UsageError.CONFLICT);
            changed(mapper.accountChange(account.accountId(), account.version(), held, account.bookedAmount(), account.state(), principal.caller().userId()));
            var changed = copy(current, UsageState.RELEASED, null, null, current.outcome(), null, null, null, false);
            changed(mapper.transition(changed, current.state()));
            audit(principal, changed, Event.RELEASED, current.state(), current.reservedAmount().negate(), UsageMoney.ZERO);
            return view(mapper.attempt(current.tenantId(), id));
        });
    }

    /** 原执行者的迟到计量不依赖当前租约；仍必须匹配原来源和attempt。 */
    public UsageDtos.Attempt report(UsagePrincipal principal, String id, UsageDtos.Report command) {
        validate(principal, id); UsageValues.report(command);
        String hash = values.hash(command);
        return transaction(() -> {
            var before = read(principal, id);
            require(before.workerId().equals(principal.caller().userId()), UsageError.NOT_FOUND);
            var account = found(mapper.lockAccount(before.accountId()), UsageError.CONFLICT);
            var current = locked(principal, id);
            var state = UsageState.parse(current.state());
            if (state == UsageState.SETTLED) {
                require(UsageProtocol.UNKNOWN.equals(command.status()) || hash.equals(current.reportSha256()), UsageError.CONFLICT);
                return view(current);
            }
            require(state.reportable(), UsageError.CONFLICT);
            if (UsageProtocol.UNKNOWN.equals(command.status())) {
                if (state == UsageState.UNKNOWN) { require(hash.equals(current.unknownSha256()), UsageError.CONFLICT); return view(current); }
                var changed = copy(current, UsageState.UNKNOWN, null, null, command.outcome(), command.providerRequestId(), null, hash, false);
                changed(mapper.transition(changed, current.state()));
                audit(principal, changed, Event.UNKNOWN, current.state(), UsageMoney.ZERO, UsageMoney.ZERO);
            } else {
                var operation = operation(current);
                var price = found(mapper.price(current.priceVersion()), UsageError.PROFILE_INVALID);
                var cost = values.read(price.pricingJson(), UsagePrice.class, UsageError.PROFILE_INVALID)
                        .settle(UsageKind.parse(operation.kind()), command.usage().inputTokens(), command.usage().outputTokens(), command.usage().totalTokens());
                BigDecimal held = account.heldAmount().subtract(current.reservedAmount());
                BigDecimal booked = account.bookedAmount().add(cost);
                require(held.signum() >= 0, UsageError.CONFLICT);
                boolean overrun = cost.compareTo(current.reservedAmount()) > 0;
                String accountState = overrun || held.add(booked).compareTo(account.limitAmount()) > 0
                        ? UsageAccountState.FROZEN.code() : account.state();
                changed(mapper.accountChange(account.accountId(), account.version(), held, booked, accountState, principal.caller().userId()));
                var changed = copy(current, UsageState.SETTLED, cost, values.write(command.usage()), command.outcome(),
                        command.providerRequestId(), hash, current.unknownSha256(), overrun);
                changed(mapper.transition(changed, current.state()));
                audit(principal, changed, Event.SETTLED, current.state(), current.reservedAmount().negate(), cost);
            }
            return view(mapper.attempt(current.tenantId(), id));
        });
    }

    /** 查询永不返回granted=true，也不刷新旧attempt的lease或发送资格。 */
    public UsageDtos.Attempt get(UsagePrincipal principal, String id) {
        validate(principal, id); return view(read(principal, id));
    }

    /** 进程丢失本机attemptId后按稳定业务键找回事实，不制造新的发送许可。 */
    public UsageDtos.LookupResult lookup(UsagePrincipal principal, UsageDtos.Lookup command) {
        UsageValues.principal(principal); UsageValues.lookup(command);
        require(properties.central(), UsageError.DISABLED);
        return transaction(() -> {
            String actor = contexts.active(principal, command.contextType(), command.contextId(), command.epoch(),
                    command.purpose(), command.kind(), command.profileId(), command.stepKey());
            var operation = mapper.operationByKey(principal.caller().tenantId(), command.contextType(), command.contextId(), command.stepKey());
            if (operation == null) { return new UsageDtos.LookupResult(UsageProtocol.SCHEMA, null, null); }
            require(operation.actorId().equals(actor) && operation.purpose().equals(command.purpose())
                    && operation.kind().equals(command.kind()) && operation.profileId().equals(command.profileId())
                    && operation.inputDigest().equals(command.inputDigest()), UsageError.CONFLICT);
            var latest = found(mapper.latestAttempt(principal.caller().tenantId(), operation.operationId()), UsageError.CONFLICT);
            source(principal, latest);
            return new UsageDtos.LookupResult(UsageProtocol.SCHEMA, operation.operationId(), view(latest, operation));
        });
    }

    /** 本人历史审计不返回资料正文或其他组织资金池余额；SQL始终约束tenant与actor。 */
    public UsageDtos.Page list(com.lrj.agentic.security.Caller owner, String type, String contextId, String cursor, int limit) {
        UsageValues.identity(owner.tenantId()); UsageValues.identity(owner.userId());
        var contextType = UsageTypes.parse(UsageTypes.ContextType.values(), type, UsageError.PROFILE_INVALID);
        UsageValues.key(contextId, 128); UsageValues.input(limit >= 1 && limit <= UsageProtocol.MAX_PAGE);
        if (contextType == UsageTypes.ContextType.TASK) { UsageValues.id(contextId); }
        String scope = values.hash(List.of(owner.tenantId(), owner.userId(), type, contextId));
        Cursor before = null;
        if (cursor != null) {
            UsageValues.input(!cursor.isEmpty() && cursor.length() <= 1024);
            try {
                byte[] raw = Base64.getUrlDecoder().decode(cursor);
                UsageValues.input(Base64.getUrlEncoder().withoutPadding().encodeToString(raw).equals(cursor));
                before = values.read(new String(raw, StandardCharsets.UTF_8), Cursor.class, UsageError.CONFLICT);
                UsageValues.input(before != null && scope.equals(before.scope()) && before.createdAt() != null);
                UsageValues.id(before.attemptId());
            } catch (IllegalArgumentException rejected) { throw new UsageFailure(UsageError.CONFLICT); }
        }
        require(mapper.ownsContext(owner.tenantId(), owner.userId(), type, contextId) == 1
                || contexts.ownedTask(owner, type, contextId), UsageError.NOT_FOUND);
        var rows = mapper.listOwn(owner.tenantId(), owner.userId(), type, contextId,
                before == null ? null : before.createdAt(), before == null ? null : before.attemptId(), limit + 1);
        var selected = rows.stream().limit(limit).toList();
        String next = null;
        if (rows.size() > limit) {
            var last = selected.getLast();
            next = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    values.write(new Cursor(scope, last.createdAt(), last.attemptId())).getBytes(StandardCharsets.UTF_8));
        }
        // 每页批量查operation，避免一条attempt追加一次查询。
        var operations = selected.isEmpty() ? java.util.Map.<String, UsageRows.Operation>of()
                : mapper.operations(owner.tenantId(), selected.stream().map(UsageRows.Attempt::operationId).distinct().toList())
                    .stream().collect(Collectors.toMap(UsageRows.Operation::operationId, row -> row));
        return new UsageDtos.Page(UsageProtocol.SCHEMA,
                selected.stream().map(row -> view(row, found(operations.get(row.operationId()), UsageError.CONFLICT))).toList(), next);
    }
    /** 即使知道attempt标识也必须匹配可信tenant/actor；不存在与越权统一404。 */
    public UsageDtos.AuditPage audit(com.lrj.agentic.security.Caller owner, String id) {
        UsageValues.identity(owner.tenantId()); UsageValues.identity(owner.userId()); UsageValues.id(id);
        var attempt = found(mapper.attempt(owner.tenantId(), id), UsageError.NOT_FOUND);
        var operation = found(mapper.operation(owner.tenantId(), attempt.operationId()), UsageError.NOT_FOUND);
        require(owner.userId().equals(operation.actorId()), UsageError.NOT_FOUND);
        var events = mapper.ownAudit(owner.tenantId(), owner.userId(), id);
        require(events.size() <= 5, UsageError.CONFLICT);
        return new UsageDtos.AuditPage(UsageProtocol.SCHEMA, List.copyOf(events));
    }
    private record Cursor(String scope, Instant createdAt, String attemptId) { }

    private UsageRows.Attempt read(UsagePrincipal principal, String id) {
        var row = found(mapper.attempt(principal.caller().tenantId(), id), UsageError.NOT_FOUND); source(principal, row); return row;
    }
    private UsageRows.Attempt locked(UsagePrincipal principal, String id) {
        var row = found(mapper.lockAttempt(principal.caller().tenantId(), id), UsageError.NOT_FOUND); source(principal, row); return row;
    }
    private static void source(UsagePrincipal principal, UsageRows.Attempt row) {
        require(row.tenantId().equals(principal.caller().tenantId()) && row.sourceRef().equals(principal.sourceRef()), UsageError.NOT_FOUND);
    }
    private UsageRows.Operation operation(UsageRows.Attempt row) {
        return found(mapper.operation(row.tenantId(), row.operationId()), UsageError.CONFLICT);
    }
    private UsageDtos.Attempt view(UsageRows.Attempt row) {
        return view(row, operation(row));
    }
    private UsageDtos.Attempt view(UsageRows.Attempt row, UsageRows.Operation operation) {
        var usage = row.usageJson() == null ? null : values.read(row.usageJson(), UsageDtos.Tokens.class, UsageError.CONFLICT);
        return new UsageDtos.Attempt(UsageProtocol.SCHEMA, row.attemptId(), row.operationId(), operation.contextType(),
                operation.contextId(), operation.stepKey(), operation.purpose(), operation.kind(), operation.profileId(), row.priceVersion(),
                UsageMoney.CURRENCY, row.state(), UsageMoney.text(row.reservedAmount()), UsageMoney.text(row.settledAmount()), usage,
                usage == null ? UsageProtocol.NONE : UsageProtocol.PROVIDER_REPORTED, row.outcome(), row.retryOf(), row.overrun(), row.createdAt(), row.updatedAt());
    }
    private static UsageRows.Attempt copy(UsageRows.Attempt row, UsageState state, BigDecimal settled, String usage,
            String outcome, String providerId, String reportHash, String unknownHash, boolean overrun) {
        return new UsageRows.Attempt(row.attemptId(), row.operationId(), row.accountId(), row.tenantId(), row.workerId(),
                row.sourceRef(), row.leaseEpoch(), row.requestSha256(), row.priceVersion(), state.code(), row.reservedAmount(),
                settled, usage, outcome, providerId, reportHash, unknownHash, row.retryOf(), overrun, row.version(), row.createdAt(), row.updatedAt());
    }
    private void audit(UsagePrincipal principal, UsageRows.Attempt row, Event event, String previous,
            BigDecimal held, BigDecimal booked) {
        changed(mapper.audit(new UsageRows.Audit(uuid(), row.accountId(), row.attemptId(), row.attemptId() + ":" + event.code(),
                event.code(), previous, row.state(), held, booked, principal.caller().userId(), principal.traceId(), null)));
    }
    private static void validate(UsagePrincipal principal, String id) { UsageValues.principal(principal); UsageValues.id(id); }
    private static String uuid() { return UUID.randomUUID().toString(); }
    private static void changed(int rows) { require(rows == 1, UsageError.CONFLICT); }
    private static <T> T found(T value, UsageError error) { require(value != null, error); return value; }
    private <T> T transaction(Supplier<T> action) {
        // 这些事务没有网络副作用；只重试真实锁冲突/唯一键竞争，不能在提交结果未知时自动重试dispatch。
        for (int count = 0; count < MAX_TRANSACTION_ATTEMPTS; count++) {
            try { return Objects.requireNonNull(tx.execute(ignored -> action.get())); }
            catch (PessimisticLockingFailureException | DuplicateKeyException collision) {
                if (count + 1 == MAX_TRANSACTION_ATTEMPTS) { throw new UsageFailure(UsageError.CONFLICT); }
            }
        }
        throw new UsageFailure(UsageError.CONFLICT);
    }
}

package com.lrj.agentic.task.usage.application;

import com.lrj.agentic.task.usage.contract.UsageAdminDtos.*;
import com.lrj.agentic.task.usage.domain.*;
import com.lrj.agentic.task.usage.domain.UsageTypes.UsageAccountState;
import com.lrj.agentic.task.usage.domain.UsageTypes.Event;
import com.lrj.agentic.task.usage.infrastructure.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.lrj.agentic.task.usage.application.UsageValues.*;
import static com.lrj.agentic.task.usage.contract.UsageAdminDtos.*;
import static com.lrj.agentic.task.usage.domain.UsageFailure.require;

/** 本地运维用例：授权上限不可改，价格不可改；所有账目、绑定和审计在同一短事务内提交。 */
@Service
public final class UsageAdminService {
    private enum Action implements UsageTypes.Code {
        BOOTSTRAP("BOOTSTRAP"), CONFIGURE("CONFIGURE"), IMPORT("IMPORT");
        private final String code;
        Action(String code) { this.code = code; }
        public String code() { return code; }
    }
    private static final String PERIOD = "LIFETIME";
    private final UsageMapper mapper;
    private final UsageValues values;
    private final UsageAdminFiles files;
    private final TransactionTemplate transaction;
    public UsageAdminService(UsageMapper mapper, UsageValues values, UsageAdminFiles files, PlatformTransactionManager manager) {
        this.mapper = mapper; this.values = values; this.files = files;
        transaction = new TransactionTemplate(manager); transaction.setTimeout(5);
    }

    /** operator由本机CLI提供；本方法不暴露给Controller，文件IO与校验在锁账户之前完成。 */
    public Result execute(Manifest command, String operator) {
        Action action = validate(command); identity(operator);
        FrozenSource frozen = files.frozen(command);
        String digest = values.hash(List.of(command.schemaVersion(), command.commandId(), command.action(),
                command.accountId(), command.sponsorRef(), command.fundingSourceRef(), command.kind(), command.limitAmount(),
                Objects.toString(command.state(), ""), Objects.toString(command.expectedVersion(), ""),
                command.profiles(), command.bindings(), sourceIdentity(command)));
        try { return transaction.execute(status -> apply(command, action, frozen, operator, digest)); }
        catch (DuplicateKeyException e) { throw new UsageFailure(UsageError.CONFLICT); }
    }
    private Result apply(Manifest c, Action action, FrozenSource frozen, String operator, String digest) {
        UsageRows.Account account = mapper.lockAccount(c.accountId());
        String commandKey = "admin:" + c.commandId();
        if (account != null) {
            immutable(account, c);
            var previous = mapper.auditByCommand(c.accountId(), commandKey);
            if (previous != null) {
                require(digest.equals(previous.commandSha256()), UsageError.CONFLICT);
                return result(c, account, true);
            }
        }
        if (action == Action.BOOTSTRAP) {
            require(account == null, UsageError.CONFLICT);
            changed(mapper.insertAccount(new UsageRows.Account(c.accountId(), c.sponsorRef(), c.fundingSourceRef(), c.kind(),
                    PERIOD, UsageMoney.CURRENCY, new BigDecimal(c.limitAmount()), UsageMoney.ZERO, UsageMoney.ZERO,
                    c.state(), 0, operator, operator, null, null)));
            account = mapper.lockAccount(c.accountId());
        } else { require(account != null, UsageError.NOT_FOUND); }

        // 同源改命令ID仍不能重复入账；同命令异参则在更早的命令摘要检查中拒绝。
        String importDigest = values.hash(List.of(c.accountId(), c.fundingSourceRef(), c.kind(), c.limitAmount(), sourceIdentity(c)));
        if (c.source() != null) {
            var receipt = mapper.imported(c.source().sourceLedgerId());
            if (receipt != null) {
                require(action == Action.IMPORT && importDigest.equals(receipt.manifestSha256()), UsageError.CONFLICT);
                audit(c, commandKey, digest, account.state(), account.state(), UsageMoney.ZERO, operator, Event.IMPORTED);
                return result(c, account, true);
            }
        }
        require(action == Action.BOOTSTRAP || c.expectedVersion() == account.version(), UsageError.CONFLICT);
        configure(c, operator);
        BigDecimal delta = c.source() == null ? UsageMoney.ZERO : new BigDecimal(c.source().reservedAmount());
        BigDecimal held = account.heldAmount().add(delta);
        require(held.compareTo(UsageMoney.MAX) <= 0, UsageError.CONFLICT);
        String next = action == Action.IMPORT ? account.state() : c.state();
        if (held.add(account.bookedAmount()).compareTo(account.limitAmount()) > 0) { next = UsageAccountState.FROZEN.code(); }
        if (frozen != null) {
            Source source = c.source();
            changed(mapper.insertImport(new UsageRows.ImportReceipt(source.sourceLedgerId(), source.sourceSha256(), importDigest,
                    c.accountId(), c.kind(), account.limitAmount(), delta, source.entriesCount(), source.freezeProofRef(), operator, null)));
            for (Entry entry : frozen.entries()) {
                changed(mapper.insertImportEntry(new UsageRows.ImportEntry(entry.id(), source.sourceLedgerId(), entry.amount())));
            }
        }
        changed(mapper.accountChange(c.accountId(), account.version(), held, account.bookedAmount(), next, operator));
        Event event = action == Action.BOOTSTRAP ? Event.INITIALIZED : action == Action.CONFIGURE ? Event.CONFIGURED : Event.IMPORTED;
        audit(c, commandKey, digest, action == Action.BOOTSTRAP ? null : account.state(), next, delta, operator, event);
        return result(c, mapper.account(c.accountId()), false);
    }
    private void configure(Manifest c, String operator) {
        for (Profile profile : c.profiles()) {
            var existing = mapper.price(profile.priceVersion());
            if (existing == null) {
                changed(mapper.insertPrice(new UsageRows.Price(profile.priceVersion(), profile.profileId(), c.kind(),
                        values.write(profile.pricing()), operator, null)));
            } else {
                require(existing.profileId().equals(profile.profileId()) && existing.kind().equals(c.kind())
                        && values.read(existing.pricingJson(), UsagePrice.class, UsageError.PROFILE_INVALID).equals(profile.pricing()), UsageError.CONFLICT);
            }
        }
        for (Binding binding : c.bindings()) {
            var price = mapper.price(binding.priceVersion());
            require(price != null && price.kind().equals(c.kind()) && price.profileId().equals(binding.profileId()), UsageError.PROFILE_INVALID);
            var existing = mapper.lockBinding(binding.tenantId(), c.kind(), binding.profileId());
            var row = new UsageRows.Binding(binding.tenantId(), c.kind(), binding.profileId(), c.accountId(),
                    binding.priceVersion(), binding.enabled(), operator, operator, null, null);
            if (existing == null) { changed(mapper.insertBinding(row)); }
            else {
                require(existing.accountId().equals(c.accountId()), UsageError.CONFLICT);
                changed(mapper.configureBinding(row));
            }
        }
    }
    private static Action validate(Manifest c) {
        input(c != null && SCHEMA.equals(c.schemaVersion())); id(c.commandId()); id(c.accountId());
        key(c.sponsorRef(), 128); key(c.fundingSourceRef(), 128); UsageKind kind = UsageKind.parse(c.kind());
        require(UsageMoney.parse(c.limitAmount(), UsageError.PROFILE_INVALID).signum() > 0, UsageError.PROFILE_INVALID);
        Action action = UsageTypes.parse(Action.values(), c.action(), UsageError.PROFILE_INVALID);
        input(action == Action.BOOTSTRAP ? c.expectedVersion() == null : c.expectedVersion() != null && c.expectedVersion() >= 0);
        if (action == Action.IMPORT) { input(c.state() == null && c.source() != null); }
        else { UsageTypes.parse(UsageAccountState.values(), c.state(), UsageError.PROFILE_INVALID); }
        input(action != Action.CONFIGURE || c.source() == null);
        input(c.profiles() != null && c.profiles().size() <= 8 && c.bindings() != null && c.bindings().size() <= 32);
        input(action != Action.IMPORT || c.profiles().isEmpty() && c.bindings().isEmpty());
        Set<String> profiles = new HashSet<>(), bindings = new HashSet<>();
        for (Profile p : c.profiles()) {
            input(p != null && p.pricing() != null); key(p.profileId(), 64); key(p.priceVersion(), 64);
            input(profiles.add(p.priceVersion())); p.pricing().validate(kind);
        }
        for (Binding b : c.bindings()) {
            input(b != null && b.enabled() != null); identity(b.tenantId()); key(b.profileId(), 64); key(b.priceVersion(), 64);
            input(bindings.add(b.tenantId() + "\u0000" + b.profileId()));
        }
        if (c.source() != null) {
            Source s = c.source(); key(s.sourceLedgerId(), 128); digest(s.sourceSha256()); key(s.freezeProofRef(), 128);
            input(s.path() != null && s.proofPath() != null && !s.path().equals(s.proofPath())
                    && s.entriesCount() != null && s.entriesCount() >= 0 && s.entriesCount() <= MAX_ENTRIES);
            input(UsageMoney.parse(s.reservedAmount(), UsageError.PROFILE_INVALID).compareTo(new BigDecimal(c.limitAmount())) <= 0);
        }
        return action;
    }
    private static Object sourceIdentity(Manifest c) {
        Source s = c.source();
        // 物理文件移动不改变资金身份，命令和收据都只绑定原始摘要与冻结证明。
        return s == null ? List.of() : List.of(s.sourceLedgerId(), s.sourceSha256(), s.reservedAmount(), s.entriesCount(), s.freezeProofRef());
    }
    private static void immutable(UsageRows.Account row, Manifest c) {
        require(row.sponsorRef().equals(c.sponsorRef()) && row.fundingSourceRef().equals(c.fundingSourceRef())
                && row.kind().equals(c.kind()) && row.limitAmount().compareTo(new BigDecimal(c.limitAmount())) == 0, UsageError.CONFLICT);
    }
    private void audit(Manifest c, String key, String digest, String previous, String next, BigDecimal delta, String actor, Event event) {
        changed(mapper.audit(new UsageRows.Audit(UUID.randomUUID().toString(), c.accountId(), null, key, event.code(),
                previous, next, delta, UsageMoney.ZERO, actor, null, digest)));
    }
    private static Result result(Manifest c, UsageRows.Account account, boolean replay) {
        return new Result(SCHEMA, c.commandId(), c.accountId(), replay, account.version(), account.state(),
                UsageMoney.text(account.limitAmount()), UsageMoney.text(account.heldAmount()), UsageMoney.text(account.bookedAmount()));
    }
    private static void changed(int rows) { require(rows == 1, UsageError.CONFLICT); }
}

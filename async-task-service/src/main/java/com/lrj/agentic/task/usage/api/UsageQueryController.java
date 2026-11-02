package com.lrj.agentic.task.usage.api;

import com.lrj.agentic.contract.ModelUsageProtocol;
import com.lrj.agentic.security.JwtTrust;
import com.lrj.agentic.task.usage.application.UsageService;
import com.lrj.agentic.task.usage.contract.UsageDtos;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** 浏览器只有本人的用量查询入口，不开放账户、费率、导入或结算写操作。 */
@RestController
public final class UsageQueryController {
    private final UsageService usage;
    public UsageQueryController(UsageService usage) { this.usage = usage; }
    /** 金额为预留及内部折算成本，服务端不会返回运营资金池全局余额。 */
    @GetMapping(ModelUsageProtocol.PUBLIC_PATH)
    public ResponseEntity<UsageDtos.Page> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam String contextType, @RequestParam String contextId,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(usage.list(JwtTrust.caller(jwt), contextType, contextId, cursor, limit));
    }
}

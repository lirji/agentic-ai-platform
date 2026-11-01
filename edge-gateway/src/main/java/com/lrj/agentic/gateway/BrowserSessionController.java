package com.lrj.agentic.gateway;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;

/** 只投影守卫已经复核的应用身份，不向浏览器输出IdP Token。 */
@RestController
@ConditionalOnProperty(name = "gateway.browser.enabled", havingValue = "true")
public class BrowserSessionController {
    @GetMapping("/api/v1/session")
    public ResponseEntity<WorkspaceIdentity> session(ServerWebExchange exchange) {
        WorkspaceIdentity identity = exchange.getAttribute(WorkspaceIdentity.ATTRIBUTE);
        if (identity == null) throw new BrowserIdentityFailure(com.lrj.agentic.contract.PlatformError.AUTH_REQUIRED);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(identity);
    }
}

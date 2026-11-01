package com.lrj.agentic.gateway;

import com.lrj.agentic.contract.PlatformError;

/** 身份端口仅传播稳定错误分类，不携带IdP响应或凭据。 */
public final class BrowserIdentityFailure extends RuntimeException {
    private final PlatformError error;
    public BrowserIdentityFailure(PlatformError error) { super(error.code()); this.error = error; }
    public PlatformError error() { return error; }
}

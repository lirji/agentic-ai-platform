package com.lrj.agentic.knowledge.domain;

import com.lrj.agentic.security.Caller;

/** 原文存储端口，调用方传入已验证的有限内容，不能指定任意外部对象地址。 */
public interface SourceStore {
    /** 写入所属项目和身份范围的内容寻址对象，不在数据库事务中等待远程存储。 */
    SourceReference put(Caller caller, String hash, String contentType, byte[] bytes);
    /** 检查当前凭据能否访问所属桶，作为受理服务 readiness 的真实依赖。 */
    boolean available();
}

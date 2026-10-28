package com.lrj.agentic.knowledge.domain.retrieval;

import java.util.List;

/** 授权检索的固定协议限额；调整算法边界须发布新策略，不能静默扩大资源。 */
public final class RetrievalProtocol {
    public static final String SCHEMA = "knowledge-retrieval-v1";
    public static final String INTERNAL_BASE = "/internal/v2/knowledge/retrieval";
    public static final String CANDIDATES_ROUTE = "/candidates";
    public static final String RESOLVE_ROUTE = "/resolve";
    public static final int MAX_REQUEST_BYTES = 128 * 1024;
    public static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    public static final int MAX_TARGET = 100;
    public static final int MAX_RESOLVE = 50;
    public static final int MAX_CANDIDATE_MILLIS = 12_000;
    public static final int MAX_RESOLVE_MILLIS = 5_000;
    public static final List<Integer> CANDIDATE_LIMITS = List.of(20, 60, MAX_TARGET);
    private RetrievalProtocol() { }
}

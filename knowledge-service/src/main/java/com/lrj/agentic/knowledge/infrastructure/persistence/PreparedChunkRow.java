package com.lrj.agentic.knowledge.infrastructure.persistence;

/** 接受事务的规范块行；结构来源由领域转换产生，不在 XML 中解释模型 JSON。 */
public record PreparedChunkRow(String chunkId, int ordinal, String text, String textHash,
        Integer startByte, Integer endByte, String preparationSchema, String sourceLocator,
        String sourceJson, String sourceDigest, String extractionDigest) { }

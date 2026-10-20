package com.lrj.agentic.knowledge.domain;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 原文完整性与内容寻址使用同一摘要算法，与语义 Embedding 无关。 */
public final class ContentDigest {
    private ContentDigest() { }

    /** JDK 必备算法，失败属于运行环境错误，不允许退化为弱摘要。 */
    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK 缺少必需的 SHA-256 算法");
        }
    }
}

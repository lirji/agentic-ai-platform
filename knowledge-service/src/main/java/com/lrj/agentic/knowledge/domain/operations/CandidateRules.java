package com.lrj.agentic.knowledge.domain.operations;

import static com.lrj.agentic.knowledge.domain.operations.CandidateModels.*;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** 候选协议的有界输入规则，字面检查不承担业务质量判断。 */
public final class CandidateRules {
    private CandidateRules() { }
    /** UUID按规范字面编码，不能通过大小写或别名改变幂等作用域。 */
    public static void uuid(String value) {
        try { require(value!=null && UUID.fromString(value).toString().equals(value)); }
        catch(IllegalArgumentException invalid) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
    }
    /** 摘要固定SHA256十六进制，拒绝空值与宽松转换。 */
    public static void digest(String value) { require(value!=null && value.matches("[0-9a-f]{64}")); }
    /** 修订上限与既有资料生命周期保持一致。 */
    public static void revision(Long value) { require(value!=null && value>0 && value<Integer.MAX_VALUE); }
    /** 保存要求保留原始短句，禁止静默trim使实际检查偏离用户输入。 */
    public static List<String> required(List<String> values) {
        require(values!=null && values.size()<=MAX_REQUIRED_TEXT);
        var unique=new HashSet<String>();
        for(String text:values) {
            require(text!=null && text.codePointCount(0,text.length())<=MAX_REQUIRED_POINTS
                    && text.codePoints().anyMatch(c->!Character.isWhitespace(c)&&!Character.isSpaceChar(c)&&c!=0x85)
                    && text.indexOf('\0')<0 && StandardCharsets.UTF_8.newEncoder().canEncode(text) && unique.add(text));
        }
        return List.copyOf(values);
    }
    /** 输入错误统一沿Knowledge错误目录。 */
    public static void require(boolean valid) { if(!valid) throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
}

package com.lrj.agentic.knowledge.domain.sharing;

import com.lrj.agentic.security.Caller;
import java.util.List;
import java.util.Optional;

/** 只读现有身份设施，Knowledge不成为用户目录或角色权威。 */
public interface SharingMembers {
    /** 每次读取当前账号，不缓存旧ALLOW；不存在/停用返回空，故障抛稳定错误。 */
    Optional<SharingModels.Member> current(Caller member);
    /** 返回本应用当前准入标识，有界集合来自现有身份登记。 */
    List<String> admitted(String tenantId);
}

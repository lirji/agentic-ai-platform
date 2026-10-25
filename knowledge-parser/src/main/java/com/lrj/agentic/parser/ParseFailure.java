package com.lrj.agentic.parser;

import com.lrj.agentic.contract.knowledge.ExtractionProtocol.Exit;
import java.io.IOException;

/** 两种格式共用固定退出原因，不保留可能包含原文或内部路径的异常链。 */
class ParseFailure extends IOException {
    private final Exit exit;
    ParseFailure(Exit exit) { this.exit = exit; }
    /** 父进程只按稳定机器码判定，不解析库错误文案。 */
    public Exit exit() { return exit; }
}

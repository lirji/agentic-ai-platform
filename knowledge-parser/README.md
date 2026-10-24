# Knowledge 解析进程

一次调用解析一个原生 PDF，Knowledge 服务是唯一调用者。使用 PDFBox 3.0.8；Spring Boot Maven 插件仅打包独立 main，不启动 Web 或 Spring 上下文。正式输入/输出、摘要、拒绝规则和资源限制见 [P03/P04](../docs/design/knowledge-first-20261020/STRUCTURED_PROCESSING_CONTRACTS.md)。

`mvn -pl knowledge-parser -am package` 产生 `target/knowledge-parser-0.1.0-SNAPSHOT.jar`。父进程使用固定 Java/JAR 路径和参数数组，将实际原字节写入 stdin；stdout 只包含一个提取 JSON。日志桥只输出 stderr，失败仅用稳定退出码反馈。服务凭据不会传给解析器。

S9B 基础批次中默认解析及 PDF 上传均关闭，持久流程/上传运行配置尚待接入。JVM 128MiB 堆、32MiB 直接内存、96MiB 元空间并非完整 OS/RSS 沙箱。20 秒、单槽、输出上限、中断及子孙回收由 Knowledge 父进程守住；未知存活进程不释放槽位。退出后只清理由本次执行创建的临时目录，不删除业务对象和历史数据。

验证结果与未完成范围见 [S9B记录](../docs/qa/pdf-ingestion-20261024/TEST_RESULT.md)。

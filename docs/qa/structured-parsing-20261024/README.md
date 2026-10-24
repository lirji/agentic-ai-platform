# S9A 解析候选证据

本目录只有设计取证、自己生成的格式夹具和隔离候选探针；不是平台生产解析代码。结论见 [TEST_RESULT](TEST_RESULT.md)，候选/制品/一次资源观察见 [research-evidence.json](research-evidence.json)，固定样本身份见 [fixture-manifest.json](fixture-manifest.json)。不要为让新实现通过而重生成固定样本。

`probes/java/pom.xml`只供JDK21的独立Maven探针，未加入根reactor。固定PDFBox3.0.8、POI6.0.0；编译后用其classpath执行ParserProbe，参数为样本**副本**所在目录。默认只读输入并写提取观察，第二参数`generate`仅用于生成器溯源，正常复核不传。

Python探针需要Python3.12、python-docx1.2.0、pypdf6.21.2；本次因PyPI TLS失败，pypdf使用官方GitHub提交`37761bd80d76e52b1470b93a5d36f9c51765205c`源码，另建venv离线安装DOCX/lxml依赖。通过PYTHONPATH指定该固定源码后，执行`probes/python_probe.py <样本副本目录>`；先运行Java探针产生对照文本，Python才逐页/逐段比较。只比较PDF去末尾空白后的可读文本，不误称两库原始输出字节相同。

本次可恢复路径：`.local/s9a-probe`、`.local/s9a-python`、`.local/s9a-research`；最终复核副本路径由`.local/s9a-replay-state.json`记录。日志为`.logs/s9a-*`，不复制第三方完整源码/官网页面或本机依赖进此目录。所有文件是合成资料；加密PDF生成器中的密码仅为测试内容，不是账号凭据。

首轮生成器漏掉DOCX必要的tblGrid，Python严格读取拒绝；修正表格网格并重新固定样本后双方通过，原失败日志保留。Java独立探针有Log4j API无provider告警；产品S9B/C需要按现有日志规范明确stderr绑定，不能沿用该未绑定状态作为生产实现。

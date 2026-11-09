# S9F · 运行与复验

- 新环境配置：已有`knowledge.parsing.enabled=true`及固定java/jar路径后，设置`KNOWLEDGE_SPREADSHEET_UPLOAD_ENABLED=true`。默认false，只关新受理，不撤销历史读取。
- Docker使用既有`deploy/docker/runtime.py prepare`的后端/前端、身份、预算、检索及其他参数，追加`--parsing --spreadsheets`；如需OCR还须沿S9E说明追加`--ocr`，既有PDF/DOCX开关不隐含变化。prepare不等于build/up，当前未执行应用部署。
- 先升级所有Java/Python/Web读端及V21，再启用新上传。旧版本不认识SHEET_CELL及新profile，回退先关闭受理并保留新读能力，禁止直接切旧应用继续读取新数据。
- 容量继承父进程20秒、Java128MiB堆/32MiB直接内存/96MiB元空间；无需新增解析服务。全部详细上限见[契约](../../design/knowledge-first-20261020/SPREADSHEET_CONTRACTS.md)。格式失败不部分发布、不回退猜格式。
- 真实集成复验：先确认隔离test资源和端点可用，再执行`python3 docs/qa/spreadsheet-sources-20261109/run_integration.py`。只用共享测试向量，无模型费用；使用原test数据库和任务隔离搜索实例28333/29300，不重启原搜索容器。
- Linux探针见`probe_linux.py`及`linux-result.json`。脚本若发现同名容器即停止以便先核对旧状态，不删除或覆盖旧结果；两次探针均已成功退出。

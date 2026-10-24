# S8F 实现证据

基线后端`9a0e0b4`、前端`5960531`，两仓原目录任务分支`feat/ci-contracts-20261024`。本片仅工作流、CI脚本、契约测试/金标、相关文档和3份旧前端QA JSON格式修正。

| 入口 | 作用 |
| --- | --- |
| 两仓 `.github/workflows/ci.yml` | 后端java/python/documents与前端web；只读权限、完整Action SHA、有界超时、无数据库或模型凭据 |
| `ci/check_contract_docs.py` | 登记契约版本标记与共享金标可读性，不替代语义测试 |
| `knowledge-service/src/test/resources/evaluation-wire-golden.json` | E02输入/profile/open/结果/凭据/状态共享线格式；固定测试夹具，不是业务演示数据 |
| `EvaluationWireContractTest.java` | 现有Java DTO严格反序列化、逐字段回写比较和领域校验 |
| `ai-runtime/tests/test_evaluation_wire_contract.py` | 同一JSON与实际Python Pydantic模型、真实召回指标函数核对 |
| 原P01共享金标与测试 | 原样继续由默认Java/Python测试运行，未复制第二份 |

代码审阅：未读取或打印私密配置；checkout不保留凭据；PR内容不拼入shell；没有写权限或自动部署。字段漂移负向实验通过独立文件注入，正式金标未改。业务源码、POM、uv.lock、pnpm-lock.yaml均不变。

本片由当前Agent实现后另行执行验证和审阅，没有独立Agent或用户签核。Git交付和远程Gate绑定完整提交，在验收与交付记录中保存。

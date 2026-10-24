# S8F 最小持续集成验收

2026-10-24；当前 **本地PASS / 远程检查待执行**。本地验证与远程状态分别记录，不以本地通过替代Actions结果。契约为 [ci-v1](../../design/knowledge-first-20261020/CI_CONTRACTS.md)。

| 检查 | 当前结果与证据 |
| --- | --- |
| Java 默认回归 | `mvn -B -ntp test` 71项通过；无数据库IT。`.logs/s8f-java-all.log` |
| Python 干净锁定安装 | 独立环境 `uv sync --locked` 成功，未改变运行中venv。`.logs/s8f-python-install.log` |
| Python 静态与默认测试 | Ruff格式/lint、mypy通过；296 passed / 49 skipped，跳过需显式DB/模型条件的集成项。`.logs/s8f-python-all.log` |
| E02跨语言 | Java3项、Python14项消费同一报文并使用实际DTO/校验器；字段改名使两端按预期失败。见 [负向实验](negative-control.json) |
| 前端冻结安装与检查 | 原锁文件干净安装、格式/lint/类型通过；临时快照13文件59项测试、Vite生产构建通过。`.logs/s8f-web-checks-final.log`、`.logs/s8f-web-temp-install.log`、`.logs/s8f-web-temp-tests.log` |
| 契约文档 | 4份登记契约/2份共享报文检查通过；仅此登记范围，不声称全部历史协议或链接均覆盖 |
| 远程GitHub Actions | 两仓enabled=true；待推送具体提交并检查所有必需job，当前UNVERIFIED |

首轮Java新测试存在方法名/泛型断言编译问题，已修正并通过完整回归。前端3份旧QA JSON不符合既有Prettier规则，只调整格式，解析值核对不变；旧失败日志保留。第一次前端快照位于被Vite禁止访问的`.local`，测试初始化被拒绝，现改用系统临时目录，未修改产品安全规则。

不含UI行为改动、业务迁移、依赖升级、模型调用或生产部署。默认CI跳过的49项不能称为集成通过；无新增性能或容量结论。原前端共享块约627KB警告保留。完整51项计划尚未完成。

# S8F 最小持续集成验收

2026-10-24；**必需验收PASS / S8F DONE**。本地与真实远程Actions均通过，后端258149e、前端83c0bc6已正常快进推送main。契约为 [ci-v1](../../design/knowledge-first-20261020/CI_CONTRACTS.md)，完整提交与检查见 [CI Gate](ci-gate.json) 和 [交付记录](delivery-result.json)。

| 检查 | 当前结果与证据 |
| --- | --- |
| Java 默认回归 | 本地 `mvn -B -ntp test` 71项通过；远程Linux `mvn -B -ntp verify` 通过；无数据库IT。`.logs/s8f-java-all.log`、`.logs/s8f-backend-actions.log` |
| Python 干净锁定安装 | 独立环境 `uv sync --locked` 成功，未改变运行中venv。`.logs/s8f-python-install.log` |
| Python 静态与默认测试 | Ruff格式/lint、mypy通过；296 passed / 49 skipped，跳过需显式DB/模型条件的集成项。`.logs/s8f-python-all.log` |
| E02跨语言 | Java3项、Python14项消费同一报文并使用实际DTO/校验器；字段改名使两端按预期失败。见 [负向实验](negative-control.json) |
| 前端冻结安装与检查 | 原锁文件干净安装、格式/lint/类型通过；临时快照13文件59项测试、Vite生产构建通过。`.logs/s8f-web-checks-final.log`、`.logs/s8f-web-temp-install.log`、`.logs/s8f-web-temp-tests.log` |
| 契约文档 | 4份登记契约/2份共享报文检查通过；仅此登记范围，不声称全部历史协议或链接均覆盖 |
| 远程GitHub Actions | 后端258149e的java/python/documents、前端83c0bc6的web全部success；完整SHA、job链接/完成时间见CI Gate。没有用分支当前状态代替提交身份 |

首轮Java新测试存在方法名/泛型断言编译问题，已修正并通过完整回归。前端3份旧QA JSON不符合既有Prettier规则，只调整格式，解析值核对不变；旧失败日志保留。第一次前端快照位于被Vite禁止访问的`.local`，测试初始化被拒绝，现改用系统临时目录，未修改产品安全规则。

不含UI行为改动、业务迁移、依赖升级、模型调用或生产部署。默认CI跳过的49项不能称为集成通过；无新增性能或容量结论。原前端共享块约627KB警告保留。完整51项计划尚未完成。

文档检查覆盖10份变更Markdown和188个本地文件链接；Action SHA/只读权限、触发器及有界超时检查通过。当前Agent实现后执行验证/审阅，无独立Agent或用户签核。没有配置仓库级分支保护或自动合并；本次按用户持续授权、真实必需检查后正常Git交付。

Handoff：`implementation-validation / S8F / COMPLETED / PASS`；`ci-cd-gate / ci-v1 / COMPLETED / PASS`。后续S9A冻结结构化解析与准备包v2，原模型预算/数据继续保留。

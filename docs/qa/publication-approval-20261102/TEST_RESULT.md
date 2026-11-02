# S14C 审核与真实入库发布验收

本地验收 PASS，两仓产品已正常合并推main，精确分支/main CI全部PASS，见[交付收据](delivery-result.json)。完整51项计划未完成；本片之后继续S14D。

| 验收 | 结果与证据 |
| --- | --- |
| P01 原子批准/并发/固定内容 | 核心PASS：实际MySQL并发同键唯一、不同键冲突、原完整S3源、迟到审计故障全事务回滚、评分变更锁内拒绝；候选内容锁外观察后编辑、原资料变化及远程质量故障真实IT补充PASS（s14c-stale-approval-it.log）。 |
| P02 真实发布/失败恢复 | PASS：原UPDATE真实worker→双索引→精确发布元组，失败后原job CAS重试；原ACL逐字段保持。新受理不冒充新发布，真实替代SUPERSEDED、删除INACTIVE，历史和原回执保留。 |
| P03 质量/权限/运行边界 | 核心PASS：真实S8报告/人工评分，MECHANISM或文字要求不足不得批准；owner/tenant大小写及HTTP只读scope/额外JSON拒绝，no-store；default-off依赖和V17真实20字段/表注释通过。质量依赖故障补充PASS，未产生批准、作业或回执。 |
| P04 页面/真实联合/工程 | 前端215项/39文件PASS，格式/类型/ESLint/生产构建PASS。实际Tasks/Knowledge/React r2联合PASS，覆盖批准丢响应原键恢复、失败/重试/发布、编辑后的历史隔离和陌生人404。最终联合PASS（s14c-joint-browser-visual-final.log，夹具s14c-joint-6331607576901284829）；确认弹层、Esc关闭/焦点返回及6状态截图已检查，1440/414px无横向溢出。 |
| 相关回归 | PASS：131项Java单元（Knowledge121+Security10）；KnowledgeCandidateIT/IsolationIT/LifecycleIT/EvaluationDecisionIT/SharingIT共52项、48执行通过/4条件跳过。对应联合按专用入口单独运行。 |

日志：`.logs/s14c-approval-core-it-r5.log`、`s14c-final-regression.log`、`s14c-stale-approval-it.log`、`s14c-joint-browser-r2.log`、`s14c-joint-browser-visual-final.log`及`.logs/s14c-joint-6331607576901284829/{joint,browser}.log`。前端`.logs/s14c-full-unit.log`、`s14c-format-final.log`、`s14c-lint-final.log`、`s14c-types-final.log`、`s14c-build-preview.log`。

失败与修正：早期测试构造参数/Assessment类型冲突、记录类重载导致Spring配置绑定失败、测试runner异常处理以及重试退避等待已修复并复验。首轮联合缩窄视口时读取响应式过渡中间态，改为等待真实布局稳定后检查无横向溢出；确认弹层截图增加可见等待。没有删除失败日志或降低业务断言。

卫生：前端IMPLEMENTATION_COMPLETE；Java IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS（仓库未配置Java formatter/独立静态分析器），人工审查见[REVIEW](REVIEW.md)。两处旧Python Runtime共4条Ruff提示与基线一致、无新增；新helper通过。既有Vite native-config提示和大chunk警告保留，未借本片升级工具链。

边界：V17仅test应用且已不可改写；保留隔离测试记录/源/索引、日志、凭据账本和旧main工作树。预览28236；旧预览与28086原Docker未动。真实持久层/任务/浏览器配合确定性处理器与质量报告验证机制，没有把它当真实模型质量提升证明；付费调用0，生产部署0。

视觉记录：紫色主动作和灰色次动作清晰，确认弹层520px宽、说明/原资料入口/批准文字与底部动作完整；窄屏卡片换行，无横向页面溢出。已查看受理中、失败、原任务重试、发布成功和历史修订的同版页面，最终补拍可见确认弹层；各状态没有新增产品变化。历史页不提供批准动作，报告仍保留当时依据，最新内容变更需要重新审批。

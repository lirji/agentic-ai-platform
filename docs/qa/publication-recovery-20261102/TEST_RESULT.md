# S14D 发布后探测与补偿验收

本地验收 PASS；两仓产品 Git/远程 CI 待交付。完整51项计划未完成，本片之后继续S15A。

| 验收 | 结果与证据 |
| --- | --- |
| R01 精确发布后检索 | PASS：当前完整发布元组命中、MISSED、旧证据/撤下竞态拒绝、依赖错误不伪成功。正式Python/LlamaIndex、真实双索引和授权回调通过浏览器联合；固定topK=1，不自动调用模型或轮询。 |
| R02 安全补偿 | PASS：真实MySQL/S3、跳过失败中间版本、同键并发唯一、不同键冲突、迟到审计失败全回滚；新稿不继承评估批准、不改当前发布。PDF/DOCX完整历史源字节和类型保留，不用提取正文替代附件。 |
| R03 权限与恢复 | PASS：tenant/owner/大小写隔离、HTTP read/write、disabled原键恢复、新编辑/原资料变化拒绝覆盖。真实UI丢响应原键核对完成、新草稿与原批准来源跳转、原资料明确撤下；撤下后探测409、陌生人历史404。 |
| R04 Runtime与迁移 | PASS：[运行校验](runtime-validation.json)，V18已在test应用、11字段和表注释完整，默认关闭且显式依赖operations，独立于新批准开关。[Ruff基线](runtime-lint-baseline.json)无新增。 |
| R05 前端与工程 | PASS：220项/40文件、类型/ESLint/Prettier/生产构建；后端132单元（Knowledge122+Security10）。5类影响回归59项、54执行通过/5条件跳过；D实际联合另行执行并通过。无控制台页面异常。 |
| R06 视觉与交互 | PASS：当前产品版本1440/414px的命中、真实错误、确认弹层、新稿、历史、撤下六状态均已查看，无横向溢出。Esc关闭/焦点返回、原键核对最终反馈、历史往返和撤下操作有真实E2E证据。 |

日志：`.logs/s14d-core-it-r2.log`（4核心IT与131个此前单元）；新增共享指纹后`.logs/s14d-joint-browser-r2.log`的132单元均PASS。最终影响回归`.logs/s14d-final-regression.log`，最终联合`.logs/s14d-joint-browser-final.log`和`.logs/s14d-joint-13543167556431421493/{joint,browser}.log`。前端`.logs/s14d-full-unit.log`、`s14d-types-complete.log`、`s14d-lint-complete.log`、`s14d-format-complete.log`、`s14d-build.log`及最终E2E类型/静态/格式补查日志。共享中文/emoji意图指纹见[intent-golden](intent-golden.json)。

失败与修正：初轮topK=5触发既有污染候选不足规则，探测改为只需一个精确命中的topK=1，未放宽原检索契约。前端ResizeObserver测试隔离已修复。联合夹具并发容量探测受既有单出站连接串行影响，改为真实回调连接失败；v2字段名和历史只读链接定位错误已按实际契约修正。r4联合通过后发现草稿图拍在原键核对loading中间态，增加等待最终受理反馈断言；最终联合再次PASS且最终草稿图已查看。失败日志均保留，产品逻辑未为测试降低标准。

视觉：沿既有紫色主动作、灰色次动作与白色卡片；探测输入/结果/检查时间、补偿来源/原因、历史记录分组清楚。窄屏弹层说明、来源和双动作完整，主操作明确只生成草稿。历史只读态保留原资料链接，撤下后来源审计仍可读。r4六状态及最终同版草稿截图由本Agent实际查看；最终新增等待只影响测试时点，产品代码未变。

卫生：前端IMPLEMENTATION_COMPLETE；Java IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS（无规范formatter/独立静态分析工具），人工[审查](REVIEW.md)及4条建议处理已记录；旧Runtime Ruff提示与基线相同。既有Vite native-config和大chunk警告保留，不借本片换工具链。

边界：V18仅test应用且不可改写；保留随机测试记录/源/索引/待处理作业、日志、凭据账本和旧main工作树。28246为恢复预览，旧预览和28086原Docker未改。确定性向量/处理/质量端口验证真实协议与授权机制，不作为语义召回率或模型质量证明；付费调用0，生产部署0。

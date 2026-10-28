# S10E 实施证据

最终状态：本地实现与验收PASS，详见[TEST_RESULT](TEST_RESULT.md)；以下先保留2026-10-27有界检查点的历史状态。

## 2026-10-27 原后端检查点

状态：**IN_PROGRESS；后端有界验证PASS，整片验收UNVERIFIED**。本片尚未提交/推送。完整计划仍在原IMPLEMENTATION_SLICES，恢复以PROGRESS_STATE为准。

已补齐生命周期契约第8节；新增本人范围的精确摘要查重GET、固定五字段导航结果与null无匹配；Mapper按tenant/owner、ACTIVE最新hash及同内容任务关联查一行。K01错误/幂等形状不变。V11用可空生成列和唯一索引只约束ACTIVE最新内容；没有删除行、历史、原键或对象。

原后端检查点时独立test库已从V10迁移V11；随后独立Docker部署已将dev迁移V11，原记录的dev V10仅为历史事实。真实MySQL/S3的新6项、原受理7项及生命周期17项相关集成用例均通过。首轮生命周期一条新断言把String code与enum对象比较而失败，已改为DELETED.code并单独复验；未降低业务断言。HTTP检查200五字段、null、401/403、非法/缺失hash400，未调用付费模型。迁移前1,151条资料的ID/tenant/owner/hash/version/status/lifecycle逐行保持不变。

证据和源码摘要：[backend-checkpoint.json](backend-checkpoint.json)。完整Java/Python/前端回归、页面接入、真实Gateway/浏览器以及dev迁移尚未执行；不能沿用S10D PASS冒充S10E完成。日志为后端.logs/s10e-backend-it.log与s10e-backend-recheck.log。测试库已有V11，恢复时不重置/回退迁移。

下一步：沿前端现有UploadModal/api/upload-intent接入409后的精确查询与资料/任务导航；已确认202/确定未受理409可清对应意图，未知/5xx保留原键，容量满拒绝新增而不逐出未知记录；local-identity只放行新GET及contentHash参数。再补UI/已发布true/失败新稿差异、并发更新争用、网关隔离与三视口验收，停止旧writer后完成本地dev迁移，最后验收/Git交付。

保留原schema、对象、索引、账本和历史样本。无新worktree，无生产部署或清理；S10D代码/收据已在main且最终CI均PASS。

## 2026-10-28 恢复后的实现与验证

沿已批准S10E继续，后端10个原检查点hash核对一致。前端复用UploadModal、现有类型客户端、Ant Design及sessionStorage指纹格式；新GET只消费五字段导航结果。409后查重仅在确认匹配时释放当前意图，查空/查询失败/断线/5xx保留原输入和原键；202释放已确认单笔。容量30笔拒绝新意图，旧指纹可继续恢复，不逐出未知记录。

增加匹配内容发布状态区分、真实资料/处理任务详情入口；本地身份桥只放行GET单个合法contentHash，不接收身份参数。额外后端集成测试覆盖发布true、保留旧发布的失败新稿false、同hash更新与新上传的数据库竞争。后端143单元与123真实组件集成均通过（`.logs/s10e-full-java.log`），Python产品源码未变，不重复付费探针。

前端99项全量测试通过（前端`.logs/s10e-unit-final.log`），类型/ESLint/构建通过。首次全量默认并发导致既有组件超时，后续Docker构建也出现资源争用，已将test.maxWorkers最终限制为1，保留断言和超时；新组件测试为jsdom关闭不可执行的CSS动画，保留可见性断言。契约浏览器4组PASS（前端`.logs/s10e-browser-final.log`）：1440/1024/414已发布/未发布提示与真实路由，以及断线→刷新→原键恢复→查空保留→确认释放。首次夹具缺本地session与processing-metadata，已明确补齐测试响应，未改生产逻辑。截图位于前端`docs/qa/document-dedup-20261027/screenshots`；已实际查看414未发布、1024未发布、1440已发布，内容/操作未截断，无视口溢出。

当前执行者按implementation-validation完成独立验收阶段，不声称由另一Agent评审。真实网关/运行UI在最终快照启动后通过，详见TEST_RESULT。Docker重用既有部署入口，Compose生成bake定义仅授权读取现有部署目录；Java构建输入hash一致时复用已通过verify的JAR。构建/运行证据以最终TEST_RESULT为准，未进行生产部署或数据清理。

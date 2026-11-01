# C-SHARING：同组织指定成员只读共享

2026-11-02，sharing-v1。用户已明确确认此矩阵，沿S13B/C逐片实施；默认关闭，完整撤权验收前不开放长期环境。

## 权限与数据所有权

- 可信tenant+稳定sub决定身份。owner仅对本人逐份资料授权本应用、本组织当前已准入且未停用的reader；reader可读当前已发布版本、来源与引用，可用于自己的检索/问答/研究/评测，不能修改、删除、重建、转授或看到owner的任务/历史草稿/共享名单。未授权、跨租户、停用对象统一不存在；目录故障明确503。
- 授权绑定documentId，跟随后续当前发布版本；不共享未发布草稿。撤下/删除后共享读取立即拒绝；重新授权不能复活历史失效版本。旧资料不自动共享，不创建团队、编辑者或管理员。
- Knowledge拥有ACL事实及命令回执。V13追加document.sharing_revision、knowledge_document_share与knowledge_sharing_command；表/字段有注释，tenant/doc/reader唯一，tenant/owner/key唯一。分享不更改owner，所有现有写谓词保持tenant+owner。
- Casdoor仍为成员状态权威。Knowledge只读适配当前应用组织及同一准入名单，按稳定userId读取当前账号，校验组织/id/停用/删除；不存密码、角色或第二用户目录，不写IAM。目录清单仅当前准入成员，分页10、上限100；对外仅userId/displayName。私密运行配置由同一S13A登记派生，启动/预检核对名单一致。

## S13B公开接口

沿现有内部JWT、Gateway会话CSRF和统一错误格式，所有响应no-store；以下位于/api/v1/knowledge/sharing。

| 方法/路径 | 请求 | 响应/权限 |
| --- | --- | --- |
| GET /capabilities | 无 | {enabled}，默认false |
| GET /members | limit(1..10，默认10)、cursor(稳定sub，可空) | {items:[{userId,displayName}],nextCursor}，仅同组织当前准入caller；排除本人 |
| GET /documents/{id} | 无 | {documentId,revision,allowedActions:["GRANT_READ","REVOKE_READ"],readers:[{userId}]}，owner；名单最多100 |
| POST /documents/{id}/commands | Idempotency-Key规范UUID；{operation:"GRANT_READ"或"REVOKE_READ",recipientId:UUID,expectedRevision:0..2147483646} | 200回执{commandId,documentId,recipientId,operation,acceptedRevision,acceptedAt}；owner |
| GET /commands/{key} | 原UUID键 | 原owner的不可变回执；删除/撤下后仍可核对 |
| GET /received | limit(1..100，默认20)、cursor(文档UUID，可空) | {items:[SharedDocument],nextCursor}，只列reader当前有效已发布资料 |
| GET /received/{id} | 无 | SharedDocument，无权404 |

SharedDocument={documentId,displayName,version,indexGeneration,contentType,sizeBytes,allowedActions:["READ","SEARCH"]}，仅当前发布修订的字段，不暴露ownerId、草稿/作业或存储地址。既有owner管理列表/详情/API不扩大可见性；共享资料在独立列表/详情呈现，只有服务端允许动作才显示共享操作。

命令受理先读本人资源，授权前验证当前recipient；撤权即使recipient已停用或IdP故障仍可执行。短READ_COMMITTED事务按document锁→回执重放→共享修订CAS→ACL/审计回执写入顺序原子提交。并发同修订只有一个成功；同键同指纹回原回执且不重放状态，同键不同请求409；未知提交先查询原key，再按同key重试。重复GRANT/REVOKE使用新key也产生一次新修订/审计，不能把旧GRANT重放为撤权后的新授权。事务中没有远程调用。

复用KNOWLEDGE_INPUT_INVALID/NOT_FOUND/IDEMPOTENCY_CONFLICT/DATABASE_UNAVAILABLE/BUSY，新增KNOWLEDGE_SHARING_DISABLED(503)、SHARING_MEMBER_UNAVAILABLE(503)、SHARING_REVISION_CONFLICT(409)、SHARING_LIMIT_EXCEEDED(409)；不存在/不准入的recipient统一404，禁止回显IAM响应。

## S13C读取与撤权

增加GET /api/v1/knowledge/readable-documents（limit 1..100默认20、cursor原文档UUID）及/{id}，分别返回Page/单个{documentId,displayName,publishedVersion,status}。本人资料取自己的当前修订摘要，共享资料仅当前发布摘要，status=READY来自数据库发布状态。统一SQL授权后再游标分页，不由前端拼接分页或伪造owner对象；关共享时此消费入口仍返回本人资料。原v1/v2管理详情及受理任务继续owner-only。问答、研究、检索和评测选择器改用本摘要，无权限/撤权详情404。

- 索引原始过滤为tenant AND (本人owner OR当前授权documentId)，只取候选身份，不从索引返回正文。共享范围有界，超过1000时拒绝全范围查询，要求显式资料范围；数据库当前ACL及发布三元组再次过滤。
- K04/K06、候选、resolve、正文、评测标注/报告和Tasks历史结果复用发布权威SQL；共享read分支同时要求当前准入caller，owner分支不受共享开关影响。ACL及成员状态不缓存ALLOW；worker的历史身份不能绕过当前状态。
- 读取以其最后一次权威核对的观察时点为准。撤权事务提交后启动的检查拒绝；已通过检查的响应/已外发请求无法追回，不声称跨服务原子撤回。模型外发前、结果交付、恢复再调用前重验；撤权后阻止后续输出和新调用，历史任务/事件/评测内容隐藏。
- 已经向用户交付或保存到用户设备的文本不可远程收回；共享页面定期重新读取权限，401/404/身份变化清空缓存/正文；未知写入查询而非自动再发。

## 验收和发布顺序

S13B：真实MySQL同键/异键/并发修订、租户/owner/reader/停用隔离、IdP故障、失响应恢复、只读列表和UI真后端行为；成员目录真实Casdoor只读核对。S13C：真实发布资料与双索引、候选后/外发前/生成中/恢复前撤权、历史Task/SSE/引用/评测拒绝，旧owner路径回归；生产构建浏览器桌面/窄屏截图复核。模型仅协议替身，不调用新计费模型，不修改旧账本。

S13B先完成默认关闭的授权管理闭环；S13C完成检索消费/恢复及全链路验收后才能在明确本地验证配置开放。正常Git交付不等于更新当前Docker环境；部署另外遵循用户授权。

适配依据：[Casdoor API认证](https://casdoor.ai/docs/basic/public-api/)和[官方SDK稳定ID查询实现](https://github.com/casdoor/casdoor-go-sdk/blob/master/casdoorsdk/user.go)；本机实际能力另做只读验证，不由文档推定。

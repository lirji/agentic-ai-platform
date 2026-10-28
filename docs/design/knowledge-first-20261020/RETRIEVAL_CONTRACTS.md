# 授权检索与重排契约

协议 `knowledge-retrieval-v1`；S11A，2026-10-28。本文冻结后续S11B–E行为，不表示产品已实现。沿[C-RETRIEVAL/C-RERANK](CAPABILITY_CONTRACT_BACKLOG.md)和现有生命周期/来源契约，Java仍为资料与发布权威，Python仍由外层LangGraph管理任务。

## 1. 不变量与版本

- RT-INV-01：索引只是候选投影；正文离开Knowledge前必须按可信tenant/owner、ACTIVE、当前发布version/generation/job/batch核验。索引携带的名称/正文/身份不能覆盖数据库权威。
- RT-INV-02：候选、重排外发、最终结果与后续引用各有现时权限检查。校验不是永久授权租约；撤权后已发送的数据不能收回，后续外发/提交/读取必须停止。
- RT-INV-03：一份查询每次执行最多一次Embedding和一次Reranker调用，无自动网络重试。重启恢复可能再次计费，沿先行累计预算保守预留，不能宣称模型exactly-once。
- RT-INV-04：两路原始一基sourceRank保持空位，RRF为`sum(1/(60+sourceRank))`，同分按chunkId升序。身份至少包含documentId/version/indexGeneration/chunkId/batchDigest，不能按正文或node.hash合并。
- RT-INV-05：候选原语不调用Python或模型，不融合、不重排。Python融合一次；旧Java入口只服务旧策略，不先融合后再融合。
- RT-INV-06：依赖失败、失效引用、真实空结果、重排失败/降级是不同结果。授权/索引失败不允许借重排降级返回缓存正文。

策略是不可变目录项，不能由模型参数创建：`JAVA_RRF_LEGACY`对应现有K04/K05；`LLAMA_RRF_V1`保持旧融合语义；`LLAMA_RERANK_V1`使用有界较大候选池与一个已验证模型。实际启用及任务字段接入由S11E完成，旧任务缺失策略时固定解释为JAVA_RRF_LEGACY，不能随全局默认漂移。模型、参数、候选上限或失败策略改变必须新策略版本，不能改写旧评测快照。

## 2. 身份、拓扑与兼容

新增两个只读POST仅供内部运行时：`/internal/v2/knowledge/retrieval/candidates`和`/internal/v2/knowledge/retrieval/resolve`。复用独立runtime-knowledge签名密钥、issuer `urn:agentic-ai:runtime-knowledge`、唯一audience `agentic-ai-knowledge`、azp `ai-runtime-worker`、最长60秒令牌、X-Internal-Token及knowledge:read；精确允许这两个方法/路径，其余deny。原v1内部/外部路径与响应保持兼容。普通用户JWT、edge签名、错误azp/issuer/audience/过期或重复身份头均不能调用；Gateway不发布/internal路径。

tenant/owner来自验签后tenant_id/sub。请求JSON禁止身份、任意URL、SQL、索引名、模型端点和权限参数；无权文档和不存在文档不得通过计数/ID区分。Python客户端身份从已验证的任务/请求上下文构造，不由LLM工具输入覆盖。初期仅本人空间；共享权限仍归S13，不能把本契约当共享授权已实现。

S11C先提供可组合检索用例。S11E交互搜索走现有Java API边界→Python检索用例→Java候选/解析原语；问答/研究/评测直接复用同一Python用例。Java公共门面不持有候选原语使用的信号量等待回调；候选端点不反调Embedding/RAG，避免递归与父子资源互等。新公共响应/诊断字段及策略落盘由S11E在本文约束下冻结，旧K04不偷偷改语义。

## 3. 授权候选原语

请求恰为`{schemaVersion, query, documentIds, embeddingModel, embeddingDimensions, vector, candidateTarget, timeoutMs}`。schemaVersion固定本协议；query沿现行2000 Unicode码点、非空白、无NUL、可编码UTF-8规则；documentIds为null或1–20个不重复规范UUID（null代表本人全部可见资料，空数组400）。模型固定`text-embedding-v4`、维度1024，vector恰1024个有限数，拒绝NaN/Inf/布尔/字符串/空值及零向量，不归一化或补齐错误输入。candidateTarget为1–100整数；timeoutMs为1–12000整数。JSON上限128KiB，重复键/未知字段/不合法类型拒绝；请求正文和向量不记录日志。

返回`{schemaVersion, candidateLimit, vectorCandidates, lexicalCandidates}`。两路分别最多100项，每项恰为下表字段；空列表仅用于成功查询无合法命中，不能从依赖异常构造。

| 字段 | 语义 |
| --- | --- |
| documentId / version | 权威资料UUID与内容修订，version沿旧接口命名 |
| indexGeneration / jobId / processorVersion | 当前已发布处理代次、任务和处理profile，不取最新失败新稿 |
| chunkId / batchDigest | 64位小写SHA256；索引候选与数据库发布批次必须匹配 |
| text / textHash | 权威正文及其UTF-8 SHA256；不使用索引正文，最多512 Unicode码点 |
| source | 现有`{displayName,locator}`，名称取已发布内容修订，定位沿TXT/PDF/DOCX来源协议 |
| sourceRank / sourceScore | 该路原始一基位置与实际有限索引分数；过滤后保留名次空位，两路原始分数不可互相比大小 |

每路同一(chunkId,batchDigest)重复或非法索引返回形状属于INDEX_UNAVAILABLE，不累计重复分数。同一稳定身份出现在两路时权威正文/来源必须一致；不同资料即使正文相同也不合并。candidateLimit只能是20/60/100，sourceRank在1..candidateLimit；所有数据在返回前按当前发布权威核验，不输出失效候选ID或数量。

Java按20→60→100依次取两路，整个过程复用传入向量，不调用Embedding。每轮从该轮原始完整列表开始，不能跨轮重复累加。满足合法唯一候选数≥candidateTarget即返回；合法不足且没有剔除无效候选，同时两路均不足本轮limit时可返回较少结果；100上限且没有剔除也可返回较少结果。“剔除”按原始(chunkId,batchDigest)唯一候选是否未获授权计算，跨源同一合法身份不算剔除。有剔除且到100仍不足目标返回INDEX_UNAVAILABLE。其余情况有界扩展。两路任何超时/故障均失败，不用单路伪装双路成功。

LLAMA_RRF_V1的candidateTarget=topK（1–20），上述规则与旧Java停止条件等价：若没有剔除且唯一候选少于topK≤20，两路原始列表必然均少于20。LLAMA_RERANK_V1的candidateTarget=50；Java只返回授权候选，Python融合后至多保留50项进入精排，不能先裁成最终5项。候选响应总JSON上限1MiB，超过上限明确失败，不能截断正文/半个列表；此限额是工程保护，不代表已测容量SLO。

## 4. 现时批量解析原语

请求恰为`{schemaVersion, documentIds, candidates, timeoutMs}`；documentIds语义同上，timeoutMs 1–5000整数，正文128KiB。candidates为1–50个不重复身份项，每项包含`documentId,version,indexGeneration,jobId,processorVersion,chunkId,batchDigest,textHash`，不接受调用者正文或标题。

返回`{schemaVersion,hits}`，顺序与输入一致，字段与第3节权威项相同但不含sourceRank/sourceScore。全部身份必须与当前发布记录、请求文档范围和textHash一致；任一缺失/失效/无权整体404 KNOWLEDGE_NOT_FOUND，不悄悄换成新稿、删掉项目或返回部分成功。权威存储故障503，原来成功的缓存不能绕过本次失败。成功后Python还要核对正文/来源与冻结候选一致；漂移拒绝，不把不匹配字段交给模型。

解析由一次有界权威SQL读取，按caller隔离并核对完整元组，不依赖索引。S11B可增加专用读取投影，沿既有Mapper XML/索引，不增加写表或迁移。旧单条引用API仍用于历史结果点击和现有消费者。

## 5. LlamaIndex融合与模型后处理

Python使用锁定core0.14.25的BaseRetriever、QueryBundle、TextNode、NodeWithScore、BaseNodePostprocessor扩展点；显式空CallbackManager，原生实现异步后处理而不落入默认to_thread同步桥接，不访问全局Settings，不默认解析LLM，不生成额外查询。当前QueryFusionRetriever默认num_queries=4、零基RRF及node.hash身份均与本文不同，不能直接默认构造。

一次用例：校验策略/身份/预算→生成一次查询向量→候选原语→按原始sourceRank融合→策略选择→现时批量解析→可选一次重排→最终选取topK→再次解析→输出。RRF路径省略重排前那次外发核对，只在最终输出前解析；没有模型时不虚构rerankScore。候选为空时不调用重排，明确EMPTY；原问答NO_EVIDENCE等消费者语义继续沿各自契约。

RRF保持双精度运算的固定VECTOR、KEYWORD（对应lexicalCandidates）累加顺序、分母60+一基rank，排序`(-rrfScore,chunkId)`，不得把过滤后的列表重新enumerate计分。TextNode.id_由稳定身份确定，正文相同不合并；元数据保存JSON原语，LangGraph检查点不保存框架节点/模型/HTTP客户端。NodeWithScore当前分数与rrfScore、rerankScore分别保存，不以精排值覆盖原融合证据。

Reranker Port输入为query和冻结顺序的至多50个权威正文，UTF-8 JSON最多256KiB；每条仍是原512码点块，禁止静默截断。原始id/tenant/owner/存储地址无需外发；候选序号与身份映射仅在本地保存。请求返回全部候选分数以稳定处理同分，再按`(-rerankScore,-rrfScore,chunkId)`取topK；提供方返回原文不作为权威。索引须为不重复、范围合法的整数，结果应覆盖提交列表，分数必须有限，其他范围由已核验模型协议约束。缺项、重复/越界、非法数、错误模型/响应形状均为协议失败，不作部分成功。

LLAMA_RERANK_V1默认失败策略FAIL：未启用/预算不足/超时/协议错误均明确失败。只有另一个冻结策略显式允许RRF_ONLY时才可返回原RRF顺序，同时mode=RRF_ONLY、degraded=true及安全reason；不能把同一个策略的默认配置暗中改为可降级。授权失败、候选/最终解析失败、取消和总deadline耗尽永不降级。

## 6. 预算、时限与错误

新检索用例硬上限30秒，且受调用方根任务剩余期限约束；每阶段都使用同一个单调时钟剩余预算。Embedding最多10秒、候选12秒、Reranker5秒、每次解析5秒，均再夹紧到总剩余；这些是上限，不是可相加或续期的独立预算。Java候选每次索引调用还受既有index-seconds限制；共最多3轮/6次索引请求。根问答45秒、work120秒不因新检索自动扩大。

Python检索最多2个在途、无无界队列；Java候选沿最多2个在途、立即BUSY，不新增线程池/中间件。公共门面与回调原语资源不能循环占用。客户端断开/取消停止后续步骤，但不能把已发送模型请求宣称未计费。HTTP响应有界读取；连接/响应超时不得默认重试。

Embedding继续原累计账本；Reranker须独立账本、价格日期/地域/模型与持久累计上限，发送前先保守预留，未知结果保留，不退款后盲重试。S11A不增加额度。U04未满足时只做协议替身和异常校验，真实模型与逐题效果标记UNVERIFIED，不挪用聊天/Embedding额度。

Java原语复用既有统一错误目录：401/403身份或scope；400 INPUT_INVALID；413 FILE_TOO_LARGE用于有界请求体；429 BUSY；404 NOT_FOUND用于解析失效；503 INDEX_UNAVAILABLE用于索引/发布可用性失败。数据库读取异常沿既有DATABASE_UNAVAILABLE映射，不能伪装空结果。Python用例在其模块错误目录统一区分输入、身份、容量、deadline、知识不可用、证据失效、模型配置/预算/协议/远端失败；S11C/D增加稳定code并在接入公共接口时映射，不散落错误文案或覆盖旧K04的code。

## 7. 诊断、评测与迁移

内部可信诊断可记录sourceRank/sourceScore、融合/精排名次与耗时，禁止正文、查询原文、向量、凭据及被过滤候选。面向本人结果只展示最终合法来源的两路贡献、RRF/重排分数、连续展示名次、策略版本、mode、降级原因与各阶段耗时；不暴露原始sourceRank空位、未授权ID/数量、内部端点。时间/费用是本次已观察值，预算预留不是提供方账单。

S11E必须使搜索、问答、研究和评测调用同一个策略实现；受理时策略ID进入不可变任务/评测配置和请求指纹，恢复不读新的全局默认。旧请求/任务/基线保留，切换与回退以版本路由完成，无双写或重算历史；不能用“RRF_ONLY”记录宣称完成真实精排消融。

上线顺序为S11B原语→S11C固定输入等价→S11D协议/条件允许的真实模型→S11E消费者接入。原公开检索保持可用直至新路径验收；不修改旧迁移/清理对象。具体公共响应字段、预算实例配置和任务接入变更在所属实现片验证，不由本片预建占位DDL。

## 8. 证据与未决

固定代码与框架证据见[取证](../../qa/retrieval-contracts-20261028/source-evidence.json)，验收见[矩阵](RETRIEVAL_ACCEPTANCE.md)。官方重排文档目前给出qwen3-rerank示例、独立/reranks接口与地域业务空间端点；这不证明当前账号开通、旧端点兼容或模型效果。本片保留已批准的优先候选，S11D再按账号核实型号/端点/价格/独立额度。[百炼官方文档](https://help.aliyun.com/zh/model-studio/rerank)

U01代表语料/标注、U04真实Reranker条件、U03共享身份、U06规模SLO仍未决。它们不阻断S11A契约或S11B/C隔离机制实现；不据此宣布真实重排质量、生产容量或共享授权完成。

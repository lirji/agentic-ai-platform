# 指定成员只读共享运行说明

C-SHARING见[共享契约](SHARING_CONTRACTS.md)，当前实现/验收状态以[进度](PROGRESS_STATE.md)为准。共享默认关闭；本片未更新正在运行的开发Docker应用或生产环境。

## 配置与身份

Knowledge读取`KNOWLEDGE_SHARING_ENABLED`（默认false）、`ISSUER`、`ORGANIZATION`、`TENANT_ID`、`CLIENT_ID`、`CLIENT_SECRET`、`MEMBERS`（后六项同样带`KNOWLEDGE_SHARING_`前缀）。Secret只进入受控环境，不放前端或提交库。成员使用稳定UUID，不使用名称/邮箱；最多100个准入成员，每份最多100个reader。名单来自同一次OIDC专属组织/客户端登记，不能把已有个人资料批量共享。

`deploy/sharing_local.py`从现有正式OIDC配置派生这些值，默认关闭。`deploy/identity_runtime.py start --sharing`用于原隔离test数据库的正式登录验证入口；按原工具先核对`status`及端口，不能与已有28196进程重复启动。`deploy/docker/runtime.py prepare ... --identity-mode oidc --sharing --committed-only`在下一次获授权本地部署时将共享开关和同一准入名单写入新不可变快照；新增选项进入fingerprint。不允许local身份开启共享。prepare不等于build/up；本片没有执行部署或创建IAM账户。

Casdoor每次按固定组织、稳定userId读取`/api/get-user`，使用现有专属应用凭据。三路独立HTTP容量，每次3秒/64KiB，不缓存ALLOW；目录分页最多10名，最多11次当前成员查询。搜索/正文读取仍以MySQL当前ACL与发布代次为最终依据。IdP故障时共享读取拒绝；所有者对完整本人资料的读取及撤权不依赖成员目录。

## 数据库与回滚

Knowledge追加V13：`knowledge_document.sharing_revision`、`knowledge_document_share`和不可变`knowledge_sharing_command`，含表/字段注释、唯一键和reader索引。本片仅隔离test库应用；不能改写已应用迁移或清空回执重测。

关闭共享后停止新的共享读/写，原owner管理与读取保持；原命令回执GET仍可查询。保留ACL/审计/已交付任务，关闭开关不等于撤销已交付文本；也不删除持久PG快照。优先以配置关闭功能再验证当前引用返回；旧版本回滚必须保留V13、核查Flyway兼容，不能做数据库降级或直接覆盖现有数据。

## 验收与边界

真实DB/索引、HTTP、Tasks、PG与浏览器证据见[验收](../../qa/sharing-20261102/TEST_RESULT.md)。测试模型只有确定性端口，本片付费调用0。正常客户端无权获得IAM对象、凭据、资料草稿或他人任务；浏览器验证夹具的签名仅在测试Node侧。

ACL提交之后的新权威读取必须拒绝。跨成员目录、SQL、索引和模型调用没有分布式原子快照；每个外发/恢复/最终交付边界重验，已送达模型或用户的文本无法追回。界面列表/共享详情按5秒轮询更新；既有打开页面无推送撤文承诺。默认不启用额外缓存、MQ、ReBAC服务、组织运营者或写共享。

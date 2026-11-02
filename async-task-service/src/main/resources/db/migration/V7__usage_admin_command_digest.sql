-- V6已应用；追加兼容字段，旧运行时审计保留NULL，受控管理命令必须写摘要。
ALTER TABLE usage_audit ADD COLUMN command_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
    COMMENT '受控CLI规范命令摘要，同命令键异参拒绝；旧运行时事件为空';

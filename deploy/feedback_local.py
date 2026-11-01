"""反馈默认关闭；启用任务关联时复用已登记的Tasks引用签名身份。"""
from collections.abc import Mapping


def environment(source: Mapping[str, str], enabled: bool = False) -> dict[str, str]:
    """只检查已有配置是否齐备，不生成密钥、不创建账号、不扩大模型预算。"""
    if enabled and (
        source.get('KNOWLEDGE_CITATION_READER_ENABLED') != 'true'
        or source.get('TASK_KNOWLEDGE_READER_ENABLED') != 'true'
        or any(not source.get(key) for key in (
            'KNOWLEDGE_CITATION_READER_PUBLIC_KEY', 'TASK_KNOWLEDGE_READER_PUBLIC_KEY',
            'TASK_KNOWLEDGE_READER_PRIVATE_KEY',
        ))
    ):
        raise ValueError('反馈关联任务需要已配置的Tasks知识引用签名身份')
    value = 'true' if enabled else 'false'
    return {'KNOWLEDGE_FEEDBACK_ENABLED': value, 'TASK_FEEDBACK_ENABLED': value}

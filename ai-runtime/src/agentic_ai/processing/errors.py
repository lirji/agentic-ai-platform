"""处理接口复用稳定错误语义，不携带原文、令牌或第三方异常。"""

from enum import StrEnum
from types import MappingProxyType


class ProcessingCode(StrEnum):
    """显式协议 code 不依赖展示文案或成员顺序。"""

    AUTH_REQUIRED = "AUTH_REQUIRED"
    AUTH_FORBIDDEN = "AUTH_FORBIDDEN"
    INPUT_INVALID = "PROCESSING_INPUT_INVALID"
    INPUT_TOO_LARGE = "PROCESSING_INPUT_TOO_LARGE"
    CAPACITY_EXCEEDED = "PROCESSING_CAPACITY_EXCEEDED"
    DEADLINE_EXCEEDED = "PROCESSING_DEADLINE_EXCEEDED"
    RESULT_INVALID = "PROCESSING_RESULT_INVALID"
    CONFIGURATION_INVALID = "PROCESSING_CONFIGURATION_INVALID"
    INTERNAL = "INTERNAL_ERROR"


ERRORS = MappingProxyType(
    {
        ProcessingCode.AUTH_REQUIRED: (401, "请提供有效的身份凭证。"),
        ProcessingCode.AUTH_FORBIDDEN: (403, "当前身份没有执行此操作的权限。"),
        ProcessingCode.INPUT_INVALID: (400, "知识处理输入无效，请核对原文与版本。"),
        ProcessingCode.INPUT_TOO_LARGE: (413, "知识处理输入超过当前允许范围。"),
        ProcessingCode.CAPACITY_EXCEEDED: (429, "知识处理繁忙，请稍后重试。"),
        ProcessingCode.DEADLINE_EXCEEDED: (503, "知识处理超时，请稍后重试。"),
        ProcessingCode.RESULT_INVALID: (502, "知识处理结果不符合约定。"),
        ProcessingCode.CONFIGURATION_INVALID: (503, "知识处理配置无效，请检查运行配置。"),
        ProcessingCode.INTERNAL: (500, "服务处理失败，请稍后重试。"),
    }
)


class ProcessingError(Exception):
    """只保存安全字段，错误转换不保留可能含输入的隐式异常上下文。"""

    def __init__(self, code: ProcessingCode) -> None:
        self.code = code
        self.status, self.message = ERRORS[code]
        super().__init__(self.message)

"""第三方异常可能含请求正文；代理日志只保留级别和组件，原始异常不出进程。"""

import logging
import traceback


class MetadataFormatter(logging.Formatter):
    """忽略消息、参数和异常文本，保留可定位到组件的最小运行信号。"""

    def format(self, record: logging.LogRecord) -> str:
        result = f"model-proxy {record.levelname} {record.name}"
        if record.exc_info and record.exc_info[0]:
            result += f" exception={record.exc_info[0].__name__}"
            # 只保留调用位置，避免异常消息和 traceback 源码行包含请求或认证参数。
            frames = traceback.extract_tb(record.exc_info[2])
            if frames:
                result += f" frame={frames[-1].name}:{frames[-1].lineno}"
        return result

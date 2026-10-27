"""只把同机两套既有搜索端口映射到应用 loopback，不接受客户端指定目标。"""

import asyncio
import signal
from contextlib import suppress

TARGETS = (
    (26333, "agentic-ai-search-qdrant-1", 6333),
    (29200, "agentic-ai-search-elasticsearch-1", 9200),
)
MAX_CONNECTIONS = 32
BUFFER_BYTES = 65536
CONNECTION_SECONDS = 30
active = 0


async def relay(reader, writer):
    """分段并等待背压，避免把索引请求全文缓存到内存。"""
    while data := await reader.read(BUFFER_BYTES):
        writer.write(data)
        await writer.drain()


async def forward(reader, writer, host, port):
    """连接/总时长和并发均有上限；异常不输出请求内容或认证头。"""
    global active
    if active >= MAX_CONNECTIONS:
        writer.close()
        return
    active += 1
    upstream = None
    tasks = []
    try:
        async with asyncio.timeout(CONNECTION_SECONDS):
            remote, upstream = await asyncio.wait_for(
                asyncio.open_connection(host, port, limit=BUFFER_BYTES), 3
            )
            tasks = [
                asyncio.create_task(relay(reader, upstream)),
                asyncio.create_task(relay(remote, writer)),
            ]
            await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
    except (OSError, TimeoutError):
        pass
    finally:
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        for connection in (writer, upstream):
            if connection is not None:
                connection.close()
                with suppress(OSError, TimeoutError):
                    await asyncio.wait_for(connection.wait_closed(), 1)
        active -= 1


async def main():
    """只绑定共享容器网络的 loopback；两个转发端口不会发布到宿主或局域网。"""
    stop = asyncio.Event()
    for value in (signal.SIGTERM, signal.SIGINT):
        asyncio.get_running_loop().add_signal_handler(value, stop.set)
    servers = []
    try:
        for local_port, host, port in TARGETS:

            async def accept(reader, writer, host=host, port=port):
                await forward(reader, writer, host, port)

            servers.append(
                await asyncio.start_server(accept, "127.0.0.1", local_port, limit=BUFFER_BYTES)
            )
        print("SEARCH_BRIDGE_READY", flush=True)
        await stop.wait()
    finally:
        for server in servers:
            server.close()
            await server.wait_closed()


if __name__ == "__main__":
    asyncio.run(main())

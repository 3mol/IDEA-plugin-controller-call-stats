"""生产调用统计数据源抽象。

具体实现见 csv_source.py（CSV 读取）。后续如需接入数据库等其它来源，
新增一个继承 DataSource 的子类，并在 server.py 启动时选择实例化即可。
"""

from abc import ABC, abstractmethod


class DataSource(ABC):
    @abstractmethod
    def get(self, sign: str) -> dict | None:
        """按 sign 查询单条 stats；未命中返回 None。"""

    @abstractmethod
    def get_batch(self, signs: list[str]) -> tuple[list[dict], list[str]]:
        """批量查询；返回 (命中的 stats 列表, 未命中的 sign 列表)。

        命中列表保留输入顺序；未命中列表保留输入顺序。
        """

"""澄清会话两条真 bug 的回归锁(DEF 编号沿用内部缺陷登记习惯)。

这两条都不是代码评审能看出来的:必须真的多会话并发跑起来才暴露。
"""
from __future__ import annotations

from pathlib import Path

import pytest

from runtime import conversation
from runtime.config import Config
from runtime.daemon import Daemon, conversation_workdir


def _daemon(tmp_path: Path) -> Daemon:
    cfg = Config(server_ws_url="ws://127.0.0.1:7001/ws/executor",
                 executor_id=1, executor_token="tok",
                 provider="mock", data_dir=str(tmp_path))
    return Daemon(cfg)


# --- DEF-037:澄清会话首回合必失败(workdir 从未 mkdir) --------------------------

def test_conversation_workdir_is_created(tmp_path: Path):
    """目录必须真的被建出来,而不只是算出一个路径。"""
    workdir = conversation_workdir(str(tmp_path), 42)
    assert workdir.is_dir()
    assert workdir == tmp_path / "conversations" / "42"


def test_conversation_workdir_is_idempotent(tmp_path: Path):
    """已存在时再调一次不报错(重连/重投会重复进入)。"""
    first = conversation_workdir(str(tmp_path), 42)
    (first / "marker.txt").write_text("x")
    second = conversation_workdir(str(tmp_path), 42)
    assert second == first
    assert (second / "marker.txt").exists()


def test_settings_file_can_be_written_right_after_workdir_creation(tmp_path: Path):
    """回归本体:拿到 workdir 后立刻落 claude-settings.json,旧代码在这里 FileNotFoundError。"""
    workdir = conversation_workdir(str(tmp_path), 7)
    path = conversation.write_conversation_settings(workdir, {"ANTHROPIC_MODEL": "m"}, "tok")
    assert path and Path(path).is_file()
    assert Path(path).read_text(encoding="utf-8").count("ANTHROPIC_MODEL") == 1


# --- DEF-038:跨会话回合被误判为重复(幂等键只用了 turn_id) ----------------------

def test_same_turn_id_in_different_conversations_is_not_deduplicated(tmp_path: Path):
    """turn_id 是会话内序号:两个会话的 turn_id=1 是两个不同的回合,不能互相当成重投。"""
    d = _daemon(tmp_path)
    submitted = []
    d._conv_pool.submit = lambda fn, *a: submitted.append((fn.__name__, a))  # type: ignore[assignment]

    d.on_conversation_turn({"conversation_id": 1, "turn_id": 1, "content": "a"})
    d.on_conversation_turn({"conversation_id": 4, "turn_id": 1, "content": "b"})

    # 两个回合都要真跑,不能有一个被当成重复跳过
    assert len(submitted) == 2
    assert all(name == "_run_conversation_turn" for name, _ in submitted)


def test_inflight_is_tracked_per_conversation(tmp_path: Path):
    """inflight 去重也必须按会话分桶:同会话同回合才跳过。"""
    d = _daemon(tmp_path)
    submitted = []
    d._conv_pool.submit = lambda fn, *a: submitted.append((fn.__name__, a))  # type: ignore[assignment]

    d.on_conversation_turn({"conversation_id": 1, "turn_id": 1, "content": "a"})
    d.on_conversation_turn({"conversation_id": 1, "turn_id": 1, "content": "a-again"})  # 同会话同回合
    d.on_conversation_turn({"conversation_id": 2, "turn_id": 1, "content": "b"})

    assert len(submitted) == 2  # 同会话重复被跳过,不同会话照跑
    assert (1, 1) in d._conv_inflight and (2, 1) in d._conv_inflight


def test_replay_of_finished_turn_reacks_instead_of_rerunning(tmp_path: Path):
    """同一会话同一回合的**重投**要重放既有 ACK,不能重跑一遍(否则重复扣 Token)。"""
    d = _daemon(tmp_path)
    submitted = []
    d._conv_pool.submit = lambda fn, *a: submitted.append((fn.__name__, a))  # type: ignore[assignment]

    d.on_conversation_turn({"conversation_id": 1, "turn_id": 5, "content": "a"})
    assert submitted and submitted[0][0] == "_run_conversation_turn"

    # 模拟该回合已完成:留底一条 ACK
    d._conv_done[(1, 5)] = {"type": "CONVERSATION_TURN_ACK", "conversationId": 1, "turnId": 5,
                            "status": "SUCCESS", "replyMarkdown": "答案"}
    d._conv_inflight.discard((1, 5))

    d.on_conversation_turn({"conversation_id": 1, "turn_id": 5, "content": "a"})
    assert submitted[-1][0] == "_reack_conversation_turn"


def test_done_cache_is_keyed_by_conversation(tmp_path: Path):
    """另一个会话的同号 turn 不能命中别的会话的 ACK 留底。"""
    d = _daemon(tmp_path)
    d._conv_done[(1, 1)] = {"type": "CONVERSATION_TURN_ACK", "conversationId": 1, "turnId": 1,
                            "status": "SUCCESS", "replyMarkdown": "属于会话1"}
    assert (1, 1) in d._conv_done
    assert (4, 1) not in d._conv_done


@pytest.mark.parametrize("bad_id", ["abc", "../../etc", -1, True, 1.5])
def test_conversation_workdir_rejects_unsafe_conversation_id(tmp_path: Path, bad_id):
    """会话号会被拼进路径:非整数/负数/含 .. 的值必须被拒,否则工作目录会越出 data_dir。"""
    with pytest.raises(ValueError):
        conversation_workdir(str(tmp_path), bad_id)  # type: ignore[arg-type]
    # 越界路径不能被创建出来
    assert not (tmp_path / "conversations" / str(bad_id)).exists()

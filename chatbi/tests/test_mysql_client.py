"""MySQL wire protocol 客户端的单元测试(不连真实库)。"""
from __future__ import annotations

import hashlib
import struct

import pytest

from chatbi.errors import MySQLError
from chatbi.mysql_client import (
    MySQLClient,
    native_password_scramble,
    parse_handshake,
    read_lenenc_bytes,
    read_lenenc_int,
)


# ------------------------------------------------------------------ 长度编码整数

@pytest.mark.parametrize("value", [0, 1, 250, 251, 252, 65535, 65536, 16777215, 16777216])
def test_lenenc_int_roundtrip(value: int) -> None:
    if value < 0xFB:
        payload = bytes([value])
    elif value <= 0xFFFF:
        payload = b"\xfc" + struct.pack("<H", value)
    elif value <= 0xFFFFFF:
        payload = b"\xfd" + struct.pack("<I", value)[:3]
    else:
        payload = b"\xfe" + struct.pack("<Q", value)
    decoded, pos = read_lenenc_int(payload, 0)
    assert decoded == value
    assert pos == len(payload)


def test_lenenc_int_null_marker() -> None:
    decoded, pos = read_lenenc_int(b"\xfb", 0)
    assert decoded is None
    assert pos == 1


def test_lenenc_bytes_returns_none_for_null() -> None:
    value, pos = read_lenenc_bytes(b"\xfb", 0)
    assert value is None
    assert pos == 1


def test_lenenc_bytes_roundtrip() -> None:
    payload = b"\x05hello"
    value, pos = read_lenenc_bytes(payload, 0)
    assert value == b"hello"
    assert pos == 6


# ------------------------------------------------------------------ 鉴权摘要

def test_native_password_scramble_length() -> None:
    scramble = bytes(range(20))
    token = native_password_scramble("secret", scramble)
    assert len(token) == 20
    assert token != scramble


def test_native_password_scramble_matches_spec_formula() -> None:
    password, scramble = "p@ssw0rd", b"0123456789abcdefghij"
    stage1 = hashlib.sha1(password.encode()).digest()
    stage2 = hashlib.sha1(stage1).digest()
    expected = bytes(
        a ^ b for a, b in zip(hashlib.sha1(scramble + stage2).digest(), stage1)
    )
    assert native_password_scramble(password, scramble) == expected


def test_native_password_scramble_empty_password() -> None:
    assert native_password_scramble("", b"0123456789abcdefghij") == b""


# ------------------------------------------------------------------ 握手解析

def _handshake_payload(plugin: str = "mysql_native_password") -> bytes:
    scramble1 = bytes(range(8))
    scramble2 = bytes(range(8, 20)) + b"\x00"  # 12 字节真实数据 + 终止符
    capabilities = 0x00000001 | 0x00000200 | 0x00008000 | 0x00080000
    body = bytearray()
    body += bytes([10])
    body += b"5.7.25-OceanBase\x00"
    body += struct.pack("<I", 12345)
    body += scramble1
    body += b"\x00"
    body += struct.pack("<H", capabilities & 0xFFFF)
    body += bytes([45])
    body += struct.pack("<H", 2)
    body += struct.pack("<H", (capabilities >> 16) & 0xFFFF)
    body += bytes([21])          # auth_plugin_data_len
    body += b"\x00" * 10
    body += scramble2
    body += plugin.encode() + b"\x00"
    return bytes(body)


def test_parse_handshake_extracts_scramble_and_plugin() -> None:
    info = parse_handshake(_handshake_payload())
    assert info.server_version == "5.7.25-OceanBase"
    assert info.connection_id == 12345
    assert info.auth_plugin == "mysql_native_password"
    assert len(info.scramble) == 20
    assert info.scramble == bytes(range(8)) + bytes(range(8, 20))


def test_parse_handshake_supports_ob_native_password() -> None:
    info = parse_handshake(_handshake_payload("ob_native_password"))
    assert info.auth_plugin == "ob_native_password"


# ------------------------------------------------------------------ 包收发

class FakeSocket:
    """够用的假 socket:记录发出的字节,按队列吐出收到的字节。"""

    def __init__(self, incoming: bytes = b"") -> None:
        self.incoming = bytearray(incoming)
        self.sent = bytearray()
        self._closed = False

    def recv(self, count: int) -> bytes:
        chunk = bytes(self.incoming[:count])
        del self.incoming[:count]
        return chunk

    def sendall(self, data: bytes) -> None:
        self.sent.extend(data)

    def settimeout(self, _timeout: float) -> None:
        pass

    def close(self) -> None:
        self._closed = True


def _packet(payload: bytes, seq: int) -> bytes:
    length = len(payload)
    return bytes([length & 0xFF, (length >> 8) & 0xFF, (length >> 16) & 0xFF, seq]) + payload


def test_write_packet_frames_payload() -> None:
    client = MySQLClient("h", 3306, "u", "p", "db")
    client._sock = FakeSocket()
    client._seq = 3
    client._write_packet(b"hello")
    assert bytes(client._sock.sent) == _packet(b"hello", 3)


def test_read_packet_parses_payload() -> None:
    client = MySQLClient("h", 3306, "u", "p", "db")
    client._sock = FakeSocket(_packet(b"payload", 2))
    assert client._read_packet() == b"payload"
    assert client._seq == 2


def test_read_packet_raises_on_closed_connection() -> None:
    client = MySQLClient("h", 3306, "u", "p", "db")
    client._sock = FakeSocket(b"")
    with pytest.raises(MySQLError):
        client._read_packet()


def test_error_packet_carries_code_and_message() -> None:
    client = MySQLClient("h", 3306, "u", "p", "db")
    payload = b"\xff" + struct.pack("<H", 1142) + b"#28000" + b"UPDATE command denied"
    with pytest.raises(MySQLError) as excinfo:
        client._raise_if_error(payload)
    assert excinfo.value.code == 1142
    assert excinfo.value.is_permission_denied
    assert "denied" in excinfo.value.message


def test_query_without_connection_raises() -> None:
    client = MySQLClient("h", 3306, "u", "p", "db")
    with pytest.raises(MySQLError):
        client.query("SELECT 1")

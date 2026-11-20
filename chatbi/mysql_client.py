"""纯标准库 MySQL 客户端(wire protocol v10)。

只实现读路径需要的能力:握手 → mysql_native_password 鉴权 → COM_QUERY → 结果集解析。
不支持 TLS、预处理语句、多结果集、连接池。
"""
from __future__ import annotations

import hashlib
import socket
import struct
from dataclasses import dataclass

from .errors import MySQLError

CLIENT_LONG_PASSWORD = 0x00000001
CLIENT_LONG_FLAG = 0x00000004
CLIENT_CONNECT_WITH_DB = 0x00000008
CLIENT_PROTOCOL_41 = 0x00000200
CLIENT_TRANSACTIONS = 0x00002000
CLIENT_SECURE_CONNECTION = 0x00008000
CLIENT_PLUGIN_AUTH = 0x00080000

CLIENT_FLAGS = (
    CLIENT_LONG_PASSWORD
    | CLIENT_LONG_FLAG
    | CLIENT_CONNECT_WITH_DB
    | CLIENT_PROTOCOL_41
    | CLIENT_TRANSACTIONS
    | CLIENT_SECURE_CONNECTION
    | CLIENT_PLUGIN_AUTH
)

MAX_PACKET_SIZE = 0x01000000
CHARSET_UTF8MB4 = 45

COM_QUERY = 0x03
COM_QUIT = 0x01

FIELD_TYPE_DATE = 0x0A
FIELD_TYPE_DATETIME = 0x0C
FIELD_TYPE_TIMESTAMP = 0x07
FIELD_TYPE_LONGLONG = 0x08
FIELD_TYPE_VAR_STRING = 0xFD
FIELD_TYPE_STRING = 0xFE
DATE_FIELD_TYPES = frozenset({FIELD_TYPE_DATE, FIELD_TYPE_DATETIME, FIELD_TYPE_TIMESTAMP})

# OceanBase 的 ob_native_password 与 mysql_native_password 同算法。
SUPPORTED_AUTH_PLUGINS = frozenset({"mysql_native_password", "ob_native_password"})


def native_password_scramble(password: str, scramble: bytes) -> bytes:
    """mysql_native_password 摘要:SHA1(scramble + SHA1(SHA1(pwd))) XOR SHA1(pwd)。"""
    if not password:
        return b""
    stage1 = hashlib.sha1(password.encode("utf-8")).digest()
    stage2 = hashlib.sha1(stage1).digest()
    token = hashlib.sha1(scramble + stage2).digest()
    return bytes(a ^ b for a, b in zip(token, stage1))


def read_lenenc_int(data: bytes, pos: int) -> tuple[int | None, int]:
    """读取长度编码整数。0xFB 在行数据里表示 NULL,这里返回 None。"""
    first = data[pos]
    pos += 1
    if first < 0xFB:
        return first, pos
    if first == 0xFB:
        return None, pos
    if first == 0xFC:
        return int.from_bytes(data[pos:pos + 2], "little"), pos + 2
    if first == 0xFD:
        return int.from_bytes(data[pos:pos + 3], "little"), pos + 3
    if first == 0xFE:
        return int.from_bytes(data[pos:pos + 8], "little"), pos + 8
    raise MySQLError(f"非法的长度编码整数前缀: 0x{first:02X}")


def read_lenenc_bytes(data: bytes, pos: int) -> tuple[bytes | None, int]:
    length, pos = read_lenenc_int(data, pos)
    if length is None:
        return None, pos
    return data[pos:pos + length], pos + length


def read_cstr(data: bytes, pos: int) -> tuple[str, int]:
    end = data.index(b"\x00", pos)
    return data[pos:end].decode("utf-8", "replace"), end + 1


@dataclass
class QueryResult:
    columns: list[str]
    column_types: list[int]
    rows: list[tuple]

    @property
    def row_count(self) -> int:
        return len(self.rows)

    def as_dicts(self) -> list[dict]:
        return [dict(zip(self.columns, row)) for row in self.rows]


@dataclass
class HandshakeInfo:
    server_version: str
    connection_id: int
    capabilities: int
    scramble: bytes
    auth_plugin: str


def parse_handshake(payload: bytes) -> HandshakeInfo:
    pos = 0
    protocol_version = payload[pos]
    pos += 1
    if protocol_version == 0xFF:
        raise MySQLError("服务端在握手前直接返回错误")
    server_version, pos = read_cstr(payload, pos)
    connection_id = int.from_bytes(payload[pos:pos + 4], "little")
    pos += 4
    scramble1 = payload[pos:pos + 8]
    pos += 8
    pos += 1  # filler
    cap_lower = int.from_bytes(payload[pos:pos + 2], "little")
    pos += 2
    pos += 1  # charset
    pos += 2  # status flags
    cap_upper = int.from_bytes(payload[pos:pos + 2], "little")
    pos += 2
    auth_data_len = payload[pos]
    pos += 1
    pos += 10  # reserved

    capabilities = cap_lower | (cap_upper << 16)
    scramble2_len = max(13, auth_data_len - 8)
    scramble2 = payload[pos:pos + scramble2_len]
    pos += scramble2_len

    scramble = scramble1 + scramble2
    if len(scramble) > 20:
        scramble = scramble[:20]
    elif len(scramble) < 20:
        scramble = scramble.ljust(20, b"\x00")

    auth_plugin = "mysql_native_password"
    if capabilities & CLIENT_PLUGIN_AUTH and pos < len(payload):
        auth_plugin, pos = read_cstr(payload, pos)

    return HandshakeInfo(server_version, connection_id, capabilities, scramble, auth_plugin)


class MySQLClient:
    """一个 MySQL 连接。用作上下文管理器。"""

    def __init__(self, host: str, port: int, user: str, password: str, database: str,
                 connect_timeout: float = 10.0) -> None:
        self.host = host
        self.port = port
        self.user = user
        self._password = password
        self.database = database
        self.connect_timeout = connect_timeout
        self._sock: socket.socket | None = None
        self._seq = 0
        self.server_version = ""
        self.capabilities = 0
        self.scramble = b""

    # ---------------------------------------------------------------- 底层收发

    def _recv_exact(self, count: int) -> bytes:
        assert self._sock is not None
        chunks = []
        remaining = count
        while remaining > 0:
            chunk = self._sock.recv(remaining)
            if not chunk:
                raise MySQLError("连接被对端关闭")
            chunks.append(chunk)
            remaining -= len(chunk)
        return b"".join(chunks)

    def _read_packet(self) -> bytes:
        payload = b""
        while True:
            header = self._recv_exact(4)
            length = header[0] | (header[1] << 8) | (header[2] << 16)
            self._seq = header[3]
            payload += self._recv_exact(length)
            if length < 0xFFFFFF:
                break
        return payload

    def _write_packet(self, payload: bytes) -> None:
        assert self._sock is not None
        offset = 0
        while True:
            chunk = payload[offset:offset + MAX_PACKET_SIZE]
            header = bytes([
                len(chunk) & 0xFF,
                (len(chunk) >> 8) & 0xFF,
                (len(chunk) >> 16) & 0xFF,
                self._seq,
            ])
            self._sock.sendall(header + chunk)
            offset += len(chunk)
            self._seq = (self._seq + 1) & 0xFF
            if len(chunk) < MAX_PACKET_SIZE:
                break

    # ---------------------------------------------------------------- 包解析

    def _raise_if_error(self, payload: bytes) -> None:
        if not payload:
            raise MySQLError("收到空包")
        marker = payload[0]
        if marker == 0xFF:
            code = int.from_bytes(payload[1:3], "little")
            pos = 3
            if payload[3:4] == b"#":
                pos = 9
            message = payload[pos:].decode("utf-8", "replace")
            raise MySQLError(message, code)
        if marker == 0xFB:
            raise MySQLError("不支持 LOCAL INFILE 协议")

    def _parse_ok(self, payload: bytes) -> None:
        return None

    # ---------------------------------------------------------------- 连接

    def connect(self) -> "MySQLClient":
        try:
            self._sock = socket.create_connection((self.host, self.port), timeout=self.connect_timeout)
            self._sock.settimeout(self.connect_timeout)
        except OSError as exc:
            raise MySQLError(f"无法连接数据库 {self.host}:{self.port} —— {exc}") from exc
        self._handshake()
        return self

    def _handshake(self) -> None:
        self._seq = 0
        payload = self._read_packet()
        self._raise_if_error(payload)
        info = parse_handshake(payload)
        self.server_version = info.server_version
        self.capabilities = info.capabilities
        self.scramble = info.scramble

        plugin = info.auth_plugin
        if plugin not in SUPPORTED_AUTH_PLUGINS:
            raise MySQLError(
                f"服务端要求不支持的鉴权插件 {plugin};"
                f"支持的插件: {', '.join(sorted(SUPPORTED_AUTH_PLUGINS))}"
            )

        auth_response = native_password_scramble(self._password, info.scramble)
        flags = CLIENT_FLAGS & (info.capabilities | CLIENT_LONG_PASSWORD | CLIENT_PROTOCOL_41
                                | CLIENT_SECURE_CONNECTION)

        body = bytearray()
        body += struct.pack("<I", flags)
        body += struct.pack("<I", MAX_PACKET_SIZE)
        body += bytes([CHARSET_UTF8MB4])
        body += b"\x00" * 23
        body += self.user.encode("utf-8") + b"\x00"
        body += bytes([len(auth_response)]) + auth_response
        if flags & CLIENT_CONNECT_WITH_DB:
            body += self.database.encode("utf-8") + b"\x00"
        if flags & CLIENT_PLUGIN_AUTH:
            body += plugin.encode("utf-8") + b"\x00"

        self._seq = 1
        self._write_packet(bytes(body))

        while True:
            response = self._read_packet()
            marker = response[0]
            if marker == 0x00:
                return
            if marker == 0xFF:
                self._raise_if_error(response)
            if marker == 0xFE:
                # AuthSwitchRequest
                plugin_name, pos = read_cstr(response, 1)
                if plugin_name not in SUPPORTED_AUTH_PLUGINS:
                    raise MySQLError(f"鉴权切换到不支持的插件 {plugin_name}")
                rest = response[pos:]
                new_scramble = rest.rstrip(b"\x00")
                self._seq += 1
                self._write_packet(native_password_scramble(self._password, new_scramble))
                continue
            raise MySQLError(f"鉴权阶段收到无法识别的包: 0x{marker:02X}")

    def close(self) -> None:
        if self._sock is None:
            return
        try:
            self._seq = 0
            self._write_packet(bytes([COM_QUIT]))
        except OSError:
            pass
        try:
            self._sock.close()
        finally:
            self._sock = None

    def __enter__(self) -> "MySQLClient":
        return self.connect()

    def __exit__(self, exc_type, exc, tb) -> None:
        self.close()

    # ---------------------------------------------------------------- 查询

    def query(self, sql: str) -> QueryResult:
        if self._sock is None:
            raise MySQLError("连接未建立")
        self._seq = 0
        self._write_packet(bytes([COM_QUERY]) + sql.encode("utf-8"))
        payload = self._read_packet()
        self._raise_if_error(payload)
        marker = payload[0]
        if marker == 0x00:
            return QueryResult([], [], [])
        if marker == 0xFE and len(payload) < 9:
            raise MySQLError("服务端返回了未预期的 EOF 包")

        column_count, _ = read_lenenc_int(payload, 0)
        if column_count is None:
            raise MySQLError("结果集列数为空")

        columns: list[str] = []
        column_types: list[int] = []
        for _ in range(column_count):
            column_payload = self._read_packet()
            name, col_type = self._parse_column_def(column_payload)
            columns.append(name)
            column_types.append(col_type)

        eof = self._read_packet()
        if eof[:1] != b"\xfe":
            self._raise_if_error(eof)

        rows: list[tuple] = []
        while True:
            row_payload = self._read_packet()
            if row_payload[:1] == b"\xfe" and len(row_payload) < 9:
                break
            self._raise_if_error(row_payload)
            row: list = []
            pos = 0
            for _ in range(column_count):
                value, pos = read_lenenc_bytes(row_payload, pos)
                row.append(None if value is None else value.decode("utf-8", "replace"))
            rows.append(tuple(row))

        return QueryResult(columns, column_types, rows)

    def _parse_column_def(self, payload: bytes) -> tuple[str, int]:
        pos = 0
        for _ in range(4):  # catalog, schema, table, org_table
            _, pos = read_lenenc_bytes(payload, pos)
        name, pos = read_lenenc_bytes(payload, pos)
        _, pos = read_lenenc_bytes(payload, pos)  # org_name
        _, pos = read_lenenc_int(payload, pos)  # length of fixed fields
        pos += 2  # charset
        pos += 4  # column length
        col_type = payload[pos]
        return (name or b"").decode("utf-8", "replace"), col_type

#!/usr/bin/env python3
"""
ESP32 Camera UDP -> HTTP MJPEG

默认（有 Pillow 时）：整图解码校验通过后原样转发，低开销、低延迟，并过滤明显坏帧。
可选 --reencode：解码后重新编码（更耗 CPU、画质/延迟会变差，仅顽固花屏时再试）。

使用随机 multipart 边界，降低与 JPEG 二进制偶然冲突的概率。
"""

from __future__ import annotations

import argparse
import os
import secrets
import socket
import threading
import time
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from io import BytesIO
from typing import Callable, Optional

try:
    from PIL import Image

    HAS_PIL = True
except ImportError:
    HAS_PIL = False


def _make_mjpeg_boundary() -> str:
    return "mjpeg" + secrets.token_hex(16)


class FrameBuffer:
    def __init__(
        self,
        *,
        pil_verify: bool,
        pil_reencode: bool,
        jpeg_quality: int,
    ) -> None:
        self._lock = threading.Lock()
        self._cond = threading.Condition(self._lock)
        self._jpeg: Optional[bytes] = None
        self._seq = 0
        self._last_ts = 0.0
        self._pil_reencode = bool(pil_reencode) and HAS_PIL
        self._pil_verify = bool(pil_verify) and HAS_PIL and not self._pil_reencode
        self._jpeg_quality = int(jpeg_quality)

    def update(self, raw: bytes) -> None:
        if not raw:
            return
        if len(raw) < _JPEG_MIN_BYTES or len(raw) > _JPEG_MAX_BYTES:
            return

        if self._pil_reencode:
            jpeg = _jpeg_normalize_pil(raw, quality=self._jpeg_quality)
            if not jpeg:
                return
        elif self._pil_verify:
            if not _jpeg_quick_header_footer(raw):
                return
            if not _pil_jpeg_fully_decodable(raw):
                return
            jpeg = raw
        else:
            if not _jpeg_quick_header_footer(raw):
                return
            jpeg = raw

        wall = time.time()
        with self._cond:
            self._jpeg = jpeg
            self._seq += 1
            self._last_ts = wall
            self._cond.notify_all()

    def get_latest(self) -> tuple[Optional[bytes], int, float]:
        with self._lock:
            return self._jpeg, self._seq, self._last_ts

    def wait_next(self, last_seq: int, timeout: float) -> tuple[Optional[bytes], int, float]:
        with self._cond:
            if self._seq == last_seq:
                self._cond.wait(timeout=timeout)
            return self._jpeg, self._seq, self._last_ts


_JPEG_MIN_BYTES = 256
_JPEG_MAX_BYTES = 12 * 1024 * 1024


def _jpeg_quick_header_footer(buf: bytes) -> bool:
    if len(buf) < _JPEG_MIN_BYTES or len(buf) > _JPEG_MAX_BYTES:
        return False
    return buf[0:2] == b"\xff\xd8" and buf[-2:] == b"\xff\xd9"


def _jpeg_trim_to_last_eoi(buf: bytes) -> bytes:
    """单包带填充时，从 SOI 裁到最后一个 EOI。"""
    if len(buf) < 4 or buf[:2] != b"\xff\xd8":
        return buf
    end = buf.rfind(b"\xff\xd9")
    if end < 2:
        return buf
    return buf[: end + 2]


def _is_complete_jpeg(buf: bytes) -> bool:
    return _jpeg_quick_header_footer(buf)


def _pil_jpeg_fully_decodable(buf: bytes) -> bool:
    if not HAS_PIL:
        return True
    try:
        with Image.open(BytesIO(buf)) as im:
            im.verify()
    except Exception:
        return False
    try:
        im = Image.open(BytesIO(buf))
        im.load()
        w, h = im.size
        if w < 2 or h < 2:
            return False
    except Exception:
        return False
    return True


def _jpeg_normalize_pil(buf: bytes, *, quality: int) -> Optional[bytes]:
    if not HAS_PIL:
        return None
    q = max(1, min(95, int(quality)))
    try:
        im = Image.open(BytesIO(buf))
        im.load()
        w, h = im.size
        if w < 2 or h < 2:
            return None
        if im.mode != "RGB":
            im = im.convert("RGB")
        out = BytesIO()
        im.save(
            out,
            format="JPEG",
            quality=q,
            optimize=True,
            subsampling="4:2:0",
        )
        raw = out.getvalue()
        if not _jpeg_quick_header_footer(raw):
            return None
        return raw
    except Exception:
        return None


class JpegExtractor:
    def __init__(self, max_buffer_bytes: int = 4 * 1024 * 1024) -> None:
        self._buf = bytearray()
        self._max = max_buffer_bytes

    def feed(self, chunk: bytes) -> list[bytes]:
        if not chunk:
            return []

        self._buf.extend(chunk)
        if len(self._buf) > self._max:
            self._buf = self._buf[-(self._max // 2) :]

        out: list[bytes] = []
        while True:
            soi = self._buf.find(b"\xff\xd8")
            if soi < 0:
                if len(self._buf) > 2:
                    self._buf = self._buf[-2:]
                break

            if soi > 0:
                del self._buf[:soi]

            eoi = self._buf.find(b"\xff\xd9", 2)
            if eoi < 0:
                break

            frame = bytes(self._buf[: eoi + 2])
            del self._buf[: eoi + 2]
            if _is_complete_jpeg(frame):
                out.append(frame)
        return out


def udp_receiver(
    fb: FrameBuffer,
    host: str,
    port: int,
    *,
    packet_is_frame: bool,
    udp_buf_bytes: int,
    extractor_max_bytes: int,
) -> None:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind((host, port))

    try:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, udp_buf_bytes)
    except OSError:
        pass

    extractor = JpegExtractor(max_buffer_bytes=extractor_max_bytes)

    while True:
        data, _addr = sock.recvfrom(65535)
        if not data:
            continue

        if packet_is_frame:
            if _is_complete_jpeg(data):
                fb.update(data)
            else:
                trimmed = _jpeg_trim_to_last_eoi(data)
                if _is_complete_jpeg(trimmed):
                    fb.update(trimmed)
                else:
                    for frame in extractor.feed(data):
                        fb.update(frame)
            continue

        for frame in extractor.feed(data):
            fb.update(frame)


class MjpegHandler(BaseHTTPRequestHandler):
    server_version = "ESP32MJPEG/2.1"

    def do_GET(self) -> None:
        srv: MjpegServer = self.server  # type: ignore[assignment]
        fb = srv.frame_buffer

        if self.path in ("/", "/index.html"):
            self._send_index()
            return
        if self.path == "/snapshot.jpg":
            self._send_snapshot(fb)
            return
        if self.path.startswith("/mjpeg"):
            self._stream_mjpeg(fb, srv.mjpeg_boundary)
            return

        self.send_error(HTTPStatus.NOT_FOUND, "Not Found")

    def log_message(self, fmt: str, *args) -> None:
        return

    def _send_index(self) -> None:
        host = self.headers.get("Host", "127.0.0.1")
        html = f"""<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <title>ESP32 MJPEG</title>
    <style>
      * {{ box-sizing: border-box; }}
      body {{
        font-family: system-ui, sans-serif;
        margin: 0;
        min-height: 100vh;
        background: #111;
        color: #eee;
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        padding: 16px;
      }}
      .hint {{ text-align: center; margin: 0 0 12px; max-width: 100%; line-height: 1.5; }}
      .video-wrap {{ display: flex; justify-content: center; align-items: center; width: 100%; max-width: min(100%, 1280px); }}
      img {{ max-width: 100%; height: auto; display: block; background: #000; margin: 0 auto; }}
      code {{ background: #333; padding: 2px 6px; border-radius: 4px; }}
    </style>
  </head>
  <body>
    <p class="hint">MJPEG: <code>http://{host}/mjpeg</code> · 快照: <code>/snapshot.jpg</code></p>
    <div class="video-wrap">
      <img src="/mjpeg" alt="stream" />
    </div>
  </body>
</html>
"""
        body = html.encode("utf-8")
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_snapshot(self, fb: FrameBuffer) -> None:
        jpeg, _seq, _ts = fb.get_latest()
        if not jpeg:
            self.send_error(HTTPStatus.SERVICE_UNAVAILABLE, "No frame yet")
            return
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", "image/jpeg")
        self.send_header("Content-Length", str(len(jpeg)))
        self.send_header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
        self.end_headers()
        self.wfile.write(jpeg)

    def _stream_mjpeg(self, fb: FrameBuffer, boundary: str) -> None:
        self.send_response(HTTPStatus.OK)
        self.send_header("Age", "0")
        self.send_header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
        self.send_header("Pragma", "no-cache")
        self.send_header("Content-Type", f"multipart/x-mixed-replace; boundary={boundary}")
        self.end_headers()

        last_seq = -1
        try:
            while True:
                jpeg, seq, _ts = fb.wait_next(last_seq, timeout=5.0)
                if not jpeg:
                    continue
                if seq == last_seq:
                    continue
                last_seq = seq

                part = (
                    f"--{boundary}\r\n"
                    "Content-Type: image/jpeg\r\n"
                    f"Content-Length: {len(jpeg)}\r\n"
                    "\r\n"
                ).encode("utf-8")
                self.wfile.write(part)
                self.wfile.write(jpeg)
                self.wfile.write(b"\r\n")
        except (ConnectionError, BrokenPipeError, ConnectionResetError):
            return


class MjpegServer(ThreadingHTTPServer):
    def __init__(
        self,
        server_address: tuple[str, int],
        RequestHandlerClass: Callable[..., BaseHTTPRequestHandler],
        frame_buffer: FrameBuffer,
        mjpeg_boundary: str,
    ) -> None:
        super().__init__(server_address, RequestHandlerClass)
        self.frame_buffer = frame_buffer
        self.mjpeg_boundary = mjpeg_boundary


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="ESP32 UDP JPEG -> HTTP MJPEG")
    p.add_argument("--udp-host", default="0.0.0.0", help="UDP bind host")
    p.add_argument("--udp-port", type=int, default=5000, help="UDP bind port")
    p.add_argument("--http-host", default="0.0.0.0", help="HTTP bind host")
    p.add_argument("--http-port", type=int, default=8080, help="HTTP bind port")
    p.add_argument("--packet-is-frame", action="store_true", help="每 UDP 包为一整帧 JPEG（推荐）")
    p.add_argument("--udp-rcvbuf", type=int, default=4 * 1024 * 1024)
    p.add_argument("--extractor-max-bytes", type=int, default=4 * 1024 * 1024)
    p.add_argument(
        "--no-decode-check",
        action="store_true",
        help="不做 Pillow 校验，仅头尾检查（最快；UDP 差时易花屏）",
    )
    p.add_argument(
        "--reencode",
        action="store_true",
        help="校验通过后仍重新编码（最耗 CPU、延迟高；仅顽固花屏时再试，需 Pillow）",
    )
    p.add_argument(
        "--jpeg-quality",
        type=int,
        default=85,
        metavar="Q",
        help="与 --reencode 配合，JPEG 质量 1–95，默认 85",
    )
    return p.parse_args()


def main() -> None:
    args = parse_args()
    reencode = bool(args.reencode) and HAS_PIL and not args.no_decode_check
    if args.reencode and not HAS_PIL:
        print("[WARN] --reencode 需要 Pillow，已忽略（请 pip install pillow）")
    elif args.reencode and args.no_decode_check:
        print("[WARN] 已同时指定 --no-decode-check，忽略 --reencode")

    pil_verify = HAS_PIL and not args.no_decode_check and not reencode

    if args.no_decode_check:
        print("[INFO] 仅头尾检查，未做 Pillow 整图校验")
    elif reencode:
        print("[INFO] Pillow 重新编码每帧（高开销）+ 随机 MJPEG 边界")
    elif HAS_PIL:
        print("[INFO] Pillow 整图校验后原样转发（默认，低延迟）+ 随机 MJPEG 边界")
    else:
        print("[WARN] 未安装 Pillow，仅头尾检查。建议: pip install pillow")

    q = max(1, min(95, int(args.jpeg_quality)))
    fb = FrameBuffer(
        pil_verify=pil_verify,
        pil_reencode=reencode,
        jpeg_quality=q,
    )
    boundary = _make_mjpeg_boundary()

    threading.Thread(
        target=udp_receiver,
        args=(fb, args.udp_host, args.udp_port),
        kwargs=dict(
            packet_is_frame=bool(args.packet_is_frame),
            udp_buf_bytes=int(args.udp_rcvbuf),
            extractor_max_bytes=int(args.extractor_max_bytes),
        ),
        daemon=True,
    ).start()

    httpd = MjpegServer(
        (args.http_host, args.http_port),
        MjpegHandler,
        fb,
        mjpeg_boundary=boundary,
    )
    sa = httpd.socket.getsockname()

    print(f"[HTTP] http://{sa[0]}:{sa[1]}/  (MJPEG: /mjpeg)")
    print(f"[UDP ] {args.udp_host}:{args.udp_port} (packet_is_frame={bool(args.packet_is_frame)})")
    print("按 Ctrl+C 退出")

    try:
        httpd.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()


if __name__ == "__main__":
    try:
        os.environ["PYTHONUNBUFFERED"] = "1"
    except Exception:
        pass
    main()

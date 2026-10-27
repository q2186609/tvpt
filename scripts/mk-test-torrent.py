#!/usr/bin/env python3
"""把一个本地视频打成单文件 .torrent（只用于本机播放链路的端到端测试）。

用法: python scripts/mk-test-torrent.py <文件> <输出.torrent> [announce]
"""
import hashlib
import os
import sys


def bencode(v):
    if isinstance(v, int):
        return b"i%de" % v
    if isinstance(v, bytes):
        return b"%d:%s" % (len(v), v)
    if isinstance(v, str):
        return bencode(v.encode())
    if isinstance(v, list):
        return b"l" + b"".join(bencode(x) for x in v) + b"e"
    if isinstance(v, dict):
        out = b"d"
        for k in sorted(v):
            out += bencode(k) + bencode(v[k])
        return out + b"e"
    raise TypeError(type(v))


def main():
    src, dst = sys.argv[1], sys.argv[2]
    announce = sys.argv[3] if len(sys.argv) > 3 else "http://127.0.0.1:1/announce"
    piece_len = 1 << 18  # 256 KiB

    total = os.path.getsize(src)
    pieces = []
    with open(src, "rb") as f:
        while True:
            chunk = f.read(piece_len)
            if not chunk:
                break
            pieces.append(hashlib.sha1(chunk).digest())

    info = {
        "name": os.path.basename(src),
        "length": total,
        "piece length": piece_len,
        "pieces": b"".join(pieces),
    }
    meta = {"announce": announce, "info": info}
    blob = bencode(meta)
    with open(dst, "wb") as f:
        f.write(blob)

    infohash = hashlib.sha1(bencode(info)).hexdigest()
    print(infohash)
    print(f"{total} bytes, {len(pieces)} pieces -> {dst}")


if __name__ == "__main__":
    main()

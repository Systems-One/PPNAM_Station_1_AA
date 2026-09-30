"""Inject barcode and RFID tag scans into a connected C72 handscanner over adb.

The app listens for the same broadcasts the scanner's own service sends, so an
`am broadcast` with the right action and a `data` extra is indistinguishable from
a real trigger pull:

    barcode  -> com.scanner.broadcast                   (Offload screen, SCAN step)
    RFID tag -> com.rscja.scanner.action.scanner.RFID   (badge login, Offload, Tag assignment)

Usage:
    python inject_scan.py barcode 6001234567890
    python inject_scan.py tag E28011700000021B2F6E9827
    python inject_scan.py pair 6001234567890 E2801170000002...   # barcode, then tag
    python inject_scan.py -s HC720DE260100322 tag ABC123          # pick a device
    python inject_scan.py devices
    python inject_scan.py                                         # interactive prompt

Interactive commands:  b <code> | t <tag> | p <code> <tag> | q
"""
from __future__ import annotations

import argparse
import os
import shlex
import shutil
import subprocess
import sys
import time
from pathlib import Path

BARCODE_ACTION = "com.scanner.broadcast"
RFID_ACTION = "com.rscja.scanner.action.scanner.RFID"


def find_adb() -> str:
    on_path = shutil.which("adb")
    if on_path:
        return on_path
    for root in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"),
                 os.environ.get("LOCALAPPDATA") and str(Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk")):
        if root:
            candidate = Path(root) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
            if candidate.exists():
                return str(candidate)
    sys.exit("adb not found: add platform-tools to PATH or set ANDROID_HOME")


class Scanner:
    def __init__(self, adb: str, serial: str | None):
        self.adb_path = adb
        self.serial = serial or self._only_device()

    def _run(self, *args: str) -> subprocess.CompletedProcess:
        cmd = [self.adb_path] + (["-s", self.serial] if self.serial else []) + list(args)
        return subprocess.run(cmd, capture_output=True, text=True, timeout=30)

    def _only_device(self) -> str:
        devices = list_devices(self.adb_path)
        if not devices:
            sys.exit("No adb device connected")
        if len(devices) > 1:
            sys.exit("Several devices connected, pick one with -s: " + ", ".join(devices))
        return devices[0]

    def _broadcast(self, action: str, value: str, label: str) -> bool:
        # adb shell joins its arguments into one remote sh command line, so quote the
        # value ourselves; otherwise spaces or shell characters in a code get mangled.
        remote = f"am broadcast -a {action} --es data {shlex.quote(value)}"
        result = self._run("shell", remote)
        ok = result.returncode == 0 and "Broadcast completed" in result.stdout
        status = "sent" if ok else "FAILED"
        print(f"[{self.serial}] {label:<7} {value!r} -> {status}")
        if not ok:
            print((result.stdout + result.stderr).strip(), file=sys.stderr)
        return ok

    def barcode(self, code: str) -> bool:
        return self._broadcast(BARCODE_ACTION, code, "barcode")

    def tag(self, tag: str) -> bool:
        return self._broadcast(RFID_ACTION, tag, "tag")

    def pair(self, code: str, tag: str, gap: float) -> bool:
        ok = self.barcode(code)
        time.sleep(gap)
        return self.tag(tag) and ok

    def foreground(self) -> str:
        out = self._run("shell", "dumpsys activity activities").stdout
        for line in out.splitlines():
            if "topResumedActivity" in line or "mResumedActivity" in line:
                return line.strip()
        return "unknown"


def list_devices(adb: str) -> list[str]:
    out = subprocess.run([adb, "devices"], capture_output=True, text=True, timeout=15).stdout
    return [line.split()[0] for line in out.splitlines()[1:]
            if line.strip() and line.split()[-1] == "device"]


def interactive(scanner: Scanner, gap: float) -> None:
    print(f"Connected to {scanner.serial}. Foreground: {scanner.foreground()}")
    print("Commands: b <barcode> | t <tag> | p <barcode> <tag> | q")
    while True:
        try:
            line = input("scan> ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            return
        if not line:
            continue
        cmd, _, rest = line.partition(" ")
        rest = rest.strip()
        if cmd in ("q", "quit", "exit"):
            return
        if cmd in ("b", "barcode") and rest:
            scanner.barcode(rest)
        elif cmd in ("t", "tag") and rest:
            scanner.tag(rest)
        elif cmd in ("p", "pair") and len(rest.split()) == 2:
            scanner.pair(*rest.split(), gap=gap)
        else:
            print("Commands: b <barcode> | t <tag> | p <barcode> <tag> | q")


def main() -> int:
    parser = argparse.ArgumentParser(description="Inject barcode / RFID tag scans into the C72 over adb.")
    parser.add_argument("-s", "--serial", help="device serial (needed when several are connected)")
    parser.add_argument("--gap", type=float, default=0.5,
                        help="seconds between barcode and tag in 'pair' (default 0.5)")
    sub = parser.add_subparsers(dest="command")
    sub.add_parser("devices", help="list connected devices")
    sub.add_parser("barcode", help="inject a barcode").add_argument("value")
    sub.add_parser("tag", help="inject an RFID tag").add_argument("value")
    p = sub.add_parser("pair", help="inject a barcode then a tag")
    p.add_argument("barcode")
    p.add_argument("tag")
    args = parser.parse_args()

    adb = find_adb()
    if args.command == "devices":
        for serial in list_devices(adb):
            print(serial)
        return 0

    scanner = Scanner(adb, args.serial)
    if args.command == "barcode":
        return 0 if scanner.barcode(args.value) else 1
    if args.command == "tag":
        return 0 if scanner.tag(args.value) else 1
    if args.command == "pair":
        return 0 if scanner.pair(args.barcode, args.tag, args.gap) else 1
    interactive(scanner, args.gap)
    return 0


if __name__ == "__main__":
    sys.exit(main())

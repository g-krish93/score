#!/usr/bin/env python3
"""Long-lived headed Chrome under Xvfb for CricHeroes Cloudflare bypass spike.

Starts Xvfb + x11vnc (localhost only) + real Chrome with a persistent user_data_dir
and CDP on 127.0.0.1:9222. Intended to run under systemd (cricheroes-browser.service).

ASSUMPTION (verify on EC2): cookies (cf_clearance, etc.) live in user_data_dir on disk.
A process crash/restart via systemd should NOT require re-solving Cloudflare — only
wiping user_data_dir or a materially new IP/fingerprint would. Reboot with persistent
EBS keeps the profile; ephemeral root would not.

SECURITY: CDP (9222) and VNC (5900) are bound to localhost only. Never expose them
on 0.0.0.0 — treat an open CDP port as equivalent to root on the browser session.
"""
from __future__ import annotations

import argparse
import logging
import os
import shutil
import signal
import socket
import subprocess
import sys
import time
from pathlib import Path
from typing import Optional

LOG = logging.getLogger("cricheroes-browser-session")

DEFAULT_DISPLAY = ":99"
DEFAULT_CDP_PORT = 9222
DEFAULT_VNC_PORT = 5900
DEFAULT_PROFILE_DIR = Path.home() / "cricheroes-browser-profile"
XVFB_SCREEN = "1280x720x24"


def _configure_logging(verbose: bool) -> None:
    level = logging.DEBUG if verbose else logging.INFO
    logging.basicConfig(
        level=level,
        format="%(asctime)s [%(name)s] %(levelname)s %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )


def find_chrome() -> str:
    for name in (
        "google-chrome-stable",
        "google-chrome",
        "chromium-browser",
        "chromium",
    ):
        path = shutil.which(name)
        if path:
            return path
    raise RuntimeError(
        "No Chrome/Chromium binary found. On Ubuntu/Debian run deploy/cricheroes-browser-setup.sh"
    )


def _port_open(host: str, port: int) -> bool:
    try:
        with socket.create_connection((host, port), timeout=1):
            return True
    except OSError:
        return False


def _wait_for_port(host: str, port: int, timeout_sec: float, label: str) -> None:
    deadline = time.monotonic() + timeout_sec
    while time.monotonic() < deadline:
        if _port_open(host, port):
            LOG.info("%s ready on %s:%s", label, host, port)
            return
        time.sleep(0.3)
    raise RuntimeError(f"{label} did not become ready on {host}:{port} within {timeout_sec}s")


def _wait_for_display(display: str, timeout_sec: float = 30) -> None:
    sock = Path(f"/tmp/.X11-unix/X{display.lstrip(':')}")
    deadline = time.monotonic() + timeout_sec
    while time.monotonic() < deadline:
        if sock.exists():
            LOG.info("X display %s socket ready", display)
            return
        time.sleep(0.3)
    raise RuntimeError(f"X display {display} socket not ready within {timeout_sec}s")


def _check_child_alive(proc: subprocess.Popen, name: str) -> None:
    code = proc.poll()
    if code is not None:
        raise RuntimeError(f"{name} exited early with code {code}")


class BrowserSessionManager:
    def __init__(
        self,
        *,
        display: str = DEFAULT_DISPLAY,
        cdp_port: int = DEFAULT_CDP_PORT,
        vnc_port: int = DEFAULT_VNC_PORT,
        profile_dir: Path = DEFAULT_PROFILE_DIR,
        start_url: str = "about:blank",
        enable_vnc: bool = True,
    ) -> None:
        self.display = display
        self.cdp_port = cdp_port
        self.vnc_port = vnc_port
        self.profile_dir = profile_dir
        self.start_url = start_url
        self.enable_vnc = enable_vnc
        self._children: list[subprocess.Popen] = []
        self._chrome_proc: Optional[subprocess.Popen] = None
        self._shutting_down = False

    def _spawn(
        self,
        cmd: list[str],
        *,
        env: Optional[dict] = None,
        name: str = "process",
    ) -> subprocess.Popen:
        LOG.info("Starting %s: %s", name, " ".join(cmd))
        proc = subprocess.Popen(
            cmd,
            env=env or os.environ.copy(),
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            start_new_session=True,
        )
        self._children.append(proc)
        return proc

    def _set_display_env(self) -> None:
        os.environ["DISPLAY"] = self.display

    def start_xvfb(self) -> None:
        if shutil.which("Xvfb") is None:
            raise RuntimeError("Xvfb not installed — run deploy/cricheroes-browser-setup.sh")
        self._spawn(
            [
                "Xvfb",
                self.display,
                "-screen",
                "0",
                XVFB_SCREEN,
                "-ac",
                "+extension",
                "GLX",
                "+render",
                "-noreset",
            ],
            name="Xvfb",
        )
        self._set_display_env()
        _wait_for_display(self.display, timeout_sec=30)

    def start_xvnc_display(self) -> None:
        """TigerVNC Xvnc: virtual display + VNC on one process (Amazon Linux fallback)."""
        if shutil.which("Xvnc") is None:
            raise RuntimeError("Neither x11vnc+Xvfb nor Xvnc (tigervnc-server) is installed")
        w, h, d = XVFB_SCREEN.split("x")
        proc = self._spawn(
            [
                "Xvnc",
                self.display,
                "-geometry",
                f"{w}x{h}",
                "-depth",
                d,
                "-rfbport",
                str(self.vnc_port),
                "-localhost",
                "-SecurityTypes",
                "None",
                "-AlwaysShared=1",
            ],
            name="Xvnc",
        )
        self._set_display_env()
        try:
            _wait_for_port("127.0.0.1", self.vnc_port, timeout_sec=30, label="TigerVNC")
        except RuntimeError:
            _check_child_alive(proc, "Xvnc")
            raise
        _check_child_alive(proc, "Xvnc")
        _wait_for_display(self.display, timeout_sec=30)

    def start_vnc(self) -> None:
        if not self.enable_vnc:
            return
        if shutil.which("x11vnc") is None:
            return
        env = os.environ.copy()
        env["DISPLAY"] = self.display
        # -localhost: listen on 127.0.0.1 only (SSH tunnel required).
        proc = self._spawn(
            [
                "x11vnc",
                "-display",
                self.display,
                "-localhost",
                "-nopw",
                "-forever",
                "-shared",
                "-rfbport",
                str(self.vnc_port),
                "-noxdamage",
            ],
            env=env,
            name="x11vnc",
        )
        try:
            _wait_for_port("127.0.0.1", self.vnc_port, timeout_sec=30, label="x11vnc")
        except RuntimeError as exc:
            _check_child_alive(proc, "x11vnc")
            raise
        _check_child_alive(proc, "x11vnc")

    def start_chrome(self) -> None:
        chrome = find_chrome()
        self.profile_dir.mkdir(parents=True, exist_ok=True)
        env = os.environ.copy()
        env["DISPLAY"] = self.display
        cmd = [
            chrome,
            f"--user-data-dir={self.profile_dir}",
            f"--remote-debugging-port={self.cdp_port}",
            "--remote-debugging-address=127.0.0.1",
            "--no-first-run",
            "--no-default-browser-check",
            "--disable-dev-shm-usage",
            "--disable-extensions",
            "--disable-background-networking",
            "--window-size=1280,720",
            self.start_url,
        ]
        self._chrome_proc = self._spawn(cmd, env=env, name="Chrome")
        try:
            _wait_for_port("127.0.0.1", self.cdp_port, timeout_sec=90, label="Chrome CDP")
        except RuntimeError:
            _check_child_alive(self._chrome_proc, "Chrome")
            raise
        _check_child_alive(self._chrome_proc, "Chrome")

    def start(self) -> None:
        LOG.info(
            "Profile dir: %s (persists cf_clearance across restarts if on durable disk)",
            self.profile_dir,
        )
        if self.enable_vnc and shutil.which("x11vnc"):
            self.start_xvfb()
            self.start_vnc()
        elif self.enable_vnc and shutil.which("Xvnc"):
            LOG.info("Using TigerVNC Xvnc (x11vnc not available)")
            self.start_xvnc_display()
        else:
            if self.enable_vnc:
                LOG.warning("No VNC server — install x11vnc or tigervnc-server for manual solve")
            self.start_xvfb()
        self.start_chrome()
        LOG.info(
            "Browser session up — CDP http://127.0.0.1:%s (localhost only), "
            "VNC localhost:%s (tunnel via ssh -L 5900:localhost:5900)",
            self.cdp_port,
            self.vnc_port,
        )

    def shutdown(self) -> None:
        if self._shutting_down:
            return
        self._shutting_down = True
        LOG.info("Shutting down browser session children...")
        for proc in reversed(self._children):
            if proc.poll() is None:
                try:
                    proc.terminate()
                except OSError:
                    pass
        deadline = time.monotonic() + 10
        for proc in reversed(self._children):
            if proc.poll() is None:
                remaining = max(0, deadline - time.monotonic())
                try:
                    proc.wait(timeout=remaining)
                except subprocess.TimeoutExpired:
                    proc.kill()
        self._children.clear()

    def wait(self) -> int:
        """Block until Chrome exits (or signal). Returns process exit code."""
        if self._chrome_proc is None:
            return 1
        try:
            return self._chrome_proc.wait()
        finally:
            self.shutdown()


def main(argv: Optional[list[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        description="Persistent headed Chrome for CricHeroes CDP scraping spike",
    )
    parser.add_argument("--display", default=DEFAULT_DISPLAY)
    parser.add_argument("--cdp-port", type=int, default=DEFAULT_CDP_PORT)
    parser.add_argument("--vnc-port", type=int, default=DEFAULT_VNC_PORT)
    parser.add_argument("--profile-dir", type=Path, default=DEFAULT_PROFILE_DIR)
    parser.add_argument("--start-url", default="about:blank")
    parser.add_argument("--no-vnc", action="store_true", help="Skip x11vnc (not recommended)")
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args(argv)

    _configure_logging(args.verbose)
    manager = BrowserSessionManager(
        display=args.display,
        cdp_port=args.cdp_port,
        vnc_port=args.vnc_port,
        profile_dir=args.profile_dir,
        start_url=args.start_url,
        enable_vnc=not args.no_vnc,
    )

    def _handle_signal(signum, _frame) -> None:
        LOG.info("Received signal %s — stopping", signum)
        manager.shutdown()
        sys.exit(0)

    signal.signal(signal.SIGTERM, _handle_signal)
    signal.signal(signal.SIGINT, _handle_signal)

    try:
        manager.start()
    except Exception as exc:
        LOG.error("Failed to start browser session: %s", exc)
        manager.shutdown()
        return 1

    code = manager.wait()
    LOG.info("Chrome exited with code %s", code)
    return code


if __name__ == "__main__":
    raise SystemExit(main())

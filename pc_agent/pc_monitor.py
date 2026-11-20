#!/usr/bin/env python3
"""
Smart Desk Companion (V6) — Lightweight Desktop System Resource Monitor (pc_monitor.py)
Pushes CPU %, RAM %, and Temperature to Firebase Realtime Database (/pc/stats.json)
using only Python 3 standard library (urllib) + psutil.
"""

import json
import logging
from logging.handlers import RotatingFileHandler
import os
import platform
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

try:
    import psutil
except ImportError:
    sys.stderr.write("Missing dependency: psutil. Run 'pip install psutil' or './install_windows.ps1'.\n")
    sys.exit(1)

SCRIPT_DIR = Path(__file__).resolve().parent
CONFIG_PATH = SCRIPT_DIR / "config.json"
LOG_PATH = SCRIPT_DIR / "pc_monitor.log"

# Configure tiny rotating log (100 KB max, 1 backup)
logger = logging.getLogger("SmartDeskPCMonitor")
logger.setLevel(logging.INFO)
handler = RotatingFileHandler(LOG_PATH, maxBytes=100 * 1024, backupCount=1, encoding="utf-8")
handler.setFormatter(logging.Formatter("%(asctime)s [%(levelname)s] %(message)s"))
logger.addHandler(handler)


def set_below_normal_priority() -> None:
    """Drop process priority so pc_monitor never competes with games or compilers."""
    try:
        proc = psutil.Process(os.getpid())
        if platform.system() == "Windows":
            proc.nice(psutil.BELOW_NORMAL_PRIORITY_CLASS)
        else:
            os.nice(10)
    except Exception as exc:
        logger.debug("Could not adjust process priority: %s", exc)


def load_config() -> dict:
    """Loads config.json next to the script, falling back to google-services.json if needed."""
    cfg = {
        "databaseUrl": "",
        "intervalSec": 5,
        "tempSource": "auto"
    }
    if CONFIG_PATH.exists():
        try:
            with open(CONFIG_PATH, "r", encoding="utf-8") as f:
                user_cfg = json.load(f)
                if isinstance(user_cfg, dict):
                    cfg.update(user_cfg)
        except Exception as exc:
            logger.warning("Failed to parse config.json: %s", exc)

    db_url = str(cfg.get("databaseUrl", "")).strip().rstrip("/")
    if not db_url or "smart-desk-companion-default-rtdb" in db_url:
        # Try reading from ../tablet/google-services.json if present
        gs_path = SCRIPT_DIR.parent / "tablet" / "google-services.json"
        if gs_path.exists():
            try:
                with open(gs_path, "r", encoding="utf-8") as f:
                    gs = json.load(f)
                    candidate = gs.get("project_info", {}).get("firebase_url", "").strip().rstrip("/")
                    if candidate and not db_url:
                        db_url = candidate
            except Exception:
                pass

    cfg["databaseUrl"] = db_url
    try:
        cfg["intervalSec"] = max(2, int(cfg.get("intervalSec", 5)))
    except Exception:
        cfg["intervalSec"] = 5

    temp_source = str(cfg.get("tempSource", "auto")).strip().lower()
    if temp_source not in ("auto", "gpu", "cpu", "none"):
        temp_source = "auto"
    cfg["tempSource"] = temp_source
    return cfg


def read_nvidia_gpu_temp() -> tuple[int | None, str | None]:
    """Reads NVIDIA GPU temperature via nvidia-smi without spawning a visible console window."""
    nvidia_smi = shutil.which("nvidia-smi")
    if not nvidia_smi and platform.system() == "Windows":
        default_win_path = Path(r"C:\Windows\System32\nvidia-smi.exe")
        if default_win_path.exists():
            nvidia_smi = str(default_win_path)

    if not nvidia_smi:
        return None, None

    try:
        creation_flags = subprocess.CREATE_NO_WINDOW if platform.system() == "Windows" else 0
        out = subprocess.check_output(
            [nvidia_smi, "--query-gpu=temperature.gpu", "--format=csv,noheader,nounits"],
            creationflags=creation_flags,
            timeout=2.5,
            stderr=subprocess.DEVNULL
        )
        first_line = out.decode("utf-8", errors="ignore").strip().splitlines()[0].strip()
        val = int(float(first_line))
        if 0 < val < 130:
            return val, "gpu"
    except Exception:
        pass
    return None, None


def _find_lhm_temp(node: dict, prefer_gpu: bool) -> int | None:
    """Recursively walks LibreHardwareMonitor's JSON tree for GPU Core or CPU Package temperature."""
    text = str(node.get("Text", "")).lower()
    value_str = str(node.get("Value", "")).strip()

    if "°c" in value_str.lower():
        try:
            numeric = float(value_str.lower().replace("°c", "").strip().split()[0])
            if 0 < numeric < 130:
                if prefer_gpu and ("gpu" in text or "hot spot" in text or "core" in text):
                    return int(round(numeric))
                if not prefer_gpu and ("cpu" in text or "package" in text or "tctl" in text or "core" in text):
                    return int(round(numeric))
        except Exception:
            pass

    for child in node.get("Children", []):
        if isinstance(child, dict):
            found = _find_lhm_temp(child, prefer_gpu=prefer_gpu)
            if found is not None:
                return found
    return None


def read_libre_hardware_monitor_temp(prefer_source: str = "auto") -> tuple[int | None, str | None]:
    """Reads optional LibreHardwareMonitor web server endpoint at http://localhost:8085/data.json."""
    try:
        req = urllib.request.Request("http://localhost:8085/data.json", method="GET")
        with urllib.request.urlopen(req, timeout=1.2) as resp:
            data = json.loads(resp.read().decode("utf-8", errors="ignore"))
            if prefer_source in ("auto", "gpu"):
                gpu_temp = _find_lhm_temp(data, prefer_gpu=True)
                if gpu_temp is not None:
                    return gpu_temp, "gpu"
            if prefer_source in ("auto", "cpu"):
                cpu_temp = _find_lhm_temp(data, prefer_gpu=False)
                if cpu_temp is not None:
                    return cpu_temp, "cpu"
    except Exception:
        pass
    return None, None


def read_linux_psutil_temp() -> tuple[int | None, str | None]:
    """Reads psutil.sensors_temperatures() on Linux."""
    if not hasattr(psutil, "sensors_temperatures"):
        return None, None
    try:
        temps = psutil.sensors_temperatures() or {}
        for _, entries in temps.items():
            for entry in entries:
                current = getattr(entry, "current", None)
                if current is not None and 0 < current < 130:
                    return int(round(current)), "cpu"
    except Exception:
        pass
    return None, None


def sample_temperature(temp_source_pref: str) -> tuple[int | None, str | None]:
    """
    Section 1.3: Temperature sources tried in order when tempSource is 'auto':
    1. NVIDIA GPU (nvidia-smi)
    2. LibreHardwareMonitor (http://localhost:8085/data.json)
    3. Linux psutil.sensors_temperatures()
    4. Otherwise (None, None)
    """
    if temp_source_pref == "none":
        return None, None

    if temp_source_pref in ("auto", "gpu"):
        temp, src = read_nvidia_gpu_temp()
        if temp is not None:
            return temp, src

    temp, src = read_libre_hardware_monitor_temp(prefer_source=temp_source_pref)
    if temp is not None:
        return temp, src

    if temp_source_pref in ("auto", "cpu"):
        temp, src = read_linux_psutil_temp()
        if temp is not None:
            return temp, src

    return None, None


def patch_stats_to_firebase(database_url: str, payload: dict) -> bool:
    """Sends an HTTP PATCH request to <databaseUrl>/pc/stats.json using standard library urllib."""
    endpoint = f"{database_url.rstrip('/')}/pc/stats.json"
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        endpoint,
        data=body,
        headers={"Content-Type": "application/json"},
        method="PATCH"
    )
    with urllib.request.urlopen(req, timeout=8.0) as resp:
        return 200 <= resp.status < 300


def main() -> None:
    set_below_normal_priority()
    hostname = socket.gethostname() or "MY-LAPTOP"

    # Prime psutil.cpu_percent(interval=None) and discard the first reading (which is always 0.0)
    psutil.cpu_percent(interval=None)
    time.sleep(0.5)

    last_sent_cpu: int | None = None
    last_sent_ram: int | None = None
    last_sent_temp: int | None = None
    last_write_time: float = 0.0

    backoff_steps = [10, 30, 60]
    failure_count = 0

    logger.info("Smart Desk PC Monitor started on host '%s'", hostname)

    while True:
        cfg = load_config()
        db_url = cfg["databaseUrl"]
        interval_sec = cfg["intervalSec"]
        temp_pref = cfg["tempSource"]

        if not db_url or "smart-desk-companion-default-rtdb" in db_url:
            logger.warning("Firebase databaseUrl is not configured in config.json yet. Retrying in 15s.")
            time.sleep(15)
            continue

        try:
            cpu_pct = int(round(psutil.cpu_percent(interval=None)))
            ram_pct = int(round(psutil.virtual_memory().percent))
            temp_val, temp_src = sample_temperature(temp_pref)

            now = time.monotonic()
            cpu_delta_ok = last_sent_cpu is None or abs(cpu_pct - last_sent_cpu) >= 2
            ram_delta_ok = last_sent_ram is None or abs(ram_pct - last_sent_ram) >= 1
            temp_delta_ok = (
                (temp_val != last_sent_temp)
                and (
                    temp_val is None
                    or last_sent_temp is None
                    or abs(temp_val - last_sent_temp) >= 1
                )
            )
            heartbeat_due = (now - last_write_time) >= 15.0

            if cpu_delta_ok or ram_delta_ok or temp_delta_ok or heartbeat_due:
                payload = {
                    "cpu": max(0, min(100, cpu_pct)),
                    "ram": max(0, min(100, ram_pct)),
                    "temp": temp_val,
                    "tempSource": temp_src,
                    "host": hostname,
                    "updatedAt": {".sv": "timestamp"}
                }
                if patch_stats_to_firebase(db_url, payload):
                    last_sent_cpu = cpu_pct
                    last_sent_ram = ram_pct
                    last_sent_temp = temp_val
                    last_write_time = now
                    if failure_count > 0:
                        logger.info("Reconnected to Firebase (/pc/stats).")
                    failure_count = 0

            time.sleep(interval_sec)

        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError) as net_err:
            delay = backoff_steps[min(failure_count, len(backoff_steps) - 1)]
            failure_count += 1
            logger.warning("Network error pushing PC stats (%s). Backing off %ds.", net_err, delay)
            time.sleep(delay)
        except Exception as unexpected:
            logger.error("Unexpected error in loop: %s", unexpected)
            time.sleep(10)


if __name__ == "__main__":
    main()

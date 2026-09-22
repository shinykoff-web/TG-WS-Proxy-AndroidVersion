import asyncio
import logging
import os
import socket
import threading
import time
import traceback
from pathlib import Path

from proxy.config import proxy_config, parse_dc_ip_list
from proxy.tg_ws_proxy import _run

_log = logging.getLogger("tg-ws-proxy-android")
_loop = None
_stop_event = None
_thread = None
_last_error = None
_lock = threading.Lock()

def configure_logging(log_path: str | None = None, verbose: bool = True):
    global _log
    root_logger = logging.getLogger()
    root_logger.setLevel(logging.DEBUG if verbose else logging.INFO)

    if log_path:
        Path(log_path).parent.mkdir(parents=True, exist_ok=True)
        # Remove handlers previously installed by this bridge.
        for h in list(root_logger.handlers):
            if getattr(h, "_tgws_android", False):
                root_logger.removeHandler(h)
                try:
                    h.close()
                except Exception:
                    pass
        fh = logging.FileHandler(log_path, encoding="utf-8")
        fh._tgws_android = True
        fh.setLevel(logging.DEBUG)
        fh.setFormatter(logging.Formatter(
            "%(asctime)s.%(msecs)03d  %(levelname)-8s  %(name)s  %(message)s",
            datefmt="%H:%M:%S"
        ))
        root_logger.addHandler(fh)
    return log_path

def _log_stage(name: str, **data):
    _log.info("ANDROID_STAGE %s %s", name, data)

def _configure(secret, port):
    proxy_config.host = "127.0.0.1"
    proxy_config.port = int(port)
    proxy_config.secret = secret
    proxy_config.dc_redirects = parse_dc_ip_list([
        "2:149.154.167.220",
        "4:149.154.167.220",
    ])
    proxy_config.buffer_size = 256 * 1024
    proxy_config.pool_size = 4
    proxy_config.fallback_cfproxy = True
    proxy_config.cfproxy_user_domains = []
    proxy_config.cfproxy_worker_domains = []
    proxy_config.disable_secure = False
    proxy_config.fake_tls_domain = ""
    proxy_config.proxy_protocol = False
    proxy_config.force_test_dc = False

def diagnostics(secret: str, port: int = 1443, log_path: str | None = None):
    """Run import, crypto, config and local socket diagnostics without starting the proxy."""
    configure_logging(log_path, verbose=True)
    result = {"ok": True, "checks": [], "error": None}
    def check(name, fn):
        try:
            value = fn()
            result["checks"].append({"name": name, "ok": True, "detail": str(value)})
            _log_stage("CHECK_OK", check=name, detail=value)
            return True
        except Exception:
            err = traceback.format_exc()
            result["checks"].append({"name": name, "ok": False, "detail": err})
            result["ok"] = False
            result["error"] = err
            _log_stage("CHECK_FAIL", check=name)
            _log.error("Check %s failed:\n%s", name, err)
            return False

    check("python", lambda: __import__("sys").version)
    check("proxy_import", lambda: (__import__("proxy.tg_ws_proxy"), "import ok"))
    check("cryptography", lambda: __import__("cryptography").__version__)
    def aes_check():
        from proxy._aes import Cipher, algorithms, modes
        c = Cipher(algorithms.AES(b"0123456789abcdef"), modes.CTR(b"0123456789abcdef")).encryptor()
        a = c.update(b"android-test-vector")
        return f"AES CTR ok, {len(a)} bytes"
    check("aes_ctr", aes_check)
    check("secret", lambda: (bytes.fromhex(secret), "32 hex chars"))
    def socket_check():
        _configure(secret, port)
        # When our own proxy is already running, connecting to its listener is
        # the correct health check. Otherwise verify that the port is free.
        if _thread is not None and _thread.is_alive():
            with socket.create_connection(("127.0.0.1", int(port)), timeout=0.5):
                return "proxy listener reachable (already running)"
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.bind(("127.0.0.1", int(port)))
            return s.getsockname()
    check("local_port", socket_check)

    if result["ok"]:
        _log_stage("DIAGNOSTICS_OK")
    else:
        _log_stage("DIAGNOSTICS_FAILED")
    return result

def _runner():
    global _loop, _stop_event, _last_error
    try:
        _log_stage("THREAD_ENTER")
        _loop = asyncio.new_event_loop()
        asyncio.set_event_loop(_loop)
        _stop_event = asyncio.Event()
        _log_stage("RUN_BEFORE")
        _loop.run_until_complete(_run(_stop_event))
        _log_stage("RUN_RETURNED")
    except BaseException:
        _last_error = traceback.format_exc()
        logging.exception("TG WS Proxy crashed in runner")
        _log_stage("THREAD_EXCEPTION", error=_last_error)
    finally:
        _log_stage("THREAD_FINALLY")
        try:
            if _loop is not None:
                _loop.close()
        except Exception:
            pass

def start_proxy(secret: str, port: int = 1443, timeout: int = 15, log_path: str | None = None):
    global _thread, _last_error
    configure_logging(log_path, verbose=True)
    with _lock:
        if _thread is not None and _thread.is_alive():
            return {"ok": True, "already_running": True, "secret": proxy_config.secret, "port": proxy_config.port}

        _configure(secret, port)
        _last_error = None
        _log_stage("START_REQUEST", host=proxy_config.host, port=proxy_config.port)

        _thread = threading.Thread(target=_runner, daemon=True, name="tg-ws-proxy")
        _thread.start()

    deadline = time.monotonic() + timeout
    last_probe_error = None
    while time.monotonic() < deadline:
        if _last_error:
            return {"ok": False, "error": _last_error, "stage": "runner_exception"}
        if _thread is None or not _thread.is_alive():
            return {
                "ok": False,
                "error": _last_error or "Поток прокси завершился до открытия порта.",
                "stage": "thread_stopped"
            }
        try:
            with socket.create_connection(("127.0.0.1", int(port)), timeout=0.25):
                _log_stage(
                    "LISTENER_OK",
                    host="127.0.0.1",
                    port=port,
                    note="probe connects and closes without sending MTProto bytes"
                )
                return {"ok": True, "already_running": False, "secret": proxy_config.secret, "port": proxy_config.port}
        except OSError as exc:
            last_probe_error = repr(exc)
            time.sleep(0.15)

    _log_stage("LISTENER_TIMEOUT", last_probe_error=last_probe_error)
    return {
        "ok": False,
        "error": _last_error or f"Порт 127.0.0.1:{int(port)} не начал принимать соединения за {timeout} секунд.\nПоследняя проверка: {last_probe_error}",
        "stage": "listener_timeout"
    }

def stop_proxy(timeout=5):
    global _thread, _loop, _stop_event
    _log_stage("STOP_REQUEST")
    with _lock:
        if _thread is None or not _thread.is_alive():
            return {"ok": True, "already_stopped": True}
        if _loop is not None and _stop_event is not None:
            _loop.call_soon_threadsafe(_stop_event.set)
        thread = _thread

    thread.join(timeout)
    if thread.is_alive():
        _log_stage("STOP_TIMEOUT")
        return {"ok": False, "error": "Прокси не завершился вовремя."}
    _log_stage("STOPPED")
    return {"ok": True}

def status():
    return {
        "running": _thread is not None and _thread.is_alive(),
        "port": proxy_config.port,
        "secret": proxy_config.secret,
        "error": _last_error,
    }

def read_log(log_path: str, max_chars: int = 30000):
    try:
        p = Path(log_path)
        if not p.exists():
            return "(лог пока пуст)"
        text = p.read_text(encoding="utf-8", errors="replace")
        return text[-max_chars:]
    except Exception as exc:
        return f"Не удалось прочитать лог: {exc!r}"


def _json_default(obj):
    try:
        return str(obj)
    except Exception:
        return "<unserializable>"

def start_proxy_json(secret: str, port: int = 1443, timeout: int = 15, log_path: str | None = None):
    import json
    return json.dumps(
        start_proxy(secret, port, timeout, log_path),
        default=_json_default,
        ensure_ascii=False,
    )

def diagnostics_json(secret: str, port: int = 1443, log_path: str | None = None):
    import json
    return json.dumps(
        diagnostics(secret, port, log_path),
        default=_json_default,
        ensure_ascii=False,
    )

def stop_proxy_json(timeout: int = 5):
    import json
    return json.dumps(stop_proxy(timeout), default=_json_default, ensure_ascii=False)

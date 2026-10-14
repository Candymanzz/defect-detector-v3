#!/usr/bin/env python3
"""Diagnostic Linux MVS interface service; no hardware writes at startup."""
import argparse
import ctypes as ct
import json
import os
from pathlib import Path
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class SdkError(RuntimeError):
    pass


def check(code, operation):
    if code:
        raise SdkError(f"{operation}: MVS error 0x{code & 0xffffffff:08x}")


def string(value):
    return bytes(value).split(b"\0", 1)[0].decode("utf-8", errors="replace")


class Mvs:
    def __init__(self, root):
        root = Path(root).resolve()
        imports = root / "Samples/64/Python/MvImport"
        if not imports.is_dir():
            raise SdkError(f"MVS Python headers not found: {imports}")
        sys.path.insert(0, str(imports))
        import CameraParams_header as types
        self.types = types
        self.lib = ct.CDLL(str(root / "lib/64/libMvCameraControl.so"))
        self.lock = threading.RLock()
        signatures = {
            "MV_CC_Initialize": [], "MV_CC_Finalize": [],
            "MV_CC_EnumInterfaces": [ct.c_uint, ct.POINTER(types.MV_INTERFACE_INFO_LIST)],
            "MV_CC_CreateInterfaceByID": [ct.POINTER(ct.c_void_p), ct.c_char_p],
            "MV_CC_OpenInterface": [ct.c_void_p, ct.c_char_p],
            "MV_CC_CloseInterface": [ct.c_void_p],
            "MV_CC_DestroyInterface": [ct.c_void_p],
            "MV_CC_GetEnumValue": [ct.c_void_p, ct.c_char_p, ct.POINTER(types.MVCC_ENUMVALUE)],
            "MV_CC_GetEnumEntrySymbolic": [ct.c_void_p, ct.c_char_p, ct.POINTER(types.MVCC_ENUMENTRY)],
            "MV_CC_SetEnumValueByString": [ct.c_void_p, ct.c_char_p, ct.c_char_p],
            "MV_CC_GetIntValueEx": [ct.c_void_p, ct.c_char_p, ct.POINTER(types.MVCC_INTVALUE_EX)],
            "MV_CC_SetIntValueEx": [ct.c_void_p, ct.c_char_p, ct.c_int64],
            "MV_CC_SetCommandValue": [ct.c_void_p, ct.c_char_p],
            "MV_XML_GetNodeAccessMode": [ct.c_void_p, ct.c_char_p, ct.POINTER(ct.c_int)],
        }
        for name, args in signatures.items():
            fn = getattr(self.lib, name)
            fn.argtypes = args
            fn.restype = ct.c_uint
        check(self.lib.MV_CC_Initialize(), "initialize")

    def interfaces(self):
        with self.lock:
            result = []
            # Official interface masks: light controllers, virtual cards, frame grabbers.
            for name, mask in (("light", 0x40), ("virtual", 0x20), ("frame_grabber", 0x1d)):
                values = self.types.MV_INTERFACE_INFO_LIST()
                code = self.lib.MV_CC_EnumInterfaces(mask, ct.byref(values))
                item = {"transport": name, "code": f"0x{code:08x}", "interfaces": []}
                if not code:
                    if values.nInterfaceNum > len(values.pInterfaceInfos):
                        raise SdkError("Invalid interface count returned by SDK")
                    for i in range(values.nInterfaceNum):
                        info = values.pInterfaceInfos[i].contents
                        item["interfaces"].append({
                            "id": string(info.chInterfaceID), "model": string(info.chModelName),
                            "name": string(info.chDisplayName), "serial": string(info.chSerialNumber),
                        })
                result.append(item)
            return result

    def enum(self, handle, key):
        value = self.types.MVCC_ENUMVALUE()
        check(self.lib.MV_CC_GetEnumValue(handle, key.encode(), ct.byref(value)), f"read {key}")
        if value.nSupportedNum > len(value.nSupportValue):
            raise SdkError(f"Invalid enum count for {key}")
        symbols = {}
        for index in [value.nCurValue, *value.nSupportValue[:value.nSupportedNum]]:
            entry = self.types.MVCC_ENUMENTRY()
            entry.nValue = index
            check(self.lib.MV_CC_GetEnumEntrySymbolic(handle, key.encode(), ct.byref(entry)), f"symbol {key}")
            symbols[index] = string(entry.chSymbolic)
        return {"current": symbols[value.nCurValue],
                "supported": [symbols[v] for v in value.nSupportValue[:value.nSupportedNum]]}

    def set_enum(self, handle, key, symbol):
        choices = self.enum(handle, key)
        if symbol not in choices["supported"]:
            raise SdkError(f"{key} does not support {symbol!r}; supported: {choices['supported']}")
        check(self.lib.MV_CC_SetEnumValueByString(handle, key.encode(), symbol.encode()), f"set {key}={symbol}")
        if self.enum(handle, key)["current"] != symbol:
            raise SdkError(f"Readback mismatch for {key}")

    def integer(self, handle, key):
        value = self.types.MVCC_INTVALUE_EX()
        check(self.lib.MV_CC_GetIntValueEx(handle, key.encode(), ct.byref(value)), f"read {key}")
        return {"current": value.nCurValue, "min": value.nMin, "max": value.nMax, "increment": value.nInc}

    def with_interface(self, interface_id, action):
        if not interface_id:
            raise SdkError("Specify --interface-id from --list; no automatic device selection")
        with self.lock:
            handle = ct.c_void_p()
            check(self.lib.MV_CC_CreateInterfaceByID(ct.byref(handle), interface_id.encode()), "create interface")
            opened = False
            try:
                check(self.lib.MV_CC_OpenInterface(handle, None), "open interface")
                opened = True
                return action(handle)
            finally:
                if opened:
                    code = self.lib.MV_CC_CloseInterface(handle)
                    if code:
                        print(f"close interface: 0x{code:08x}", file=sys.stderr)
                self.lib.MV_CC_DestroyInterface(handle)

    def inspect(self, interface_id):
        def read(handle):
            result = {}
            for key in ("TimerSelector", "TimerTriggerSource", "LineSelector", "LineMode", "LineSource"):
                try:
                    result[key] = self.enum(handle, key)
                except SdkError as e:
                    result[key] = {"error": str(e)}
            for key in ("TimerDuration", "TimerDelay"):
                try:
                    result[key] = self.integer(handle, key)
                except SdkError as e:
                    result[key] = {"error": str(e)}
            return result
        return self.with_interface(interface_id, read)

    def pulse(self, interface_id, line, configure=False, duration_native=None):
        def fire(handle):
            old_timer = self.enum(handle, "TimerSelector")["current"]
            old_line = self.enum(handle, "LineSelector")["current"]
            try:
                self.set_enum(handle, "TimerSelector", "Timer6")
                self.set_enum(handle, "LineSelector", line)
                access = ct.c_int()
                check(self.lib.MV_XML_GetNodeAccessMode(handle, b"TimerTriggerSoftware", ct.byref(access)), "check timer command")
                if access.value not in (2, 4):  # AM_WO, AM_RW from CameraParams.h.
                    raise SdkError(f"TimerTriggerSoftware is not writable: access={access.value}")
                desired = {"TimerTriggerSource": "Software", "LineMode": "Output", "LineSource": "Timer6Active"}
                # Validate every enum before changing the route.
                for key, symbol in desired.items():
                    if symbol not in self.enum(handle, key)["supported"]:
                        raise SdkError(f"{key} does not expose {symbol}; inspect device nodes")
                if duration_native is not None:
                    value = self.integer(handle, "TimerDuration")
                    if not value["min"] <= duration_native <= value["max"] or (value["increment"] > 0 and (duration_native - value["min"]) % value["increment"]):
                        raise SdkError(f"TimerDuration out of range: {value}")
                if configure:
                    for key, symbol in desired.items():
                        self.set_enum(handle, key, symbol)
                    if duration_native is not None:
                        check(self.lib.MV_CC_SetIntValueEx(handle, b"TimerDuration", duration_native), "set TimerDuration")
                        if self.integer(handle, "TimerDuration")["current"] != duration_native:
                            raise SdkError("TimerDuration readback mismatch")
                elif duration_native is not None:
                    raise SdkError("--duration-native requires --configure")
                for key, symbol in desired.items():
                    if self.enum(handle, key)["current"] != symbol:
                        raise SdkError(f"Route not configured: {key} must be {symbol}. Use MVS or --configure")
                duration = self.integer(handle, "TimerDuration")
                check(self.lib.MV_CC_SetCommandValue(handle, b"TimerTriggerSoftware"), "execute Timer6")
                return {"status": "sdk_command_accepted", "timer": "Timer6", "line": line,
                        "duration_native": duration, "physical_signal_verified": False}
            finally:
                # These selectors select nodes; they do not disconnect the timer output route.
                for key, value in (("LineSelector", old_line), ("TimerSelector", old_timer)):
                    try:
                        self.set_enum(handle, key, value)
                    except SdkError as e:
                        print(f"selector restoration failed: {e}", file=sys.stderr)
        return self.with_interface(interface_id, fire)

    def close(self):
        with self.lock:
            self.lib.MV_CC_Finalize()


def serve(sdk, args):
    class Handler(BaseHTTPRequestHandler):
        def response(self, status, value):
            body = json.dumps(value, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            try:
                if self.path == "/health":
                    self.response(200, {"status": "ok", "sdk_loaded": True, "hardware_ready": "check /interfaces"})
                elif self.path == "/interfaces":
                    self.response(200, sdk.interfaces())
                elif self.path == "/nodes":
                    self.response(200, sdk.inspect(args.interface_id))
                else:
                    self.response(404, {"error": "unknown endpoint"})
            except (SdkError, OSError) as e:
                self.response(503, {"error": str(e)})

        def do_POST(self):
            if self.path != "/pulse":
                self.response(404, {"error": "unknown endpoint"})
                return
            try:
                self.response(200, sdk.pulse(args.interface_id, args.line, args.configure, args.duration_native))
            except (SdkError, OSError) as e:
                self.response(503, {"error": str(e), "physical_signal_verified": False})

    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.daemon_threads = False
    print(f"MVS IO service: http://127.0.0.1:{args.port}; no pulse at startup", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


def main():
    parser = argparse.ArgumentParser(description="Linux MVS: diagnostic DO6 / Timer6 service")
    parser.add_argument("--mvs-root", default=os.getenv("MVS_ROOT", "/opt/MVS"))
    parser.add_argument("--interface-id")
    parser.add_argument("--line", default="DO6", help="Exact SDK LineSelector symbol; do not assume Line6 means DO6")
    parser.add_argument("--configure", action="store_true", help="Configure Timer6 software trigger and output route before pulse")
    parser.add_argument("--duration-native", type=int, help="TimerDuration in device native units; verify units in MVS")
    parser.add_argument("--port", type=int, default=9116)
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--list", action="store_true")
    modes.add_argument("--inspect", action="store_true")
    modes.add_argument("--pulse", action="store_true")
    args = parser.parse_args()
    sdk = None
    try:
        sdk = Mvs(args.mvs_root)
        if args.list:
            result = sdk.interfaces()
        elif args.inspect:
            result = sdk.inspect(args.interface_id)
        elif args.pulse:
            result = sdk.pulse(args.interface_id, args.line, args.configure, args.duration_native)
        else:
            serve(sdk, args)
            return 0
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except (SdkError, OSError, AttributeError, ImportError) as e:
        print(json.dumps({"error": str(e), "physical_signal_verified": False}, ensure_ascii=False), file=sys.stderr)
        return 2
    finally:
        if sdk:
            sdk.close()


if __name__ == "__main__":
    raise SystemExit(main())

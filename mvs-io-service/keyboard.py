#!/usr/bin/env python3
"""Space -> MVS Timer6 software trigger on a serial controller."""
import argparse
from contextlib import contextmanager
import ctypes as ct
import json
import os
import sys
import termios
import time
import tty

from service import Mvs, SdkError, check, string


class SerialMvs(Mvs):
    def __init__(self, root):
        super().__init__(root)
        try:
            signatures = {
                'MV_CAML_GetSerialPortList': [ct.POINTER(self.types.MV_CAML_SERIAL_PORT_LIST)],
                'MV_CAML_SetEnumSerialPorts': [ct.POINTER(self.types.MV_CAML_SERIAL_PORT_LIST)],
                'MV_CC_EnumDevices': [ct.c_uint, ct.POINTER(self.types.MV_CC_DEVICE_INFO_LIST)],
                'MV_CC_CreateHandle': [ct.POINTER(ct.c_void_p), ct.POINTER(self.types.MV_CC_DEVICE_INFO)],
                'MV_CC_OpenDevice': [ct.c_void_p, ct.c_uint, ct.c_ushort],
                'MV_CC_CloseDevice': [ct.c_void_p],
                'MV_CC_DestroyHandle': [ct.c_void_p],
                'MV_CC_GetBoolValue': [ct.c_void_p, ct.c_char_p, ct.POINTER(ct.c_bool)],
            }
            for name, args in signatures.items():
                fn = getattr(self.lib, name)
                fn.argtypes = args
                fn.restype = ct.c_uint
        except Exception:
            self.close()
            raise

    def boolean(self, handle, key):
        value = ct.c_bool()
        check(self.lib.MV_CC_GetBoolValue(handle, key.encode(), ct.byref(value)), f'read {key}')
        return value.value

    def devices(self, port=None):
        if port:
            ports = self.types.MV_CAML_SERIAL_PORT_LIST()
            encoded = port.encode()
            if len(encoded) >= 64:
                raise SdkError('Port name is too long')
            ports.nSerialPortNum = 1
            ports.stSerialPort[0].chSerialPort = encoded
            check(self.lib.MV_CAML_SetEnumSerialPorts(ct.byref(ports)), 'select enumeration port')
        values = self.types.MV_CC_DEVICE_INFO_LIST()
        check(self.lib.MV_CC_EnumDevices(8, ct.byref(values)), 'enumerate serial MVS devices')
        if values.nDeviceNum > len(values.pDeviceInfo):
            raise SdkError('Invalid device count returned by SDK')
        result = []
        for i in range(values.nDeviceNum):
            if values.pDeviceInfo[i].contents.nTLayerType != 8:
                continue
            # Copy SDK-owned information before any subsequent enumeration.
            info = self.types.MV_CC_DEVICE_INFO.from_buffer_copy(values.pDeviceInfo[i].contents)
            serial = info.SpecialInfo.stCamLInfo
            result.append(({'port': string(serial.chPortID), 'model': string(serial.chModelName),
                            'serial': string(serial.chSerialNumber)}, info))
        return result

    @contextmanager
    def controller(self, port, serial_number):
        devices = self.devices(port)
        matches = [(meta, info) for meta, info in devices
                   if (not port or meta['port'] == port)
                   and (not serial_number or meta['serial'] == serial_number)
                   and meta['model'].startswith('MV-VC')]
        if len(matches) != 1:
            raise SdkError(f'Expected one MV-VC controller, found {len(matches)}. '
                           'Use --list, then specify --port and/or --serial. '
                           f'Devices: {[meta for meta, _ in devices]}')
        meta, info = matches[0]
        handle = ct.c_void_p()
        check(self.lib.MV_CC_CreateHandle(ct.byref(handle), ct.byref(info)), 'create controller handle')
        opened = False
        try:
            code = self.lib.MV_CC_OpenDevice(handle, 1, 0)
            if code:
                raise SdkError(f'Open {meta["port"]}: 0x{code:08x}. '
                               'Disconnect this device in MVS and stop other IO services; '
                               'the serial port may be occupied.')
            opened = True
            yield handle, meta
        finally:
            if opened:
                code = self.lib.MV_CC_CloseDevice(handle)
                if code:
                    print(f'Close device failed: 0x{code:08x}', file=sys.stderr)
            self.lib.MV_CC_DestroyHandle(handle)


@contextmanager
def selected_timer(sdk, handle, timer):
    old = sdk.enum(handle, 'TimerSelector')['current']
    try:
        sdk.set_enum(handle, 'TimerSelector', timer)
        source = sdk.enum(handle, 'TimerTriggerSource')['current']
        if source != 'Software':
            raise SdkError(f'{timer}: TimerTriggerSource={source!r}, expected Software. '
                           'Configure the timer in MVS first.')
        mode = ct.c_int()
        # This controller's GenICam XML names the command TriggerSoftware.
        check(sdk.lib.MV_XML_GetNodeAccessMode(handle, b'TriggerSoftware', ct.byref(mode)),
              'check TriggerSoftware')
        if mode.value not in (2, 4):
            raise SdkError(f'TriggerSoftware is not writable (access={mode.value})')
        yield
    finally:
        try:
            sdk.set_enum(handle, 'TimerSelector', old)
        except SdkError as e:
            print(f'Could not restore TimerSelector: {e}', file=sys.stderr)


def verify_output(sdk, handle):
    old = sdk.enum(handle, 'LineSelector')['current']
    try:
        sdk.set_enum(handle, 'LineSelector', 'Out6')
        source = sdk.enum(handle, 'LineSource')['current']
        polarity = sdk.enum(handle, 'LinePolarity')['current']
        print(f'DO6: источник {source}, полярность {polarity}.', flush=True)
        if source != 'Timer6':
            raise SdkError(f'DO6 source is {source!r}; configure Out6 → Timer6 in MVS')
    finally:
        sdk.set_enum(handle, 'LineSelector', old)


@contextmanager
def timer_duration(sdk, handle, duration_ms):
    original = sdk.integer(handle, 'TimerDuration')
    changed = False
    try:
        if duration_ms is not None:
            value = duration_ms * 1000  # Controller XML explicitly specifies microseconds.
            increment = original['increment']
            if not original['min'] <= value <= original['max'] or (increment > 0 and (value - original['min']) % increment):
                raise SdkError(f'TimerDuration={value} us is outside device limits: {original}')
            check(sdk.lib.MV_CC_SetIntValueEx(handle, b'TimerDuration', value), 'set temporary TimerDuration')
            changed = True
            if sdk.integer(handle, 'TimerDuration')['current'] != value:
                raise SdkError('TimerDuration readback mismatch')
        value = sdk.integer(handle, 'TimerDuration')['current']
        print(f'Timer6: длительность {value} мкс ({value / 1000:g} мс).', flush=True)
        if value < 10000:
            print('Импульс может быть незаметен на лампочке. Для проверки: --duration-ms 1000.', flush=True)
        yield
    finally:
        if changed:
            code = sdk.lib.MV_CC_SetIntValueEx(handle, b'TimerDuration', original['current'])
            if code:
                print(f'Could not restore TimerDuration: 0x{code:08x}', file=sys.stderr)


class SpaceTrigger:
    def __init__(self, sdk, handle, timer, debounce_ms, watch=False):
        self.sdk, self.handle, self.timer = sdk, handle, timer
        self.debounce = debounce_ms / 1000
        self.last = None
        self.count = 0
        self.watch = watch

    def key(self, key, now=None):
        if key != ' ':
            return False
        now = time.monotonic() if now is None else now
        if self.last is not None and now - self.last < self.debounce:
            return False
        old_line = None
        try:
            if self.watch:
                old_line = self.sdk.enum(self.handle, 'LineSelector')['current']
                self.sdk.set_enum(self.handle, 'LineSelector', 'Out6')
                print(f'DO6 LineStatus до команды: {int(self.sdk.boolean(self.handle, "LineStatus"))}', flush=True)
            # LineSelector may invalidate the timer selector; select Timer6 again immediately before firing.
            self.sdk.set_enum(self.handle, 'TimerSelector', self.timer)
            if self.sdk.enum(self.handle, 'TimerTriggerSource')['current'] != 'Software':
                raise SdkError(f'{self.timer} is no longer configured for Software')
            check(self.sdk.lib.MV_CC_SetCommandValue(self.handle, b'TriggerSoftware'),
                  f'execute {self.timer}')
            self.last = now
            self.count += 1
            print(f'{time.strftime("%H:%M:%S")} {self.timer}: trigger #{self.count} accepted by SDK', flush=True)
            if self.watch:
                started = time.monotonic()
                try:
                    for delay in (0, .05, .2, .5):
                        time.sleep(max(0, started + delay - time.monotonic()))
                        state = self.sdk.boolean(self.handle, 'LineStatus')
                        print(f'DO6 LineStatus +{(time.monotonic() - started) * 1000:.0f} мс: {int(state)}', flush=True)
                except SdkError as e:
                    print(f'Команда принята, но прочитать состояние не удалось: {e}', file=sys.stderr)
        finally:
            if old_line is not None:
                self.sdk.set_enum(self.handle, 'LineSelector', old_line)
        return True


def keyboard_loop(trigger, stream=sys.stdin):
    if not stream.isatty():
        raise SdkError('Run in an interactive terminal: stdin is not a TTY')
    fd = stream.fileno()
    old = termios.tcgetattr(fd)
    try:
        tty.setcbreak(fd)  # Space is read immediately; Ctrl+C still generates SIGINT.
        print('Пробел → Timer6 → DO6 (маршрут из MVS). q / Esc / Ctrl+C → выход.\n'
              'При удержании пробела возможны повторные импульсы.', flush=True)
        while True:
            key = stream.read(1)
            if not key or key in ('q', 'Q', '\x1b'):
                return
            trigger.key(key)
    finally:
        termios.tcsetattr(fd, termios.TCSADRAIN, old)


def main():
    parser = argparse.ArgumentParser(description='Пробел → software trigger Timer6 в Linux MVS')
    parser.add_argument('--mvs-root', default=os.getenv('MVS_ROOT', '/opt/MVS'))
    parser.add_argument('--port', help='Exact MVS port name, e.g. COM_Port#ttyS4')
    parser.add_argument('--serial', help='Controller serial number')
    parser.add_argument('--debounce-ms', type=int, default=150, help='Minimum interval between triggers')
    parser.add_argument('--duration-ms', type=int, help='Temporary Timer6 pulse duration in milliseconds; restored on exit')
    parser.add_argument('--watch', action='store_true', help='Read DO6 LineStatus before and after each trigger (up to 500 ms)')
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--list', action='store_true', help='List serial devices without triggering outputs')
    mode.add_argument('--check', action='store_true', help='Open controller and verify Timer6 without firing')
    args = parser.parse_args()
    if args.debounce_ms < 0:
        parser.error('--debounce-ms must be nonnegative')
    if args.duration_ms is not None and args.duration_ms <= 0:
        parser.error('--duration-ms must be positive')
    if args.check and args.duration_ms is not None:
        parser.error('--check cannot change duration; omit --duration-ms')
    sdk = None
    try:
        if not args.list and not args.check and not sys.stdin.isatty():
            raise SdkError('Run in an interactive terminal: stdin is not a TTY')
        sdk = SerialMvs(args.mvs_root)
        if args.list:
            print(json.dumps([meta for meta, _ in sdk.devices(args.port)], ensure_ascii=False, indent=2))
            return 0
        with sdk.controller(args.port, args.serial) as (handle, meta):
            print(json.dumps(meta, ensure_ascii=False), flush=True)
            with selected_timer(sdk, handle, 'Timer6'):
                verify_output(sdk, handle)
                print('Timer6: Software; TriggerSoftware доступен. Маршрут DO6 не изменён.', flush=True)
                with timer_duration(sdk, handle, args.duration_ms):
                    if not args.check:
                        keyboard_loop(SpaceTrigger(sdk, handle, 'Timer6', args.debounce_ms, args.watch))
        return 0
    except KeyboardInterrupt:
        print('\nОстановлено.')
        return 0
    except (SdkError, OSError, AttributeError, ImportError) as e:
        print(f'Ошибка: {e}', file=sys.stderr)
        return 2
    finally:
        if sdk:
            sdk.close()


if __name__ == '__main__':
    raise SystemExit(main())

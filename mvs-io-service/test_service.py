import ctypes
import unittest
from unittest.mock import Mock

from service import Mvs, SdkError


class FakeMvs(Mvs):
    def __init__(self):
        self.values = {
            'TimerSelector': {'current': 'Timer1', 'supported': ['Timer1', 'Timer6']},
            'LineSelector': {'current': 'DO1', 'supported': ['DO1', 'DO6']},
            'TimerTriggerSource': {'current': 'Software', 'supported': ['Software', 'Line1']},
            'LineMode': {'current': 'Output', 'supported': ['Output']},
            'LineSource': {'current': 'Timer6Active', 'supported': ['Timer6Active', 'UserOutput']},
        }
        self.lib = Mock()
        self.lib.MV_CC_SetCommandValue.return_value = 0
        self.lib.MV_CC_SetIntValueEx.return_value = 0
        self.lib.MV_CC_SetEnumValueByString.side_effect = self.write_enum
        self.lib.MV_XML_GetNodeAccessMode.side_effect = self.access
        self.duration = 50
        self.access_mode = 2

    def write_enum(self, handle, key, value):
        self.values[key.decode()]['current'] = value.decode()
        return 0

    def access(self, handle, key, mode):
        ctypes.cast(mode, ctypes.POINTER(ctypes.c_int))[0] = self.access_mode
        return 0

    def with_interface(self, interface_id, action):
        if not interface_id:
            raise SdkError('Specify interface ID')
        return action(ctypes.c_void_p(1))

    def enum(self, handle, key):
        return self.values[key]

    def integer(self, handle, key):
        return {'current': self.duration, 'min': 1, 'max': 100, 'increment': 1}


class PulseTests(unittest.TestCase):
    def test_missing_interface_does_not_fire(self):
        sdk = FakeMvs()
        with self.assertRaises(SdkError):
            sdk.pulse(None, 'DO6')
        sdk.lib.MV_CC_SetCommandValue.assert_not_called()

    def test_unknown_output_does_not_fire_or_guess_mapping(self):
        sdk = FakeMvs()
        with self.assertRaises(SdkError):
            sdk.pulse('controller', 'Line6')
        sdk.lib.MV_CC_SetCommandValue.assert_not_called()
        self.assertEqual(sdk.values['TimerSelector']['current'], 'Timer1')

    def test_wrong_route_does_not_fire_without_configure(self):
        sdk = FakeMvs()
        sdk.values['LineSource']['current'] = 'UserOutput'
        with self.assertRaises(SdkError):
            sdk.pulse('controller', 'DO6')
        sdk.lib.MV_CC_SetCommandValue.assert_not_called()

    def test_readonly_command_does_not_fire(self):
        sdk = FakeMvs()
        sdk.access_mode = 3
        with self.assertRaises(SdkError):
            sdk.pulse('controller', 'DO6')
        sdk.lib.MV_CC_SetCommandValue.assert_not_called()

    def test_configure_and_fire_once_restore_selectors(self):
        sdk = FakeMvs()
        sdk.values['LineSource']['current'] = 'UserOutput'
        sdk.values['TimerTriggerSource']['current'] = 'Line1'
        result = sdk.pulse('controller', 'DO6', configure=True)
        sdk.lib.MV_CC_SetCommandValue.assert_called_once()
        self.assertEqual(sdk.lib.MV_CC_SetCommandValue.call_args.args[1], b'TimerTriggerSoftware')
        self.assertFalse(result['physical_signal_verified'])
        self.assertEqual(sdk.values['LineSource']['current'], 'Timer6Active')
        self.assertEqual(sdk.values['LineSelector']['current'], 'DO1')
        self.assertEqual(sdk.values['TimerSelector']['current'], 'Timer1')

    def test_invalid_duration_does_not_change_route_or_fire(self):
        sdk = FakeMvs()
        sdk.values['LineSource']['current'] = 'UserOutput'
        with self.assertRaises(SdkError):
            sdk.pulse('controller', 'DO6', configure=True, duration_native=101)
        self.assertEqual(sdk.values['LineSource']['current'], 'UserOutput')
        sdk.lib.MV_CC_SetCommandValue.assert_not_called()

    def test_sdk_failure_is_reported_and_selectors_restored(self):
        sdk = FakeMvs()
        sdk.lib.MV_CC_SetCommandValue.return_value = 0x80000004
        with self.assertRaisesRegex(SdkError, '0x80000004'):
            sdk.pulse('controller', 'DO6')
        self.assertEqual(sdk.values['TimerSelector']['current'], 'Timer1')


if __name__ == '__main__':
    unittest.main()

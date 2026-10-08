import os
import termios
import unittest
from unittest.mock import Mock

from keyboard import SpaceTrigger, keyboard_loop, selected_timer, timer_duration, verify_output
from service import SdkError
from test_service import FakeMvs


class KeyboardTests(unittest.TestCase):
    def test_temporary_duration_restored_on_failure(self):
        sdk = FakeMvs()
        def write(handle, key, value):
            sdk.duration = value
            return 0
        sdk.lib.MV_CC_SetIntValueEx.side_effect = write
        sdk.integer = lambda h, k: {'current': sdk.duration, 'min': 1, 'max': 30000000, 'increment': 1}
        with self.assertRaises(KeyboardInterrupt):
            with timer_duration(sdk, None, 1000):
                self.assertEqual(sdk.duration, 1000000)
                raise KeyboardInterrupt
        self.assertEqual(sdk.duration, 50)
        sdk.lib.MV_CC_SetCommandValue.assert_not_called()

    def test_bad_duration_is_rejected_before_write(self):
        sdk = FakeMvs()
        with self.assertRaises(SdkError):
            with timer_duration(sdk, None, 1000):
                self.fail('Must reject duration outside device limits')
        sdk.lib.MV_CC_SetIntValueEx.assert_not_called()

    def test_wrong_output_route_is_rejected_and_selector_restored(self):
        sdk = FakeMvs()
        sdk.values['LineSelector']['supported'].append('Out6')
        sdk.values['LinePolarity'] = {'current': 'PNP', 'supported': ['PNP', 'NPN']}
        with self.assertRaises(SdkError):
            verify_output(sdk, None)
        self.assertEqual(sdk.values['LineSelector']['current'], 'DO1')
        sdk.lib.MV_CC_SetCommandValue.assert_not_called()

    def test_space_sends_exact_controller_command(self):
        sdk = FakeMvs()
        sdk.values['TimerSelector']['supported'].append('Timer6')
        trigger = SpaceTrigger(sdk, None, 'Timer6', 150)
        self.assertFalse(trigger.key('a', now=0))
        self.assertTrue(trigger.key(' ', now=0))
        self.assertFalse(trigger.key(' ', now=0.1))
        self.assertTrue(trigger.key(' ', now=0.2))
        self.assertEqual(sdk.lib.MV_CC_SetCommandValue.call_count, 2)
        sdk.lib.MV_CC_SetCommandValue.assert_called_with(None, b'TriggerSoftware')

    def test_failed_trigger_does_not_count_success(self):
        sdk = FakeMvs()
        sdk.values['TimerSelector']['supported'].append('Timer6')
        sdk.lib.MV_CC_SetCommandValue.return_value = 0x80000109
        trigger = SpaceTrigger(sdk, None, 'Timer6', 150)
        with self.assertRaises(SdkError):
            trigger.key(' ')
        self.assertEqual(trigger.count, 0)

    def test_changed_timer_source_blocks_trigger(self):
        sdk = FakeMvs()
        sdk.values['TimerSelector']['supported'].append('Timer6')
        sdk.values['TimerTriggerSource']['current'] = 'Line1'
        with self.assertRaises(SdkError):
            SpaceTrigger(sdk, None, 'Timer6', 150).key(' ')
        sdk.lib.MV_CC_SetCommandValue.assert_not_called()

    def test_watch_reads_output_and_restores_selector(self):
        from unittest.mock import patch
        sdk = FakeMvs()
        sdk.values['TimerSelector']['supported'].append('Timer6')
        sdk.values['LineSelector']['supported'].append('Out6')
        sdk.boolean = Mock(side_effect=[False, True, True, True, True])
        with patch('keyboard.time.sleep'):
            SpaceTrigger(sdk, None, 'Timer6', 150, watch=True).key(' ')
        self.assertEqual(sdk.boolean.call_count, 5)
        self.assertEqual(sdk.values['LineSelector']['current'], 'DO1')
        sdk.lib.MV_CC_SetCommandValue.assert_called_once()

    def test_setup_does_not_fire_or_change_output_route(self):
        sdk = FakeMvs()
        sdk.values['TimerSelector']['supported'].append('Timer6')
        with selected_timer(sdk, None, 'Timer6'):
            self.assertEqual(sdk.values['TimerSelector']['current'], 'Timer6')
        self.assertEqual(sdk.values['TimerSelector']['current'], 'Timer1')
        keys = [call.args[1] for call in sdk.lib.MV_CC_SetEnumValueByString.call_args_list]
        self.assertEqual(keys, [b'TimerSelector', b'TimerSelector'])
        sdk.lib.MV_CC_SetCommandValue.assert_not_called()

    def test_wrong_source_is_rejected_without_firing(self):
        sdk = FakeMvs()
        sdk.values['TimerSelector']['supported'].append('Timer6')
        sdk.values['TimerTriggerSource']['current'] = 'Line1'
        with self.assertRaises(SdkError):
            with selected_timer(sdk, None, 'Timer6'):
                self.fail('Must reject hardware source')
        self.assertEqual(sdk.values['TimerSelector']['current'], 'Timer1')
        sdk.lib.MV_CC_SetCommandValue.assert_not_called()

    def test_terminal_restored_on_interrupt(self):
        master, slave = os.openpty()
        try:
            original = termios.tcgetattr(slave)
            stream = Mock()
            stream.isatty.return_value = True
            stream.fileno.return_value = slave
            stream.read.side_effect = KeyboardInterrupt
            with self.assertRaises(KeyboardInterrupt):
                keyboard_loop(Mock(), stream)
            self.assertEqual(termios.tcgetattr(slave), original)
        finally:
            os.close(master)
            os.close(slave)


if __name__ == '__main__':
    unittest.main()

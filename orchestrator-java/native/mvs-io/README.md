# Vendor IO SDK libraries

`win64/MvIOInterfaceBox.dll` and `win64/MvSerial.dll` are the existing
Hikrobot IO SDK binaries previously used by the C# IO monitor, copied here
so the orchestrator has no runtime dependency on that project directory.
They are vendor binaries, not Java code; their existing vendor license applies.
Use a 64-bit Windows JVM and the installed vendor runtime dependencies.
For Linux, obtain a compatible vendor IO SDK shared library and validate its
ABI on the controller. Windows DLLs cannot be loaded by a Linux JVM.
Configure paths and the COM port under `integration.inspection_trigger.mvs_io`.

# CMake generated Testfile for 
# Source directory: D:/visionGay/defect-detector-v3/camera-worker
# Build directory: D:/visionGay/defect-detector-v3/camera-worker/build-test
# 
# This file includes the relevant testing commands required for 
# testing this directory and lists subdirectories to be tested as well.
if(CTEST_CONFIGURATION_TYPE MATCHES "^([Dd][Ee][Bb][Uu][Gg])$")
  add_test(cw_config_tests "D:/visionGay/defect-detector-v3/camera-worker/build-test/Debug/cw_config_tests.exe")
  set_tests_properties(cw_config_tests PROPERTIES  _BACKTRACE_TRIPLES "D:/visionGay/defect-detector-v3/camera-worker/CMakeLists.txt;52;add_test;D:/visionGay/defect-detector-v3/camera-worker/CMakeLists.txt;0;")
elseif(CTEST_CONFIGURATION_TYPE MATCHES "^([Rr][Ee][Ll][Ee][Aa][Ss][Ee])$")
  add_test(cw_config_tests "D:/visionGay/defect-detector-v3/camera-worker/build-test/Release/cw_config_tests.exe")
  set_tests_properties(cw_config_tests PROPERTIES  _BACKTRACE_TRIPLES "D:/visionGay/defect-detector-v3/camera-worker/CMakeLists.txt;52;add_test;D:/visionGay/defect-detector-v3/camera-worker/CMakeLists.txt;0;")
elseif(CTEST_CONFIGURATION_TYPE MATCHES "^([Mm][Ii][Nn][Ss][Ii][Zz][Ee][Rr][Ee][Ll])$")
  add_test(cw_config_tests "D:/visionGay/defect-detector-v3/camera-worker/build-test/MinSizeRel/cw_config_tests.exe")
  set_tests_properties(cw_config_tests PROPERTIES  _BACKTRACE_TRIPLES "D:/visionGay/defect-detector-v3/camera-worker/CMakeLists.txt;52;add_test;D:/visionGay/defect-detector-v3/camera-worker/CMakeLists.txt;0;")
elseif(CTEST_CONFIGURATION_TYPE MATCHES "^([Rr][Ee][Ll][Ww][Ii][Tt][Hh][Dd][Ee][Bb][Ii][Nn][Ff][Oo])$")
  add_test(cw_config_tests "D:/visionGay/defect-detector-v3/camera-worker/build-test/RelWithDebInfo/cw_config_tests.exe")
  set_tests_properties(cw_config_tests PROPERTIES  _BACKTRACE_TRIPLES "D:/visionGay/defect-detector-v3/camera-worker/CMakeLists.txt;52;add_test;D:/visionGay/defect-detector-v3/camera-worker/CMakeLists.txt;0;")
else()
  add_test(cw_config_tests NOT_AVAILABLE)
endif()

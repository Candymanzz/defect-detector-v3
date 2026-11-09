cmake_minimum_required(VERSION 3.25)
project(CppAnalisSurface LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
  set(CMAKE_BUILD_TYPE Release)
endif()

find_package(OpenCV REQUIRED COMPONENTS core imgproc imgcodecs video)
find_package(httplib CONFIG QUIET)
if(NOT TARGET httplib::httplib)
  # Ubuntu/Debian ship cpp-httplib through pkg-config rather than a CMake config.
  find_package(PkgConfig REQUIRED)
  pkg_check_modules(HTTPLIB REQUIRED IMPORTED_TARGET cpp-httplib>=0.14)
  add_library(httplib::httplib ALIAS PkgConfig::HTTPLIB)
endif()
find_package(nlohmann_json CONFIG REQUIRED)
find_package(Threads REQUIRED)

add_executable(cpp_analis_surface
  src/main.cpp
  src/core/settings.cpp
  src/core/reference.cpp
  src/core/inspector.cpp
  src/core/learning_matcher.cpp
  src/core/seam.cpp
  src/core/logger.cpp
  src/core/performance_log.cpp
  src/core/shm_io.cpp
  src/service/service.cpp
  src/web/routes.cpp)
target_include_directories(cpp_analis_surface PRIVATE src ${OpenCV_INCLUDE_DIRS})
if(MSVC)
  target_compile_options(cpp_analis_surface PRIVATE /utf-8 /W3)
  target_compile_definitions(cpp_analis_surface PRIVATE _CRT_SECURE_NO_WARNINGS)
else()
  target_compile_options(cpp_analis_surface PRIVATE -Wall -Wextra -O2)
endif()
target_link_libraries(cpp_analis_surface PRIVATE
  opencv_core opencv_imgproc opencv_video opencv_imgcodecs
  httplib::httplib nlohmann_json::nlohmann_json Threads::Threads)

include(CTest)
if(BUILD_TESTING)
  add_executable(settings_store_test tests/settings_store_test.cpp src/core/settings.cpp)
  target_include_directories(settings_store_test PRIVATE src ${OpenCV_INCLUDE_DIRS})
  target_link_libraries(settings_store_test PRIVATE opencv_core nlohmann_json::nlohmann_json Threads::Threads)
  if(MSVC)
    target_compile_options(settings_store_test PRIVATE /utf-8)
  endif()
  add_test(NAME settings_store_test COMMAND settings_store_test "${CMAKE_CURRENT_BINARY_DIR}/settings-store-test-data")
  add_executable(label_roi_test tests/label_roi_test.cpp
    src/core/settings.cpp src/core/reference.cpp src/core/inspector.cpp src/core/learning_matcher.cpp)
  target_include_directories(label_roi_test PRIVATE src ${OpenCV_INCLUDE_DIRS})
  target_link_libraries(label_roi_test PRIVATE
    opencv_core opencv_imgproc opencv_video nlohmann_json::nlohmann_json Threads::Threads)
  add_test(NAME label_roi_test COMMAND label_roi_test)
  add_executable(alignment_preprocessing_test tests/alignment_preprocessing_test.cpp
    src/core/settings.cpp src/core/reference.cpp src/core/inspector.cpp src/core/learning_matcher.cpp)
  target_include_directories(alignment_preprocessing_test PRIVATE src ${OpenCV_INCLUDE_DIRS})
  target_link_libraries(alignment_preprocessing_test PRIVATE
    opencv_core opencv_imgproc opencv_video nlohmann_json::nlohmann_json Threads::Threads)
  add_test(NAME alignment_preprocessing_test COMMAND alignment_preprocessing_test)
  add_executable(shift_accuracy_test tests/shift_accuracy_test.cpp
    src/core/settings.cpp src/core/reference.cpp src/core/inspector.cpp src/core/learning_matcher.cpp)
  target_include_directories(shift_accuracy_test PRIVATE src ${OpenCV_INCLUDE_DIRS})
  target_link_libraries(shift_accuracy_test PRIVATE
    opencv_core opencv_imgproc opencv_video nlohmann_json::nlohmann_json Threads::Threads)
  add_test(NAME shift_accuracy_test COMMAND shift_accuracy_test)
  add_executable(preprocessing_test tests/preprocessing_test.cpp
    src/core/settings.cpp src/core/reference.cpp src/core/inspector.cpp src/core/learning_matcher.cpp)
  target_include_directories(preprocessing_test PRIVATE src ${OpenCV_INCLUDE_DIRS})
  target_link_libraries(preprocessing_test PRIVATE
    opencv_core opencv_imgproc opencv_video nlohmann_json::nlohmann_json Threads::Threads)
  add_test(NAME preprocessing_test COMMAND preprocessing_test)
  add_executable(learned_placement_test tests/learned_placement_test.cpp
    src/core/settings.cpp src/core/reference.cpp src/core/inspector.cpp src/core/learning_matcher.cpp)
  target_include_directories(learned_placement_test PRIVATE src ${OpenCV_INCLUDE_DIRS})
  target_link_libraries(learned_placement_test PRIVATE
    opencv_core opencv_imgproc opencv_video nlohmann_json::nlohmann_json Threads::Threads)
  add_test(NAME learned_placement_test COMMAND learned_placement_test)
  add_executable(moving_shadow_test tests/moving_shadow_test.cpp
    src/core/settings.cpp src/core/reference.cpp src/core/inspector.cpp src/core/learning_matcher.cpp)
  target_include_directories(moving_shadow_test PRIVATE src ${OpenCV_INCLUDE_DIRS})
  target_link_libraries(moving_shadow_test PRIVATE opencv_core opencv_imgproc opencv_video opencv_imgcodecs nlohmann_json::nlohmann_json Threads::Threads)
  add_test(NAME moving_shadow_test COMMAND moving_shadow_test)
  add_executable(learned_region_test tests/learned_region_test.cpp
    src/core/settings.cpp src/core/reference.cpp src/core/inspector.cpp src/core/learning_matcher.cpp src/core/seam.cpp
    src/core/shm_io.cpp src/service/service.cpp)
  target_include_directories(learned_region_test PRIVATE src ${OpenCV_INCLUDE_DIRS})
  target_link_libraries(learned_region_test PRIVATE
    opencv_core opencv_imgproc opencv_video opencv_imgcodecs nlohmann_json::nlohmann_json Threads::Threads)
  add_test(NAME learned_region_test COMMAND learned_region_test)
  add_executable(shift_tolerance_test tests/shift_tolerance_test.cpp
    src/core/settings.cpp src/core/reference.cpp src/core/inspector.cpp src/core/learning_matcher.cpp)
  target_include_directories(shift_tolerance_test PRIVATE src)
  target_link_libraries(shift_tolerance_test PRIVATE
    opencv_core opencv_imgproc opencv_video nlohmann_json::nlohmann_json Threads::Threads)
  add_test(NAME shift_tolerance_test COMMAND shift_tolerance_test)
  add_executable(performance_log_test tests/performance_log_test.cpp src/core/performance_log.cpp)
  target_include_directories(performance_log_test PRIVATE src)
  target_link_libraries(performance_log_test PRIVATE nlohmann_json::nlohmann_json Threads::Threads)
  add_test(NAME performance_log_test COMMAND performance_log_test)
  add_executable(performance_http_test tests/performance_http_test.cpp src/core/performance_log.cpp)
  target_include_directories(performance_http_test PRIVATE src)
  target_link_libraries(performance_http_test PRIVATE httplib::httplib nlohmann_json::nlohmann_json Threads::Threads)
  if(MINGW)
    target_link_libraries(performance_http_test PRIVATE ws2_32 crypt32)
  endif()
  add_test(NAME performance_http_test COMMAND performance_http_test)
  add_executable(metric_tolerance_test tests/metric_tolerance_test.cpp
    src/core/settings.cpp src/core/reference.cpp src/core/inspector.cpp src/core/learning_matcher.cpp src/core/seam.cpp
    src/core/shm_io.cpp src/service/service.cpp)
  target_include_directories(metric_tolerance_test PRIVATE src ${OpenCV_INCLUDE_DIRS})
  target_link_libraries(metric_tolerance_test PRIVATE
    opencv_core opencv_imgproc opencv_video opencv_imgcodecs nlohmann_json::nlohmann_json Threads::Threads)
  add_test(NAME metric_tolerance_test COMMAND metric_tolerance_test)
  add_executable(upper_corner_test tests/upper_corner_test.cpp
    src/core/settings.cpp src/core/reference.cpp src/core/inspector.cpp src/core/learning_matcher.cpp)
  target_include_directories(upper_corner_test PRIVATE src ${OpenCV_INCLUDE_DIRS})
  target_link_libraries(upper_corner_test PRIVATE
    opencv_core opencv_imgproc opencv_video nlohmann_json::nlohmann_json Threads::Threads)
  if(MSVC)
    target_compile_options(upper_corner_test PRIVATE /utf-8)
  endif()
  add_test(NAME upper_corner_test COMMAND upper_corner_test)
endif()

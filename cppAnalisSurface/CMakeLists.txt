cmake_minimum_required(VERSION 3.25)
project(CppAnalisSurface LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
  set(CMAKE_BUILD_TYPE Release)
endif()

find_package(OpenCV REQUIRED COMPONENTS core imgproc imgcodecs)
find_package(httplib CONFIG REQUIRED)
find_package(nlohmann_json CONFIG REQUIRED)
find_package(Threads REQUIRED)

add_executable(cpp_analis_surface
  src/main.cpp
  src/core/settings.cpp
  src/core/reference.cpp
  src/core/inspector.cpp
  src/core/seam.cpp
  src/core/logger.cpp
  src/core/shm_io.cpp
  src/service/service.cpp
  src/web/routes.cpp)
target_include_directories(cpp_analis_surface PRIVATE src)
if(MSVC)
  target_compile_options(cpp_analis_surface PRIVATE /utf-8 /W3)
  target_compile_definitions(cpp_analis_surface PRIVATE _CRT_SECURE_NO_WARNINGS)
else()
  target_compile_options(cpp_analis_surface PRIVATE -Wall -Wextra -O2)
endif()
target_link_libraries(cpp_analis_surface PRIVATE
  opencv_core opencv_imgproc opencv_imgcodecs
  httplib::httplib nlohmann_json::nlohmann_json Threads::Threads)

cmake_minimum_required(VERSION 3.16)
project(camera_worker C)

set(CMAKE_C_STANDARD 11)

find_package(PkgConfig QUIET)

add_executable(camera_worker
        src/main.c
)

target_include_directories(camera_worker PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/deps"
)

if (PkgConfig_FOUND)
    pkg_check_modules(ARAVIS QUIET arv-0.8)
    if (ARAVIS_FOUND)
        target_compile_definitions(camera_worker PRIVATE HAVE_ARAVIS=1)
        target_include_directories(camera_worker PRIVATE ${ARAVIS_INCLUDE_DIRS})
        target_link_libraries(camera_worker PRIVATE ${ARAVIS_LIBRARIES})
        target_link_directories(camera_worker PRIVATE ${ARAVIS_LIBRARY_DIRS})
    endif ()
endif ()

if (MSVC)
    target_compile_definitions(camera_worker PRIVATE _CRT_SECURE_NO_WARNINGS)
endif ()

# Hikvision MVS SDK (Windows x64): install default path, or set -DMVS_ROOT=... at configure time.
if (WIN32)
    if (NOT MVS_ROOT)
        set(MVS_ROOT "C:/Program Files (x86)/MVS/Development")
    endif ()
    if (EXISTS "${MVS_ROOT}/Includes/MvCameraControl.h" AND EXISTS "${MVS_ROOT}/Libraries/win64/MvCameraControl.lib")
        message(STATUS "camera-worker: HAVE_HIK_MVS (MVS_ROOT=${MVS_ROOT})")
        target_compile_definitions(camera_worker PRIVATE HAVE_HIK_MVS=1)
        target_include_directories(camera_worker PRIVATE "${MVS_ROOT}/Includes")
        target_link_libraries(camera_worker PRIVATE "${MVS_ROOT}/Libraries/win64/MvCameraControl.lib")
    else ()
        message(STATUS "camera-worker: MVS SDK not found at ${MVS_ROOT}; capture_source=hik will require rebuilding after SDK install")
    endif ()
endif ()

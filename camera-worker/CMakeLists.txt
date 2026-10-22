cmake_minimum_required(VERSION 3.16)
project(camera_worker C)

set(CMAKE_C_STANDARD 11)

find_package(PkgConfig QUIET)
option(CAMERA_BUILD_JNI "Build the in-process orchestrator camera library" ON)
option(CAMERA_ENABLE_MVS "Enable installed Hikvision MVS SDK" ON)
option(CAMERA_ENABLE_ARAVIS "Enable installed Aravis SDK" ON)

add_executable(camera_worker
        src/main.c
        src/cw_config.c
)

set(CAMERA_TARGETS camera_worker)
if (CAMERA_BUILD_JNI)
    if (CMAKE_VERSION VERSION_LESS 3.24)
        find_package(JNI REQUIRED)
    else ()
        find_package(JNI REQUIRED COMPONENTS JVM)
    endif ()
    add_library(camera_jni SHARED src/main.c src/cw_config.c src/camera_jni.c)
    target_compile_definitions(camera_jni PRIVATE CW_EMBEDDED=1)
    target_include_directories(camera_jni PRIVATE ${JNI_INCLUDE_DIRS})
    if (WIN32)
        set_target_properties(camera_jni PROPERTIES PREFIX "")
    endif ()
    list(APPEND CAMERA_TARGETS camera_jni)
endif ()

foreach (camera_target IN LISTS CAMERA_TARGETS)
    target_include_directories(${camera_target} PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/deps"
            "${CMAKE_CURRENT_SOURCE_DIR}/include"
    )

    if (PkgConfig_FOUND AND CAMERA_ENABLE_ARAVIS)
        pkg_check_modules(ARAVIS QUIET arv-0.8)
        if (ARAVIS_FOUND)
            target_compile_definitions(${camera_target} PRIVATE HAVE_ARAVIS=1)
            target_include_directories(${camera_target} PRIVATE ${ARAVIS_INCLUDE_DIRS})
            target_link_libraries(${camera_target} PRIVATE ${ARAVIS_LIBRARIES})
            target_link_directories(${camera_target} PRIVATE ${ARAVIS_LIBRARY_DIRS})
        endif ()
    endif ()

    if (MSVC)
        target_compile_definitions(${camera_target} PRIVATE _CRT_SECURE_NO_WARNINGS)
    endif ()

    # Hikvision MVS SDK (Windows x64): install default path, or set -DMVS_ROOT=... at configure time.
    if (WIN32)
        if (NOT MVS_ROOT)
            set(MVS_ROOT "C:/Program Files (x86)/MVS/Development")
        endif ()
        if (CAMERA_ENABLE_MVS AND EXISTS "${MVS_ROOT}/Includes/MvCameraControl.h" AND EXISTS "${MVS_ROOT}/Libraries/win64/MvCameraControl.lib")
            message(STATUS "camera-worker: HAVE_HIK_MVS (MVS_ROOT=${MVS_ROOT})")
            target_compile_definitions(${camera_target} PRIVATE HAVE_HIK_MVS=1)
            target_include_directories(${camera_target} PRIVATE "${MVS_ROOT}/Includes")
            target_link_libraries(${camera_target} PRIVATE "${MVS_ROOT}/Libraries/win64/MvCameraControl.lib")
        endif ()

        # Runtime DLLs must sit next to the executable/JNI library.
        set(_MVS_RUNTIME_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../orchestrator-java/native/mvs/win64")
        if (CAMERA_ENABLE_MVS AND EXISTS "${MVS_ROOT}/Includes/MvCameraControl.h" AND EXISTS "${_MVS_RUNTIME_DIR}/MvCameraControl.dll")
            add_custom_command(TARGET ${camera_target} POST_BUILD
                COMMAND ${CMAKE_COMMAND} -E copy_directory
                    "${_MVS_RUNTIME_DIR}"
                    "$<TARGET_FILE_DIR:${camera_target}>"
                COMMENT "Copy MVS Runtime/win64 DLLs next to ${camera_target}"
            )
        elseif (CAMERA_ENABLE_MVS)
            message(STATUS "camera-worker: MVS SDK/runtime not found; hik capture unavailable")
        endif ()
    endif ()

    if (UNIX)
        find_package(Threads REQUIRED)
        target_link_libraries(${camera_target} PRIVATE Threads::Threads ${CMAKE_DL_LIBS})
        if (NOT MVS_ROOT)
            set(MVS_ROOT "/opt/MVS")
        endif ()
        if (CAMERA_ENABLE_MVS AND EXISTS "${MVS_ROOT}/include/MvCameraControl.h" AND EXISTS "${MVS_ROOT}/lib/64/libMvCameraControl.so")
            message(STATUS "camera-worker: HAVE_HIK_MVS (Linux MVS_ROOT=${MVS_ROOT})")
            target_compile_definitions(${camera_target} PRIVATE HAVE_HIK_MVS=1)
            target_include_directories(${camera_target} PRIVATE "${MVS_ROOT}/include")
            target_link_libraries(${camera_target} PRIVATE "${MVS_ROOT}/lib/64/libMvCameraControl.so")
            set_target_properties(${camera_target} PROPERTIES BUILD_RPATH "${MVS_ROOT}/lib/64")
        endif ()
    endif ()
endforeach ()

enable_testing()

add_executable(cw_config_tests
        tests/test_cw_config.c
        src/cw_config.c
)
target_include_directories(cw_config_tests PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/include")
add_test(NAME cw_config_tests COMMAND cw_config_tests)

add_executable(cw_roi_tests tests/test_cw_roi.c)
target_include_directories(cw_roi_tests PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/include")
add_test(NAME cw_roi_tests COMMAND cw_roi_tests)

if(UNIX AND CAMERA_ENABLE_MVS AND EXISTS "${MVS_ROOT}/include/MvCameraControl.h"
        AND EXISTS "${MVS_ROOT}/lib/64/libMvCameraControl.so")
    add_executable(cw_roi_mvs_tests tests/test_cw_roi_mvs.c src/cw_config.c)
    target_compile_definitions(cw_roi_mvs_tests PRIVATE HAVE_HIK_MVS=1)
    target_compile_options(cw_roi_mvs_tests PRIVATE -UNDEBUG)
    target_include_directories(cw_roi_mvs_tests PRIVATE deps include "${MVS_ROOT}/include")
    target_link_libraries(cw_roi_mvs_tests PRIVATE Threads::Threads ${CMAKE_DL_LIBS}
            "${MVS_ROOT}/lib/64/libMvCameraControl.so")
    set_target_properties(cw_roi_mvs_tests PROPERTIES BUILD_RPATH "${MVS_ROOT}/lib/64")
    add_test(NAME cw_roi_mvs_tests COMMAND cw_roi_mvs_tests)
endif()

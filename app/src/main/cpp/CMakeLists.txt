cmake_minimum_required(VERSION 3.22.1)
project(sdr_native LANGUAGES C CXX)

set(LIBUSB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libusb)
set(RTL_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/rtl_sdr_blog)

add_library(usb1 STATIC
    ${LIBUSB_DIR}/libusb/core.c
    ${LIBUSB_DIR}/libusb/descriptor.c
    ${LIBUSB_DIR}/libusb/hotplug.c
    ${LIBUSB_DIR}/libusb/io.c
    ${LIBUSB_DIR}/libusb/sync.c
    ${LIBUSB_DIR}/libusb/strerror.c
    ${LIBUSB_DIR}/libusb/os/linux_usbfs.c
    ${LIBUSB_DIR}/libusb/os/events_posix.c
    ${LIBUSB_DIR}/libusb/os/threads_posix.c
    ${LIBUSB_DIR}/libusb/os/linux_netlink.c
)
target_include_directories(usb1 PUBLIC
    ${LIBUSB_DIR}/libusb
    ${LIBUSB_DIR}/libusb/os
    ${LIBUSB_DIR}/android
)
target_compile_options(usb1 PRIVATE -fvisibility=hidden)

add_library(rtlsdr STATIC
    ${RTL_DIR}/src/librtlsdr.c
    ${RTL_DIR}/src/tuner_e4k.c
    ${RTL_DIR}/src/tuner_fc0012.c
    ${RTL_DIR}/src/tuner_fc0013.c
    ${RTL_DIR}/src/tuner_fc2580.c
    ${RTL_DIR}/src/tuner_r82xx.c
)
target_include_directories(rtlsdr PUBLIC ${RTL_DIR}/include)
target_link_libraries(rtlsdr PUBLIC usb1)

add_library(sdr_native SHARED native_bridge.cpp wfm_demodulator.cpp am_demodulator.cpp)
target_include_directories(sdr_native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../../../build/generated/dsp/cpp)
target_compile_options(sdr_native PRIVATE -O3)
find_library(log_library log)
target_link_libraries(sdr_native PRIVATE rtlsdr ${log_library} m)

set(FTX_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/ft8_lib)
file(GLOB FTX_SOURCES ${FTX_DIR}/ft8/*.c ${FTX_DIR}/fft/*.c)
add_library(ftx STATIC ${FTX_SOURCES} ${FTX_DIR}/common/monitor.c)
target_include_directories(ftx PUBLIC ${FTX_DIR})
target_compile_options(ftx PRIVATE -O3)
target_sources(sdr_native PRIVATE ftx_decoder.cpp)
target_link_libraries(sdr_native PRIVATE ftx)

set(HAMNOISE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/hamnoise)
add_library(hamnoise STATIC ${HAMNOISE_DIR}/src/denoise_audio.c ${HAMNOISE_DIR}/src/denoise_gru.c hamnoise_models.c)
target_include_directories(hamnoise PUBLIC ${HAMNOISE_DIR}/include PRIVATE ${HAMNOISE_DIR}/generated)
target_compile_options(hamnoise PRIVATE -O3)
target_sources(sdr_native PRIVATE hamnoise_bridge.cpp)
target_link_libraries(sdr_native PRIVATE hamnoise)

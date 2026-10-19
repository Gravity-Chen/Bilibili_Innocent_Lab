cmake_minimum_required(VERSION 3.31)
project(innocent_shadowhook_master_probe LANGUAGES C)
add_library(innocent_shadowhook_master_probe SHARED native_master_probe.c)
find_package(shadowhook REQUIRED CONFIG)
target_link_libraries(innocent_shadowhook_master_probe shadowhook::shadowhook dl)
target_compile_features(innocent_shadowhook_master_probe PRIVATE c_std_11)
target_compile_options(innocent_shadowhook_master_probe PRIVATE -Wall -Wextra -Werror -fvisibility=hidden -g0 -O2)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(innocent_shadowhook_master_probe PRIVATE -mno-outline-atomics)
endif()
target_link_options(innocent_shadowhook_master_probe PRIVATE -Wl,--strip-all -Wl,-z,max-page-size=16384)
if(ENTRY_PROBE_COPY)
    set(probe_copy "${ENTRY_PROBE_COPY}/${ANDROID_ABI}/libinnocent_shadowhook_master_probe.so")
    add_custom_command(TARGET innocent_shadowhook_master_probe POST_BUILD
        COMMAND "${CMAKE_COMMAND}" -E make_directory "${ENTRY_PROBE_COPY}/${ANDROID_ABI}"
        COMMAND "${CMAKE_COMMAND}" -E copy_if_different "$<TARGET_FILE:innocent_shadowhook_master_probe>" "${probe_copy}"
        BYPRODUCTS "${probe_copy}" VERBATIM)
endif()

target_link_options(innocent_shadowhook_master_probe PRIVATE "-Wl,-Map,${CMAKE_CURRENT_BINARY_DIR}/native-abi-probe.map")
target_link_options(innocent_shadowhook_master_probe PRIVATE "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.map")
set_property(TARGET innocent_shadowhook_master_probe APPEND PROPERTY LINK_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/exports.map")

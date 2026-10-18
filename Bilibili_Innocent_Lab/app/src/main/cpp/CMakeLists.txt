cmake_minimum_required(VERSION 3.31)
project(innocent_frame_interpolation LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

include(FetchContent)
set(ncnn_archive "https://github.com/Tencent/ncnn/releases/download/20260526/ncnn-20260526-android-vulkan.zip")
if(INNOCENT_NCNN_ARCHIVE)
    set(ncnn_archive "${INNOCENT_NCNN_ARCHIVE}")
endif()
FetchContent_Declare(ncnn_android
    URL "${ncnn_archive}"
    URL_HASH SHA256=26909c92eed35afed4a966b5e9e503fcb0a529691ea3f910ec2c94a4fff52804
    DOWNLOAD_EXTRACT_TIMESTAMP FALSE)
FetchContent_MakeAvailable(ncnn_android)
find_package(ncnn 20260526 EXACT CONFIG REQUIRED NO_DEFAULT_PATH NO_CMAKE_FIND_ROOT_PATH
    PATHS "${ncnn_android_SOURCE_DIR}/${ANDROID_ABI}/lib/cmake/ncnn")

add_subdirectory(player_interpolation)
add_library(innocent_frame_interpolation SHARED frame_jni.cpp)
target_link_libraries(innocent_frame_interpolation PRIVATE innocent_frame_engine)
target_compile_options(innocent_frame_interpolation PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
set_source_files_properties(frame_jni.cpp PROPERTIES COMPILE_OPTIONS -fexceptions)
target_link_options(innocent_frame_interpolation PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--exclude-libs,ALL")

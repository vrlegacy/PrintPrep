cmake_minimum_required(VERSION 3.22)
project(printprep_native C)

# Gutenprint, cross-compiled by native/build-android.sh (done automatically in the GitHub build).
set(GP ${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/prebuilt/${ANDROID_ABI}/install)

add_library(gutenprint STATIC IMPORTED)
set_target_properties(gutenprint PROPERTIES IMPORTED_LOCATION ${GP}/lib/libgutenprint.a)

add_library(printprep_native SHARED gp_jni.c)
target_include_directories(printprep_native PRIVATE ${GP}/include)
target_link_libraries(printprep_native gutenprint log m)

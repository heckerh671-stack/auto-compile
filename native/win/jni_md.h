// Minimal cross-platform jni_md.h so jni.h can be used for a freestanding build on Windows, Linux
// and macOS (the DLL/.so/.dylib is pure computation + JNI; no OS calls but the export attribute).
#pragma once
#if defined(_WIN32)
  #define JNIEXPORT __declspec(dllexport)
  #define JNIIMPORT __declspec(dllimport)
  #define JNICALL __stdcall
#else
  #define JNIEXPORT __attribute__((visibility("default")))
  #define JNIIMPORT
  #define JNICALL
#endif
typedef int jint;
typedef long long jlong;
typedef signed char jbyte;

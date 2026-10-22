#pragma once
#include <cstdint>
using jint=int; using jlong=long long; using jobject=void*; using jclass=void*; using jstring=void*; using jobjectArray=void*; using jmethodID=void*; using jsize=int; using jboolean=unsigned char;
#define JNIEXPORT
#define JNICALL
#define JNI_VERSION_1_6 0x10006
#define JNI_OK 0
struct JNIEnv {
 virtual const char* GetStringUTFChars(jstring,jboolean*)=0;
 virtual void ReleaseStringUTFChars(jstring,const char*)=0;
 virtual jint GetArrayLength(jobjectArray)=0;
 virtual jobject GetObjectArrayElement(jobjectArray,jint)=0;
 virtual jclass FindClass(const char*)=0;
 virtual jint ThrowNew(jclass,const char*)=0;
 virtual jboolean ExceptionCheck()=0;
 virtual void ExceptionClear()=0;
 virtual jclass GetObjectClass(jobject)=0;
 virtual jmethodID GetMethodID(jclass,const char*,const char*)=0;
 virtual jstring NewStringUTF(const char*)=0;
 virtual void CallVoidMethod(jobject,jmethodID,jstring)=0;
 virtual void DeleteLocalRef(jobject)=0;
 virtual jobjectArray NewObjectArray(jsize,jclass,jobject)=0;
 virtual void SetObjectArrayElement(jobjectArray,jsize,jobject)=0;
 virtual jobject NewGlobalRef(jobject)=0;
 virtual void DeleteGlobalRef(jobject)=0;
};
struct JavaVM { virtual jint GetEnv(void**,jint)=0; virtual jint AttachCurrentThread(JNIEnv**,void*)=0; virtual jint DetachCurrentThread()=0; };

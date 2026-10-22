#include "jni.h"
#include "engine_bridge.h"
#include <cassert>
#include <cstdlib>
#include <cstring>
#include <iostream>
static bool failCpp=false;
void* operator new(std::size_t n) { if(failCpp) { failCpp=false; throw std::bad_alloc(); } auto p=std::malloc(n); if(!p)throw std::bad_alloc(); return p; }
void operator delete(void* p) noexcept { std::free(p); }
void operator delete(void* p,std::size_t) noexcept { std::free(p); }
struct Env:JNIEnv {
 bool pending=false,failArray=false,failString=false,failGlobal=false,failClass=false,failUtf=false; int invalid=0,releases=0,locals=0,globals=0,calls=0; std::string thrownClass;
 const char* GetStringUTFChars(jstring s,jboolean*) override { if(pending)invalid++; if(failUtf) { pending=true; return nullptr; } return (const char*)s; }
 void ReleaseStringUTFChars(jstring,const char*) override { releases++; }
 jint GetArrayLength(jobjectArray) override { if(pending)invalid++; return 0; }
 jobject GetObjectArrayElement(jobjectArray,jint) override { if(pending)invalid++; return nullptr; }
 jclass FindClass(const char* s) override { if(pending)invalid++; thrownClass=s; locals++; return (void*)1; }
 jint ThrowNew(jclass,const char*) override { if(pending)invalid++; pending=true; return 0; }
 jboolean ExceptionCheck() override { return pending; } void ExceptionClear() override { pending=false; }
 jclass GetObjectClass(jobject o) override { if(pending||!o)invalid++; if(failClass) { pending=true; return nullptr; } locals++; return (void*)1; }
 jmethodID GetMethodID(jclass c,const char*,const char*) override { if(pending||!c)invalid++; return (void*)1; }
 jstring NewStringUTF(const char*) override { if(pending)invalid++; if(failString) { pending=true; return nullptr; } locals++; return (void*)1; }
 void CallVoidMethod(jobject o,jmethodID m,jstring s) override { if(pending||!o||!m||!s)invalid++; calls++; }
 void DeleteLocalRef(jobject o) override { if(o)locals--; }
 jobjectArray NewObjectArray(jsize,jclass c,jobject) override { if(pending||!c)invalid++; if(failArray) { pending=true; return nullptr; } locals++; return (void*)1; }
 void SetObjectArrayElement(jobjectArray a,jsize,jobject s) override { if(pending||!a||!s)invalid++; }
 jobject NewGlobalRef(jobject o) override { if(pending||!o)invalid++; if(failGlobal) { pending=true; return nullptr; } globals++; return (void*)1; }
 void DeleteGlobalRef(jobject o) override { if(o)globals--; }
};
struct VM:JavaVM { Env* env; VM(Env* e):env(e){} jint GetEnv(void** e,jint)override{*e=env;return JNI_OK;} jint AttachCurrentThread(JNIEnv** e,void*)override{*e=env;return JNI_OK;} jint DetachCurrentThread()override{return 0;} };
#include "jni_under_test.cpp"
int main(int argc,char**argv) {
 assert(argc==2); Env env; VM machine(&env); JNI_OnLoad(&machine,nullptr); std::string name=argv[1]; auto text=(jstring)"fixture";
 if(name=="array"||name=="string") {
   env.failArray=name=="array"; env.failString=name=="string";
   auto result=Java_dev_sakura_xiangqi_engine_NativeBindings_legal(&env,nullptr,text,(void*)1);
   assert(result==nullptr); assert(env.pending); assert(env.invalid==0); assert(env.locals==0);
 } else if(name=="utf") {
   env.failUtf=true; auto p=Java_dev_sakura_xiangqi_engine_NativeBindings_create(&env,nullptr,text,2,128);
   assert(p==0); assert(env.pending); assert(env.invalid==0);
 } else if(name=="global") {
   auto p=Java_dev_sakura_xiangqi_engine_NativeBindings_create(&env,nullptr,text,2,128); assert(p!=0); env.failGlobal=true;
   Java_dev_sakura_xiangqi_engine_NativeBindings_start(&env,nullptr,p,text,(void*)1,1000,(void*)1);
   assert(env.pending); assert(env.invalid==0); assert(Assistant::starts==0); env.pending=false;
   Java_dev_sakura_xiangqi_engine_NativeBindings_destroy(&env,nullptr,p); assert(env.globals==0);
 } else if(name=="class"||name=="callback-string") {
   env.failClass=name=="class"; env.failString=name=="callback-string"; emit((void*)1,"info depth 1");
   assert(env.invalid==0); assert(env.calls==0); assert(env.locals==0);
 } else if(name=="badalloc") {
   Assistant::allocationFailure=true; auto p=Java_dev_sakura_xiangqi_engine_NativeBindings_create(&env,nullptr,text,2,128);
   assert(p==0); assert(env.pending); assert(env.thrownClass=="java/lang/OutOfMemoryError"); assert(env.invalid==0);
 } else if(name=="utf-release") {
   std::string input(100,'x'); failCpp=true; bool caught=false;
   try { (void)string(&env,(jstring)input.c_str()); } catch(const std::bad_alloc&) { caught=true; }
   assert(caught); assert(env.releases==1);
 } else return 2;
 std::cout<<"PASS "<<name<<"\n";
}

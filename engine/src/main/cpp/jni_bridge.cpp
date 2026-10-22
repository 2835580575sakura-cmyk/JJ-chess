#include <jni.h>
#include "engine_bridge.h"
#include <string>
#include <vector>

namespace {
JavaVM* vm=nullptr;
struct Handle { std::unique_ptr<Assistant::Bridge> bridge; jobject callback=nullptr; };
std::string string(JNIEnv* e,jstring s) { const char* p=e->GetStringUTFChars(s,nullptr); if(!p)throw std::bad_alloc(); std::string r(p); e->ReleaseStringUTFChars(s,p); return r; }
std::vector<std::string> strings(JNIEnv* e,jobjectArray a) { std::vector<std::string> r; for(int i=0;i<e->GetArrayLength(a);++i) { auto s=(jstring)e->GetObjectArrayElement(a,i); r.push_back(string(e,s)); e->DeleteLocalRef(s); } return r; }
void fail(JNIEnv* e,const char* text) { e->ThrowNew(e->FindClass("java/lang/IllegalStateException"),text); }
void emit(jobject callback,const std::string& line) {
    JNIEnv* e=nullptr; bool attached=false;
    if(vm->GetEnv((void**)&e,JNI_VERSION_1_6)!=JNI_OK) { if(vm->AttachCurrentThread(&e,nullptr)!=JNI_OK)return; attached=true; }
    jclass c=e->GetObjectClass(callback);
    jmethodID m=e->GetMethodID(c,"onLine","(Ljava/lang/String;)V");
    jstring s=e->NewStringUTF(line.c_str());
    if(m && s) e->CallVoidMethod(callback,m,s);
    if(e->ExceptionCheck())e->ExceptionClear();
    if(s)e->DeleteLocalRef(s); e->DeleteLocalRef(c);
    if(attached)vm->DetachCurrentThread();
}
}
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* v,void*) { vm=v; return JNI_VERSION_1_6; }
extern "C" JNIEXPORT jlong JNICALL Java_dev_sakura_xiangqi_engine_NativeBindings_create(JNIEnv* e,jobject,jstring net,jint threads,jint hash) {
    try { auto h=std::make_unique<Handle>(); h->bridge=std::make_unique<Assistant::Bridge>(string(e,net),threads,hash); return (jlong)h.release(); }
    catch(const std::exception& ex) { fail(e,ex.what()); return 0; }
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_dev_sakura_xiangqi_engine_NativeBindings_legal(JNIEnv* e,jobject,jstring fen,jobjectArray moves) {
    try {
        auto r=Assistant::legal(string(e,fen),strings(e,moves));
        auto array=e->NewObjectArray(r.moves.size()+1,e->FindClass("java/lang/String"),nullptr);
        auto s=e->NewStringUTF(r.fen.c_str()); e->SetObjectArrayElement(array,0,s); e->DeleteLocalRef(s);
        for(size_t i=0;i<r.moves.size();++i) { s=e->NewStringUTF(r.moves[i].c_str()); e->SetObjectArrayElement(array,i+1,s); e->DeleteLocalRef(s); }
        return array;
    } catch(const std::exception& ex) { fail(e,ex.what()); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL Java_dev_sakura_xiangqi_engine_NativeBindings_start(JNIEnv* e,jobject,jlong pointer,jstring fen,jobjectArray moves,jint ms,jobject callback) {
    try {
        auto h=(Handle*)pointer;
        h->bridge->await();
        if(h->callback)e->DeleteGlobalRef(h->callback);
        h->callback=e->NewGlobalRef(callback);
        jobject captured=h->callback;
        h->bridge->start(string(e,fen),strings(e,moves),ms,[captured](const std::string& line) { emit(captured,line); });
    } catch(const std::exception& ex) { fail(e,ex.what()); }
}
extern "C" JNIEXPORT void JNICALL Java_dev_sakura_xiangqi_engine_NativeBindings_stop(JNIEnv*,jobject,jlong p) { if(p)((Handle*)p)->bridge->stop(); }
extern "C" JNIEXPORT void JNICALL Java_dev_sakura_xiangqi_engine_NativeBindings_await(JNIEnv*,jobject,jlong p) { if(p)((Handle*)p)->bridge->await(); }
extern "C" JNIEXPORT void JNICALL Java_dev_sakura_xiangqi_engine_NativeBindings_configure(JNIEnv* e,jobject,jlong p,jint threads,jint hash) { try { ((Handle*)p)->bridge->configure(threads,hash); } catch(const std::exception& ex) { fail(e,ex.what()); } }
extern "C" JNIEXPORT void JNICALL Java_dev_sakura_xiangqi_engine_NativeBindings_destroy(JNIEnv* e,jobject,jlong p) {
    auto h=(Handle*)p; if(!h)return; h->bridge->stop(); h->bridge->await(); if(h->callback)e->DeleteGlobalRef(h->callback); delete h;
}

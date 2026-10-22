#include <jni.h>
#include "engine_bridge.h"
#include <string>
#include <vector>
#include <stdexcept>

namespace {
JavaVM* vm=nullptr;
struct PendingJavaException {};
struct Handle { std::unique_ptr<Assistant::Bridge> bridge; jobject callback=nullptr; };
void check(JNIEnv* e) { if(e->ExceptionCheck())throw PendingJavaException{}; }
template<class T> struct LocalRef {
    JNIEnv* env; T value;
    LocalRef(JNIEnv* e,T v):env(e),value(v) {}
    ~LocalRef() { if(value)env->DeleteLocalRef(value); }
    T checked() { check(env); if(!value)throw std::bad_alloc(); return value; }
    T release() { T result=value; value=nullptr; return result; }
};
struct GlobalRef {
    JNIEnv* env; jobject value;
    ~GlobalRef() { if(value)env->DeleteGlobalRef(value); }
    jobject checked() { check(env); if(!value)throw std::bad_alloc(); return value; }
    jobject release() { auto result=value; value=nullptr; return result; }
};
struct UtfChars {
    JNIEnv* env; jstring source; const char* value;
    UtfChars(JNIEnv* e,jstring s):env(e),source(s),value(nullptr) {
        if(!s)throw std::invalid_argument("Missing string");
        value=e->GetStringUTFChars(s,nullptr);
        check(e); if(!value)throw std::bad_alloc();
    }
    ~UtfChars() { if(value)env->ReleaseStringUTFChars(source,value); }
};
std::string string(JNIEnv* e,jstring s) { UtfChars chars(e,s); return std::string(chars.value); }
std::vector<std::string> strings(JNIEnv* e,jobjectArray array) {
    if(!array)throw std::invalid_argument("Missing move list");
    auto count=e->GetArrayLength(array); check(e);
    if(count>8192)throw std::invalid_argument("Position input too large");
    std::vector<std::string> result;
    for(int i=0;i<count;++i) {
        LocalRef<jstring> item(e,(jstring)e->GetObjectArrayElement(array,i));
        result.push_back(string(e,item.checked()));
    }
    return result;
}
void fail(JNIEnv* e,const char* text,const char* type="java/lang/IllegalStateException") {
    if(e->ExceptionCheck())return;
    LocalRef<jclass> cls(e,e->FindClass(type));
    if(!cls.value || e->ExceptionCheck())return;
    e->ThrowNew(cls.value,text);
}
template<class R,class F> R guarded(JNIEnv* e,F action,R fallback) {
    try { return action(); }
    catch(const PendingJavaException&) {} // Preserve the original Java exception.
    catch(const std::bad_alloc&) { fail(e,"Native allocation failed","java/lang/OutOfMemoryError"); }
    catch(const std::exception& error) { fail(e,error.what()); }
    return fallback;
}
void emit(jobject callback,const std::string& line) {
    if(!vm || !callback)return;
    JNIEnv* e=nullptr; bool attached=false;
    if(vm->GetEnv((void**)&e,JNI_VERSION_1_6)!=JNI_OK) {
        if(vm->AttachCurrentThread(&e,nullptr)!=JNI_OK)return;
        attached=true;
    }
    try {
        LocalRef<jclass> cls(e,e->GetObjectClass(callback));
        auto method=e->GetMethodID(cls.checked(),"onLine","(Ljava/lang/String;)V");
        check(e); if(!method)throw std::runtime_error("Missing native callback");
        LocalRef<jstring> text(e,e->NewStringUTF(line.c_str()));
        e->CallVoidMethod(callback,method,text.checked()); check(e);
    } catch(...) { if(e->ExceptionCheck())e->ExceptionClear(); }
    if(attached)vm->DetachCurrentThread();
}
}
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* value,void*) { vm=value; return JNI_VERSION_1_6; }
extern "C" JNIEXPORT jlong JNICALL Java_dev_sakura_xiangqi_engine_NativeBindings_create(JNIEnv* e,jobject,jstring net,jint threads,jint hash) {
    return guarded<jlong>(e,[&] {
        auto handle=std::make_unique<Handle>();
        handle->bridge=std::make_unique<Assistant::Bridge>(string(e,net),threads,hash);
        return (jlong)handle.release();
    },0);
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_dev_sakura_xiangqi_engine_NativeBindings_legal(JNIEnv* e,jobject,jstring fen,jobjectArray moves) {
    return guarded<jobjectArray>(e,[&] {
        auto result=Assistant::legal(string(e,fen),strings(e,moves));
        LocalRef<jclass> cls(e,e->FindClass("java/lang/String"));
        LocalRef<jobjectArray> array(e,e->NewObjectArray(result.moves.size()+1,cls.checked(),nullptr));
        array.checked();
        {
            LocalRef<jstring> text(e,e->NewStringUTF(result.fen.c_str()));
            e->SetObjectArrayElement(array.value,0,text.checked()); check(e);
        }
        for(size_t i=0;i<result.moves.size();++i) {
            LocalRef<jstring> text(e,e->NewStringUTF(result.moves[i].c_str()));
            e->SetObjectArrayElement(array.value,i+1,text.checked()); check(e);
        }
        return array.release();
    },nullptr);
}
extern "C" JNIEXPORT void JNICALL Java_dev_sakura_xiangqi_engine_NativeBindings_start(JNIEnv* e,jobject,jlong pointer,jstring fen,jobjectArray moves,jint ms,jobject callback) {
    guarded<int>(e,[&] {
        auto handle=(Handle*)pointer;
        if(!handle || !callback)throw std::invalid_argument("Missing native handle or callback");
        handle->bridge->await();
        auto initialFen=string(e,fen); auto history=strings(e,moves);
        GlobalRef next{e,e->NewGlobalRef(callback)};
        auto captured=next.checked();
        try { handle->bridge->start(initialFen,history,ms,[captured](const std::string& line) { emit(captured,line); }); }
        catch(...) { handle->bridge->stop(); handle->bridge->await(); throw; }
        if(handle->callback)e->DeleteGlobalRef(handle->callback);
        handle->callback=next.release();
        return 0;
    },0);
}
extern "C" JNIEXPORT void JNICALL Java_dev_sakura_xiangqi_engine_NativeBindings_stop(JNIEnv*,jobject,jlong p) { if(p)((Handle*)p)->bridge->stop(); }
extern "C" JNIEXPORT void JNICALL Java_dev_sakura_xiangqi_engine_NativeBindings_await(JNIEnv*,jobject,jlong p) { if(p)((Handle*)p)->bridge->await(); }
extern "C" JNIEXPORT void JNICALL Java_dev_sakura_xiangqi_engine_NativeBindings_configure(JNIEnv* e,jobject,jlong p,jint threads,jint hash) {
    guarded<int>(e,[&] { if(!p)throw std::invalid_argument("Missing native handle"); ((Handle*)p)->bridge->configure(threads,hash); return 0; },0);
}
extern "C" JNIEXPORT void JNICALL Java_dev_sakura_xiangqi_engine_NativeBindings_destroy(JNIEnv* e,jobject,jlong p) {
    auto handle=(Handle*)p; if(!handle)return;
    handle->bridge->stop(); handle->bridge->await();
    if(handle->callback)e->DeleteGlobalRef(handle->callback);
    delete handle;
}

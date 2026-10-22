#include <jni.h>
#include <sys/auxv.h>
#include <asm/hwcap.h>
extern "C" JNIEXPORT jboolean JNICALL Java_dev_sakura_xiangqi_engine_NativeLoader_hasDotProduct(JNIEnv*,jobject) {
    return (getauxval(AT_HWCAP)&HWCAP_ASIMDDP)!=0;
}

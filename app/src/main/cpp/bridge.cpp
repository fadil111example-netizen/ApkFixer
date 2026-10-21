#include <jni.h>

#include <string>
#include <vector>

#include "axml.h"

static std::vector<uint8_t> toVec(JNIEnv* env, jbyteArray arr) {
    jsize n = env->GetArrayLength(arr);
    std::vector<uint8_t> v((size_t)n);
    env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte*>(v.data()));
    return v;
}

extern "C" {

// Mengembalikan "package\tminSdk\ttargetSdk\tsplitRequired" atau "" jika gagal.
JNIEXPORT jstring JNICALL Java_com_fadil_apkfixer_Native_inspect(JNIEnv* env, jobject, jbyteArray arr) {
    auto buf = toVec(env, arr);
    axml::Info info;
    if (!axml::process(buf.data(), buf.size(), -1, -1, false, info)) return env->NewStringUTF("");
    std::string r = info.pkg + "\t" + std::to_string(info.minSdk) + "\t" +
                    std::to_string(info.targetSdk) + "\t" + std::to_string(info.splitReq) + "\t" +
                    std::to_string(info.versionCode) + "\t" + info.versionName + "\t" + std::to_string(info.perms);
    return env->NewStringUTF(r.c_str());
}

JNIEXPORT jbyteArray JNICALL Java_com_fadil_apkfixer_Native_patch(JNIEnv* env, jobject, jbyteArray arr,
                                                                 jint newMin, jint newTarget, jboolean clearSplit) {
    auto buf = toVec(env, arr);
    axml::Info info;
    if (!axml::process(buf.data(), buf.size(), newMin, newTarget, clearSplit == JNI_TRUE, info)) return nullptr;
    jbyteArray out = env->NewByteArray((jsize)buf.size());
    env->SetByteArrayRegion(out, 0, (jsize)buf.size(), reinterpret_cast<const jbyte*>(buf.data()));
    return out;
}

}  // extern "C"

// JNI surface for app.phase.stem.audio.NativeEngine. One engine per process.
#include <jni.h>

#include <atomic>
#include <memory>
#include <string>
#include <vector>

#include "Exporter.h"
#include "OboeHost.h"
#include "StemEngine.h"

using namespace phase;

namespace {
StemEngine& engine() {
    static StemEngine e;
    return e;
}
OboeHost& host() {
    static OboeHost h(engine());
    return h;
}
std::atomic<float> gExportProgress{0};
std::atomic<bool> gExportCancel{false};
std::string gLastError;

std::string str(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c);
    env->ReleaseStringUTFChars(s, c);
    return r;
}
}  // namespace

#define JNI(ret, name) extern "C" JNIEXPORT ret JNICALL Java_app_phase_stem_audio_NativeEngine_##name

JNI(jboolean, nStartOutput)(JNIEnv*, jobject, jboolean lowLatency) { return host().start(lowLatency); }
JNI(void, nStopOutput)(JNIEnv*, jobject) { host().stop(); }
JNI(jboolean, nIsOutputRunning)(JNIEnv*, jobject) { return host().isRunning(); }

JNI(jintArray, nStreamInfo)(JNIEnv* env, jobject) {
    StreamInfo i = host().info();
    jint v[9] = {i.sampleRate, i.framesPerBurst, i.bufferSize, i.bufferCapacity, i.aaudio, i.exclusive,
                 i.lowLatency, (jint)(i.latencyMs * 100), i.xruns};
    jintArray a = env->NewIntArray(9);
    env->SetIntArrayRegion(a, 0, 9, v);
    return a;
}

JNI(jboolean, nLoad)(JNIEnv* env, jobject, jobjectArray paths, jint sourceRate) {
    std::vector<std::string> p;
    const jsize n = env->GetArrayLength(paths);
    for (jsize i = 0; i < n; ++i) {
        auto js = (jstring)env->GetObjectArrayElement(paths, i);
        p.push_back(str(env, js));
        env->DeleteLocalRef(js);
    }
    std::string err;
    bool ok = engine().load(p, sourceRate, &err);
    if (!ok) gLastError = err;
    return ok;
}

JNI(void, nUnload)(JNIEnv*, jobject) { engine().unload(); }
JNI(jint, nSourceCount)(JNIEnv*, jobject) { return engine().sourceCount(); }
JNI(jstring, nLastError)(JNIEnv* env, jobject) { return env->NewStringUTF(gLastError.c_str()); }

JNI(void, nSetPlaying)(JNIEnv*, jobject, jboolean p) { engine().setPlaying(p); }
JNI(jboolean, nIsPlaying)(JNIEnv*, jobject) { return engine().isPlaying(); }
JNI(jboolean, nConsumeEnded)(JNIEnv*, jobject) { return engine().consumeEnded(); }
JNI(void, nSeek)(JNIEnv*, jobject, jdouble s) { engine().seek(s); }
JNI(jdouble, nPosition)(JNIEnv*, jobject) { return engine().position(); }
JNI(jdouble, nDuration)(JNIEnv*, jobject) { return engine().duration(); }
JNI(void, nSetSpeed)(JNIEnv*, jobject, jfloat s) { engine().setSpeed(s); }
JNI(void, nSetKeepPitch)(JNIEnv*, jobject, jboolean k) { engine().setKeepPitch(k); }
JNI(void, nSetReverse)(JNIEnv*, jobject, jboolean r) { engine().setReverse(r); }
JNI(void, nSetLoop)(JNIEnv*, jobject, jboolean on, jdouble a, jdouble b) { engine().setLoop(on, a, b); }

JNI(void, nSetParam)(JNIEnv*, jobject, jint bus, jint id, jfloat v) {
    if (bus < 0 || bus >= kNumBuses || id < 0 || id >= P_COUNT) return;
    engine().bus(bus).set(id, v);
}
JNI(jfloat, nGetParam)(JNIEnv*, jobject, jint bus, jint id) {
    if (bus < 0 || bus >= kNumBuses || id < 0 || id >= P_COUNT) return 0;
    return engine().bus(bus).get(id);
}

JNI(void, nMeters)(JNIEnv* env, jobject, jfloatArray out) {
    float m[kNumBuses * 2];
    engine().meters(m);
    env->SetFloatArrayRegion(out, 0, kNumBuses * 2, m);
}

JNI(jboolean, nStartRecording)(JNIEnv* env, jobject, jstring path, jstring format, jint kbps) {
    std::string err;
    bool ok = engine().recorder().start(str(env, path), str(env, format), kbps, engine().outputSampleRate(), &err);
    if (!ok) gLastError = err;
    return ok;
}
JNI(jdouble, nStopRecording)(JNIEnv*, jobject) { return engine().recorder().stop(); }
JNI(jboolean, nIsRecording)(JNIEnv*, jobject) { return engine().recorder().isRecording(); }
JNI(jdouble, nRecordingSeconds)(JNIEnv*, jobject) { return engine().recorder().seconds(); }

JNI(jboolean, nExport)(JNIEnv* env, jobject, jstring path, jstring format, jint kbps, jdouble a, jdouble b,
                       jboolean tail) {
    gExportCancel.store(false);
    ExportOptions o;
    o.path = str(env, path);
    o.format = str(env, format);
    o.bitrateKbps = kbps;
    o.startSec = a;
    o.endSec = b;
    o.renderTail = tail;
    std::string err;
    bool ok = exportMix(engine(), o, gExportProgress, gExportCancel, &err);
    if (!ok) gLastError = err;
    return ok;
}
JNI(jfloat, nExportProgress)(JNIEnv*, jobject) { return gExportProgress.load(); }
JNI(void, nCancelExport)(JNIEnv*, jobject) { gExportCancel.store(true); }

// ---- mashup decks & performance -------------------------------------------
// paths: all files, deck by deck; counts[d] = files in deck d; rates[d] = its rate.
JNI(jboolean, nLoadDecks)(JNIEnv* env, jobject, jobjectArray paths, jintArray counts, jintArray rates) {
    const jsize nd = env->GetArrayLength(counts);
    std::vector<jint> c(nd), r(nd);
    env->GetIntArrayRegion(counts, 0, nd, c.data());
    env->GetIntArrayRegion(rates, 0, nd, r.data());
    std::vector<DeckSpec> decks;
    int k = 0;
    for (jsize d = 0; d < nd; ++d) {
        DeckSpec spec;
        spec.sampleRate = r[d];
        for (int i = 0; i < c[d]; ++i, ++k) {
            auto js = (jstring)env->GetObjectArrayElement(paths, k);
            spec.paths.push_back(str(env, js));
            env->DeleteLocalRef(js);
        }
        decks.push_back(std::move(spec));
    }
    std::string err;
    bool ok = engine().loadDecks(decks, &err);
    if (!ok) gLastError = err;
    return ok;
}
JNI(jint, nDeckCount)(JNIEnv*, jobject) { return engine().deckCount(); }
JNI(void, nSetDeckParam)(JNIEnv*, jobject, jint deck, jint id, jfloat v) { engine().setDeckParam(deck, id, v); }
JNI(void, nSetGlobal)(JNIEnv*, jobject, jint id, jfloat v) { engine().setGlobal(id, v); }
JNI(void, nTriggerSfx)(JNIEnv*, jobject, jint pad) { engine().triggerSfx(pad); }
JNI(void, nBrake)(JNIEnv*, jobject) { engine().brake(); }
JNI(void, nStopSfx)(JNIEnv*, jobject) { engine().stopSfx(); }
JNI(jdouble, nDeckHold)(JNIEnv*, jobject, jint deck) { return engine().deckHold(deck); }

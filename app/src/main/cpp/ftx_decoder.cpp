#include <jni.h>
#include <vector>
#include <string>
#include <set>
#include <cmath>
extern "C" {
#include "common/monitor.h"
#include "ft8/message.h"
#include "ft8/encode.h"
}

static std::vector<std::string> decode_pcm(const std::vector<jshort>& pcm, bool ft4) {
    monitor_config_t cfg{};
    cfg.f_min = 100; cfg.f_max = 3200; cfg.sample_rate = 12000;
    cfg.time_osr = 2; cfg.freq_osr = 2;
    cfg.protocol = ft4 ? FTX_PROTOCOL_FT4 : FTX_PROTOCOL_FT8;
    monitor_t mon{}; monitor_init(&mon, &cfg);
    std::vector<float> block(mon.block_size);
    for (size_t pos=0; pos+block.size()<=pcm.size(); pos+=block.size()) {
        for (size_t i=0; i<block.size(); ++i) block[i]=pcm[pos+i]/32768.f;
        monitor_process(&mon, block.data());
    }
    ftx_candidate_t candidates[140];
    int count=ftx_find_candidates(&mon.wf,140,candidates,10);
    std::set<std::string> seen;
    std::vector<std::string> result;
    for (int i=0; i<count; ++i) {
        ftx_message_t message{}; ftx_decode_status_t status{};
        if (!ftx_decode_candidate(&mon.wf,&candidates[i],25,&message,&status)) continue;
        char text[FTX_MAX_MESSAGE_LENGTH]{}; ftx_message_offsets_t offsets{};
        if (ftx_message_decode(&message,nullptr,text,&offsets)!=FTX_MESSAGE_RC_OK) continue;
        if (!seen.insert(text).second) continue;
        float hz=(mon.min_bin+candidates[i].freq_offset+float(candidates[i].freq_sub)/mon.wf.freq_osr)/mon.symbol_period;
        result.push_back(std::to_string(int(std::lround(hz)))+"\t"+text);
        if (result.size()>=50) break;
    }
    monitor_free(&mon);
    return result;
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_com_example_sdr_NativeFtx_decode(JNIEnv* env,jobject,jshortArray input,jboolean ft4) {
    const int n=env->GetArrayLength(input);
    auto stringClass=env->FindClass("java/lang/String");
    if(n<12000 || n>180000) return env->NewObjectArray(0,stringClass,nullptr);
    std::vector<jshort> pcm(n);env->GetShortArrayRegion(input,0,n,pcm.data());
    auto rows=decode_pcm(pcm,ft4);
    auto output=env->NewObjectArray(rows.size(),stringClass,nullptr);
    for(size_t i=0;i<rows.size();++i){auto s=env->NewStringUTF(rows[i].c_str());env->SetObjectArrayElement(output,i,s);env->DeleteLocalRef(s);}
    return output;
}
#ifndef NDEBUG
// Independent upstream encoder supplies reference vectors for device tests.
extern "C" JNIEXPORT jshortArray JNICALL Java_com_example_sdr_FtxNativeTest_reference(JNIEnv* env,jobject,jboolean ft4) {
    ftx_message_t message{};ftx_message_encode(&message,nullptr,"CQ K1ABC FN42");
    uint8_t tones[FT4_NN > FT8_NN ? FT4_NN : FT8_NN]{};
    if(ft4)ft4_encode(message.payload,tones);else ft8_encode(message.payload,tones);
    const int symbols=ft4?FT4_NN:FT8_NN;const int block=ft4?576:1920;
    std::vector<jshort> pcm(ft4?90000:180000,0);double phase=0;
    for(int s=0;s<symbols;++s) for(int j=0;j<block;++j){int p=6000+s*block+j;if(p>=int(pcm.size()))break;phase+=2*3.141592653589793*(1000.0+tones[s]*12000.0/block)/12000;pcm[p]=jshort(12000*std::sin(phase));}
    auto output=env->NewShortArray(pcm.size());env->SetShortArrayRegion(output,0,pcm.size(),pcm.data());return output;
}
#endif

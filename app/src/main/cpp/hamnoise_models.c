#include "denoise_model.h"
#include "cw_model_weights.h"
#include "voice_model_weights.h"

const denoise_model_t *radiola_hamnoise_model(int model) {
    return model == 1 ? &k_denoise_model : &k_voice_reduction_model;
}

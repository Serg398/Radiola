function processPcm(samples, gain) {
    for (let i = 0; i < samples.length; i++) {
        samples[i] = Math.max(-32768, Math.min(32767, Math.round(samples[i] * gain)));
    }
}

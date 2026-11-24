function analyzePcm(samples) {
    var n = samples.length;
    var re = new Float64Array(n);
    var im = new Float64Array(n);
    var sum = 0;
    for (var i = 0; i < n; i++) sum += samples[i];
    var dc = sum / n;
    for (var i = 0, j = 0; i < n; i++) {
        re[j] = (samples[i] - dc) / 32768 * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / n));
        var bit = n >> 1;
        while (bit && (j & bit)) { j ^= bit; bit >>= 1; }
        j ^= bit;
    }
    for (var size = 2; size <= n; size *= 2) {
        var half = size >> 1;
        for (var j = 0; j < half; j++) {
            var phase = -2 * Math.PI * j / size;
            var c = Math.cos(phase);
            var s = Math.sin(phase);
            for (var i = j; i < n; i += size) {
                var k = i + half;
                var a = c * re[k] - s * im[k];
                var b = s * re[k] + c * im[k];
                re[k] = re[i] - a;
                im[k] = im[i] - b;
                re[i] += a;
                im[i] += b;
            }
        }
    }
    var result = new Float32Array(n / 2 + 1);
    for (var i = 0; i < result.length; i++) {
        var scale = (i === 0 || i === n / 2 ? 2 : 4) / n;
        var amplitude = Math.sqrt(re[i] * re[i] + im[i] * im[i]) * scale;
        result[i] = Math.max(-100, 20 * Math.log10(amplitude));
    }
    return result;
}

package com.example.sdr;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;
import com.example.sdr.audio.HamNoiseNative;
import com.example.sdr.decoders.cw.DeepCwDecoder;
import com.example.sdr.decoders.cw.CwFormerDecoder;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** No AndroidX/Kotlin test-runner ABI: calls the retained production boundaries directly. */
public class ReleaseSmokeInstrumentation extends Instrumentation {
    private int checks;
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            int[] rates = NativeSdr.INSTANCE.receiverRates();
            require(rates[0] > 0, "Native sample rate");
            SpectrumFrame frame = NativeSdr.INSTANCE.spectrum();
            require(frame.getBins().length == 8192 && frame.getSpanHz() == rates[0], "JNI SpectrumFrame constructor");
            require(NativeSdr.INSTANCE.rds() != null, "JNI RdsFrame constructor");
            checks++;
            decodeFt8(); checks++;
            short[] cw = morse();
            try (DeepCwDecoder decoder = new DeepCwDecoder(getTargetContext())) {
                for (int i = 0; i < cw.length; i += 997)
                    decoder.process(Arrays.copyOfRange(cw, i, Math.min(cw.length, i + 997)), 8000, 1);
                require("CQ TEST DE CQ TEST".equals(decoder.information().getText().trim()), "DeepCW transcript");
            }
            checks++;
            try (CwFormerDecoder decoder = new CwFormerDecoder(getTargetContext())) {
                for (int i = 0; i < cw.length; i += 997)
                    decoder.process(Arrays.copyOfRange(cw, i, Math.min(cw.length, i + 997)), 8000, 1);
                require("CQ TEST DE CQ TEST".equals(decoder.information().getText().trim()), "CWformer transcript");
            }
            checks++;
            long handle = HamNoiseNative.INSTANCE.create(12000, 1, 1);
            require(handle != 0, "HamNoise JNI create");
            try {
                short[] pcm = new short[4800];
                for (int i = 0; i < pcm.length; i++) pcm[i] = (short) (5000 * Math.sin(2 * Math.PI * 700 * i / 12000));
                require(HamNoiseNative.INSTANCE.process(handle, pcm, pcm.length, 1, .55f), "HamNoise JNI process");
            } finally { HamNoiseNative.INSTANCE.destroy(handle); }
            checks++;
            plugin(); checks++;
            result.putString("status", "passed"); result.putInt("checks", checks);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            Log.e("ReleaseSmoke", "Minified release boundary failed", error);
            result.putString("status", "failed"); result.putInt("checks", checks);
            result.putString("error", error.toString()); finish(Activity.RESULT_CANCELED, result);
        }
    }
    private void decodeFt8() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream stream = getContext().getAssets().open("ft8-websdr.wav")) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = stream.read(buffer)) != -1) out.write(buffer, 0, count);
        }
        byte[] wav = out.toByteArray(); int offset = 12;
        ByteBuffer bytes = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        while (!(wav[offset] == 'd' && wav[offset + 1] == 'a' && wav[offset + 2] == 't' && wav[offset + 3] == 'a')) {
            int size = bytes.getInt(offset + 4); offset += 8 + size + (size & 1);
        }
        short[] pcm = new short[bytes.getInt(offset + 4) / 2];
        bytes.position(offset + 8);
        for (int i = 0; i < pcm.length; i++) pcm[i] = bytes.getShort();
        boolean found = false;
        for (String row : NativeFtx.INSTANCE.decode(pcm, false))
            found |= row.endsWith("LZ1LZ G4UJS IO83");
        require(found, "FT8 real recording");
    }
    private static short[] morse() {
        ArrayList<Short> samples = new ArrayList<>();
        segment(samples, 10, false);
        for (char letter : "CQ TEST DE CQ TEST".toCharArray()) {
            if (letter == ' ') { segment(samples, 4, false); continue; }
            String pattern;
            switch (letter) {
                case 'C': pattern = "-.-."; break;
                case 'Q': pattern = "--.-"; break;
                case 'T': pattern = "-"; break;
                case 'E': pattern = "."; break;
                case 'S': pattern = "..."; break;
                case 'D': pattern = "-.."; break;
                default: throw new AssertionError("Unknown fixture letter");
            }
            for (int i = 0; i < pattern.length(); i++) {
                segment(samples, pattern.charAt(i) == '-' ? 3 : 1, true);
                if (i + 1 < pattern.length()) segment(samples, 1, false);
            }
            segment(samples, 3, false);
        }
        segment(samples, 100, false);
        short[] result = new short[samples.size()];
        for (int i = 0; i < result.length; i++) result[i] = samples.get(i);
        return result;
    }
    private static void segment(ArrayList<Short> samples, int units, boolean mark) {
        for (int i = 0; i < units * 480; i++)
            samples.add((short) (mark ? Math.round(22000 * Math.sin(2 * Math.PI * 700 * samples.size() / 8000)) : 0));
    }
    private void plugin() throws Exception {
        Context context = getTargetContext();
        CountDownLatch connected = new CountDownLatch(1), loaded = new CountDownLatch(1), processed = new CountDownLatch(1);
        AtomicReference<Messenger> endpoint = new AtomicReference<>();
        AtomicReference<short[]> output = new AtomicReference<>();
        Messenger reply = new Messenger(new Handler(Looper.getMainLooper(), message -> {
            if (message.what == 3 && message.arg2 == 1) loaded.countDown();
            if (message.what == 1) { output.set(message.getData().getShortArray("pcm")); processed.countDown(); }
            return true;
        }));
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                endpoint.set(new Messenger(binder)); connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) { }
        };
        Intent intent = new Intent().setComponent(new ComponentName(context.getPackageName(), "com.example.sdr.plugins.PluginScriptService"));
        require(context.bindService(intent, connection, Context.BIND_AUTO_CREATE), "Bind isolated JS service");
        try {
            require(connected.await(3, TimeUnit.SECONDS), "JS service connection");
            Message load = Message.obtain(null, 3, 1, 0); load.replyTo = reply;
            Bundle data = new Bundle(); data.putString("script", "function processPcm(samples, gain) { for(var i=0;i<samples.length;i++) samples[i]*=gain; }");
            load.setData(data); endpoint.get().send(load);
            require(loaded.await(3, TimeUnit.SECONDS), "Rhino script load");
            Message process = Message.obtain(null, 1, 2, 0); process.replyTo = reply;
            data = new Bundle(); data.putShortArray("pcm", new short[]{1000, -2000}); data.putDouble("gain", 2);
            process.setData(data); endpoint.get().send(process);
            require(processed.await(3, TimeUnit.SECONDS), "Rhino PCM processing");
            require(Arrays.equals(new short[]{2000, -4000}, output.get()), "Rhino PCM result");
        } finally { context.unbindService(connection); }
    }
}

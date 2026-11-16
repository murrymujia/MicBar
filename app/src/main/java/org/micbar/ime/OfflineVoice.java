package org.micbar.ime;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class OfflineVoice {
    interface Listener {
        void listening();
        void phrase(String words);
        void finished();
        void failed(String message);
    }
    static final class Run {
        volatile boolean stop;
        volatile boolean cancelled;
    }

    private static final int RATE = 16000;
    private static final int FRAME = 512;
    private final Context app;
    private final ExecutorService capture = Executors.newSingleThreadExecutor();
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private OfflineRecognizer recognizer;

    OfflineVoice(Context context) { app = context.getApplicationContext(); }

    Run start(Listener listener) {
        Run run = new Run();
        capture.execute(() -> record(run, listener));
        return run;
    }

    private OfflineRecognizer loadRecognizer() {
        OfflineRecognizerConfig config = new OfflineRecognizerConfig();
        OfflineModelConfig model = config.getModelConfig();
        model.getSenseVoice().setModel("SenseVoiceSmall/model.int8.onnx");
        model.getSenseVoice().setLanguage("auto");
        model.getSenseVoice().setUseInverseTextNormalization(true);
        model.setTokens("SenseVoiceSmall/tokens.txt");
        model.setNumThreads(2);
        return new OfflineRecognizer(app.getAssets(), config);
    }

    private Vad newDetector() {
        VadModelConfig config = new VadModelConfig();
        SileroVadModelConfig silero = config.getSileroVadModelConfig();
        silero.setModel("silero_vad.onnx");
        silero.setMinSpeechDuration(0.15f);
        silero.setMinSilenceDuration(0.45f);
        // Bound preview latency during continuous speech as well as after natural pauses.
        silero.setMaxSpeechDuration(4f);
        config.setSampleRate(RATE);
        return new Vad(app.getAssets(), config);
    }

    private void record(Run run, Listener listener) {
        AudioRecord mic = null;
        Vad vad = null;
        try {
            if (run.cancelled) return;
            vad = newDetector();
            int minBytes = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBytes <= 0) throw new IOException();
            mic = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(minBytes * 2, RATE * 4));
            mic.startRecording();
            if (mic.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IOException();
            listener.listening();
            // Load the recognizer concurrently so speech from the first tap is captured.
            decoder.execute(() -> {
                if (run.cancelled || recognizer != null) return;
                try {
                    recognizer = loadRecognizer();
                } catch (RuntimeException | LinkageError ex) {
                    fail(run, listener, "离线混合模型未能加载，请关闭其他应用后重试");
                }
            });
            short[] pcm = new short[FRAME];
            float[] samples = new float[FRAME];
            while (!run.stop && !run.cancelled) {
                int n = mic.read(pcm, 0, FRAME);
                if (n <= 0) throw new IOException();
                for (int i = 0; i < n; i++) samples[i] = pcm[i] / 32768f;
                vad.acceptWaveform(n == FRAME ? samples : Arrays.copyOf(samples, n));
                queueDetectedSpeech(vad, run, listener);
            }
            if (run.cancelled) return;
            mic.stop();
            vad.flush();
            queueDetectedSpeech(vad, run, listener);
            decoder.execute(() -> {
                if (!run.cancelled) listener.finished();
            });
        } catch (Exception | LinkageError ex) {
            fail(run, listener, "无法开始听写，请检查麦克风权限和设备可用空间");
        } finally {
            if (mic != null) mic.release();
            if (vad != null) vad.release();
        }
    }

    private void queueDetectedSpeech(Vad vad, Run run, Listener listener) {
        while (!vad.empty() && !run.cancelled) {
            float[] speech = vad.front().getSamples();
            vad.pop();
            decoder.execute(() -> transcribe(speech, run, listener));
        }
    }

    private void transcribe(float[] samples, Run run, Listener listener) {
        if (run.cancelled) return;
        OfflineStream stream = null;
        try {
            stream = recognizer.createStream();
            stream.acceptWaveform(samples, RATE);
            recognizer.decode(stream);
            String text = recognizer.getResult(stream).getText().trim();
            if (text.isEmpty()) return;
            if (!run.cancelled) listener.phrase(text);
        } catch (RuntimeException | LinkageError ex) {
            fail(run, listener, "本段听写未完成，请重试");
        } finally {
            if (stream != null) stream.release();
        }
    }

    private static void fail(Run run, Listener listener, String message) {
        synchronized (run) {
            if (run.cancelled) return;
            run.cancelled = true;
        }
        listener.failed(message);
    }

    void release() {
        capture.execute(() -> {
            decoder.execute(() -> { if (recognizer != null) recognizer.release(); });
            decoder.shutdown();
        });
        capture.shutdown();
    }
}

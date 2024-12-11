package com.gykj.voicetts;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.TextView;

import java.util.Date;

public class Speaktts {

    private static Speaktts instance;
    private static String content;
    private static AudioTrack audioTrack;
    private static byte[] audioData;
    protected static Predictor predictor = new Predictor();
    private static boolean isSpeaking = false;  // 线程运行标志
    public static String message;
    public static Thread thread;

    // 创建 Handler，切换到主线程
    Handler mainHandler = new Handler(Looper.getMainLooper());

    // 私有化构造方法，防止外部直接实例化
    private Speaktts() {}

    // 提供一个获取单例实例的方法
    public static Speaktts getInstance() {
        if (instance == null) {
            synchronized (Speaktts.class) {
                if (instance == null) {
                    instance = new Speaktts();
                }
            }
        }
        return instance;
    }

    // 初始化方法
    public boolean init(Context appCtx, String modelPath, String AMmodelName, String VOCmodelName, int cpuThreadNum,
                        String cpuPowerMode, int speakId) {
        return predictor.init(appCtx, modelPath, AMmodelName, VOCmodelName, cpuThreadNum, cpuPowerMode, speakId);
    }

    // 开始说话
    public void speakText(String text, int sampleRate, int speakId,TextView tvInferenceTime) {
        // 如果正在说话，禁止重复点击
        if (isSpeaking) {
            return; // 如果已经有一个线程在运行，直接返回
        }

        content = text;
        predictor.speakId = speakId;

        // 设置为正在说话
        isSpeaking = true;

        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    content = content.replace("\n", "").replace("\r", "");
                    String charSplit = "[：；。？！,;?!]《》（）()、#";
                    for (int i = 0; i < charSplit.length(); i++) {
                        content = content.replace(charSplit.charAt(i), '，');
                    }
                    String[] segmentText = content.split("，");
                    predictor.isLoaded();

                    int minBufferSize = AudioTrack.getMinBufferSize(sampleRate,
                            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT);
                    audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate,
                            AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                            minBufferSize, AudioTrack.MODE_STREAM);

                    audioTrack.play();

                    float inferenceTime = 0;
                    long totalLength = 0;

                    for (String str : segmentText) {
                        str = str.trim();
                        if (str == null || str.length() == 0)
                            continue;

                        String codes = CalcMac.getPhoneIds(str);
                        String[] codevioce = codes.split(",");
                        int[] ft = new int[codevioce.length + 1];
                        int index = 0;
                        for (String s : codevioce) {
                            if (s.equals(""))
                                ft[index] = 277;
                            else
                                ft[index] = Integer.valueOf(s);
                            index++;
                        }
                        ft[index] = 277;

                        Date start = new Date();
                        predictor.runSegmentModel(ft);
                        Date end = new Date();

                        inferenceTime += (end.getTime() - start.getTime());
                        totalLength += predictor.singlewav.length;

                        try {
                            audioData = Utils.segToByte(predictor.singlewav, predictor.maxwav).toByteArray();
                            audioTrack.write(audioData, 0, audioData.length);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }

                        // 如果需要停止播放
                        if (!isSpeaking) {
                            break;
                        }
                    }

                    // 计算RTF
                    message = "Inference done！\nInference time: " + inferenceTime + " ms"
                            + "\nRTF: " + 1.00 * inferenceTime * sampleRate / (totalLength * 1000);
                    Log.d("dddddd",""+message);

                } finally {
                    // 播放完毕后标记为不在说话状态
                    isSpeaking = false;

                    // 在主线程中计算并显示结果
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            tvInferenceTime.setText(message);
                        }
                    });
                }
            }
        });

        thread.start();
    }

    // 停止播放的方法
    public void stopSpeaking() {
        if (isSpeaking) {
            // 设置为不再说话状态
            isSpeaking = false;

            // 停止音频播放
            if (audioTrack != null) {
                audioTrack.stop();
                audioTrack.release();
                audioTrack = null;
            }

            // 停止线程
            if (thread != null && thread.isAlive()) {
                thread.interrupt();
                thread = null;
            }
        }
    }

    // 暂停音频播放
    public void pauseAudioTrack() {
        if (audioTrack != null) {
            audioTrack.pause();
            audioTrack.flush();
        }
    }

    // 销毁资源
    public void onDestroy() {
        if (predictor != null) {
            predictor.releaseModel();
        }
    }
}
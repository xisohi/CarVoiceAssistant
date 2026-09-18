path = r"E:\GitHub\CarVoiceAssistant\app\src\main\java\com\xisohi\car\voiceassistant\core\VoiceAssistantService.kt"
with open(path, 'r', encoding='utf-8') as f:
    c = f.read()

# 在变量声明区域添加尾部缓冲相关变量
old_vars = '''            var silenceDuration = 0L
            var hasSpeechStarted = false
            var speechFrameCount = 0
            var finalText = ""'''

new_vars = '''            var silenceDuration = 0L
            var hasSpeechStarted = false
            var speechFrameCount = 0
            var finalText = ""
            // ★ 尾部缓冲：检测到静音后再录500ms，确保最后一个字的尾音不丢
            var tailBufferRemainingMs = 0L
            val TAIL_BUFFER_MS = 500L'''

c = c.replace(old_vars, new_vars)

# 修改端点检测逻辑：检测到静音后不立即break，而是启动尾部缓冲
old_vad = '''                    // 基于 RMS 的端点检测
                    val recordDuration = SystemClock.elapsedRealtime() - startMs
                    if (hasSpeechStarted && isSilence) {
                        silenceDuration += (n * 1000L / SpeechRecognizer.SAMPLE_RATE.toInt())
                        val dynamicSilenceMs = if (lastPartial.isNotEmpty()) 3000L else 2000L
                        if (silenceDuration >= dynamicSilenceMs && recordDuration >= MIN_RECORD_MS) {
                            android.util.Log.d("VoiceService",
                                "连续静音${silenceDuration}ms，确认用户说完了，结束录音 (RMS=${rms.toInt()}, 阈值=${adaptiveSilenceThreshold.toInt()})")
                                sendRecognitionLog("⏹️ 结束录音 (静音${silenceDuration}ms, RMS=${rms.toInt()})")
                            shouldStopRecording.set(true)
                            break@loop
                        }
                    } else if (!isSilence) {
                        silenceDuration = 0
                    }'''

new_vad = '''                    // 基于 RMS 的端点检测
                    val recordDuration = SystemClock.elapsedRealtime() - startMs
                    val frameDurationMs = n * 1000L / SpeechRecognizer.SAMPLE_RATE.toInt()

                    // ★ 尾部缓冲模式：已检测到静音，继续录 TAIL_BUFFER_MS 确保尾音不丢
                    if (tailBufferRemainingMs > 0) {
                        tailBufferRemainingMs -= frameDurationMs
                        if (tailBufferRemainingMs <= 0) {
                            android.util.Log.d("VoiceService",
                                "尾部缓冲结束（${TAIL_BUFFER_MS}ms），停止录音")
                            shouldStopRecording.set(true)
                            break@loop
                        }
                        // 尾部缓冲期间继续处理音频（写入文件、喂给识别器），但不再检测静音
                        continue@loop
                    }

                    if (hasSpeechStarted && isSilence) {
                        silenceDuration += frameDurationMs
                        val dynamicSilenceMs = if (lastPartial.isNotEmpty()) 3000L else 2000L
                        if (silenceDuration >= dynamicSilenceMs && recordDuration >= MIN_RECORD_MS) {
                            android.util.Log.d("VoiceService",
                                "连续静音${silenceDuration}ms，启动尾部缓冲（${TAIL_BUFFER_MS}ms）确保尾音不丢 (RMS=${rms.toInt()}, 阈值=${adaptiveSilenceThreshold.toInt()})")
                                sendRecognitionLog("⏹️ 检测到静音，尾部缓冲${TAIL_BUFFER_MS}ms后停止")
                            // ★ 不立即停止，启动尾部缓冲，再录500ms
                            tailBufferRemainingMs = TAIL_BUFFER_MS
                            shouldStopRecording.set(true)  // 通知生产者停止录音（但队列里还有数据会继续处理）
                        }
                    } else if (!isSilence) {
                        silenceDuration = 0
                    }'''

c = c.replace(old_vad, new_vad)

with open(path, 'w', encoding='utf-8') as f:
    f.write(c)

print("✅ VoiceAssistantService.kt: 添加尾部缓冲（500ms），解决录音尾部截断问题")

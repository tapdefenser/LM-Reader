package com.lmreader.core.vision

import ai.onnxruntime.*
import android.graphics.*
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.FloatBuffer
import java.io.File
import kotlin.system.measureTimeMillis

@RunWith(AndroidJUnit4::class)
class OcrAccelerationIntegrationTest {
    @Test fun profileRequiresRealHardwareExecution() {
        val cpuOnly = """[{"cat":"Session","args":{"provider":"QNNExecutionProvider"}},
            {"cat":"Node","args":{"provider":"CPUExecutionProvider"}}]"""
        assertEquals(0,hardwareNodeCount(cpuOnly,"QNNExecutionProvider"))
        val mixed = """[{"cat":"Node","args":{"provider":"CPUExecutionProvider"}},
            {"cat":"Node","args":{"provider":"QNNExecutionProvider"}}]"""
        assertEquals(1,hardwareNodeCount(mixed,"QNNExecutionProvider"))
    }
    @Test fun qnnDeviceInventoryAndFixedShapeInference() {
        val models = VisionModels(ApplicationProvider.getApplicationContext())
        org.junit.Assume.assumeTrue(android.os.Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a")
        val devices = try { QnnAvailability.devices(models) } catch (failure: Exception) {
            if (InstrumentationRegistry.getArguments().getString("requireQnn") == "true") throw failure
            emptyList()
        }
        Log.i("OcrAcceleration", "QNN devices=$devices")
        if (InstrumentationRegistry.getArguments().getString("requireQnn") == "true") assertTrue("No QNN hardware",devices.isNotEmpty())
        org.junit.Assume.assumeTrue(devices.isNotEmpty())
        val device = devices.firstOrNull { it.device.type == OrtHardwareDevice.OrtHardwareDeviceType.NPU }
            ?: devices.first()
        val environment = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { options ->
            options.setSymbolicDimensionValue("DynamicDimension.0", 1)
            options.setSymbolicDimensionValue("DynamicDimension.1", 320)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_INFO)
            options.addExecutionProvider(listOf(device), mapOf("backend_type" to "htp", "htp_performance_mode" to "burst"))
            options.enableProfiling(models.profilePrefix())
            environment.createSession(models.file("rec.onnx").absolutePath, options).use { session ->
                val shape = (session.inputInfo.values.single().info as TensorInfo).shape
                Log.i("OcrAcceleration", "QNN input=${shape.contentToString()}")
                OnnxTensor.createTensor(environment, FloatBuffer.wrap(FloatArray(3*48*320)), longArrayOf(1,3,48,320)).use { input ->
                    session.run(mapOf(session.inputNames.single() to input)).use { output ->
                        Log.i("OcrAcceleration", "QNN output=${(output[0] as OnnxTensor).info.shape.contentToString()}")
                    }
                }
                val profile = File(session.endProfiling())
                Log.i("OcrAcceleration", "PROFILE=${profile.absolutePath}")
                try { assertTrue(hardwareNodeCount(profile.readText(), "QNNExecutionProvider") > 0) }
                finally { profile.delete() }
            }
        }
    }

    private fun fixture(text: String, width: Int = 900): Bitmap =
        Bitmap.createBitmap(width,320,Bitmap.Config.ARGB_8888).apply {
            Canvas(this).apply {
                drawColor(Color.WHITE)
                drawText(text,80f,170f,Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK; textSize = 62f; typeface = Typeface.DEFAULT_BOLD
                })
            }
        }

    @Test fun explicitBackendChoiceAndCpuFallback() {
        val models = VisionModels(ApplicationProvider.getApplicationContext())
        val image = fixture("HELLO")
        val strip = Bitmap.createBitmap(image,60,100,300,85)
        val requireQnn = InstrumentationRegistry.getArguments().getString("requireQnn") == "true"
        try {
            for (choice in OcrBackend.entries) {
                val notices = mutableListOf<String>()
                PaddleRecognizer(models,false,choice,notices::add).use { recognizer ->
                    assertTrue(recognizer.recognize(strip).text.contains("HELLO"))
                    when (choice) {
                        OcrBackend.CPU -> assertEquals("ONNX Runtime CPU",recognizer.backend)
                        OcrBackend.NNAPI -> {
                            assertFalse("NNAPI selection must not use QNN",recognizer.backend.startsWith("QNN"))
                            if (NnapiAvailability.unavailableReason != null) {
                                assertEquals("ONNX Runtime CPU",recognizer.backend)
                                assertTrue(notices.any { it.contains("回退 CPU") })
                            }
                        }
                        OcrBackend.QNN, OcrBackend.AUTO -> if (requireQnn) {
                            assertTrue(recognizer.backend.startsWith("QNN NPU"))
                            assertFalse(recognizer.backend.contains("待验证"))
                        }
                    }
                    Log.i("OcrAcceleration","CHOICE=$choice actual=${recognizer.backend} notices=$notices")
                }
            }
        } finally { strip.recycle(); image.recycle() }
    }

    @Test fun acceleratedOcrPreservesLanguagesLongLinesAndReload() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cpu = LocalVisionEngine(context) { VisionExecutionSettings(ocrConcurrency = 1) }
        val accelerated = LocalVisionEngine(context) {
            VisionExecutionSettings(ocrConcurrency = 1, ocrBackend = com.lmreader.core.model.OcrBackend.AUTO)
        }
        val requireQnn = InstrumentationRegistry.getArguments().getString("requireQnn") == "true"
        try {
            for ((language,text) in listOf(LocalOcrLanguage.ENGLISH to "HELLO",
                LocalOcrLanguage.CHINESE_SIMPLIFIED to "你好世界", LocalOcrLanguage.JAPANESE to "こんにちは",
                LocalOcrLanguage.KOREAN to "안녕하세요")) {
                val image = fixture(text)
                try {
                    val expected = cpu.recognize("cpu",image,language)
                    val actual = accelerated.recognize("hardware",image,language)
                    assertTrue("$language: ${actual.text}", actual.text.contains(text))
                    assertEquals(expected.text, actual.text)
                    assertTrue(actual.lines.all { it.bounds.left >= 0 && it.bounds.top >= 0 &&
                        it.bounds.right <= image.width && it.bounds.bottom <= image.height })
                    Log.i("OcrAcceleration", "$language CPU=${expected.elapsedMillis}ms hardware=${actual.elapsedMillis}ms text=${actual.text}")
                    if (requireQnn) withTimeout(5000) {
                        accelerated.loadedResources.first { resources ->
                            resources.any { it.id.endsWith(":det") && it.backend.startsWith("QNN") && !it.backend.contains("待验证") } &&
                                resources.any { (it.id.endsWith(":rec") || it.id.endsWith(":ko")) &&
                                    it.backend.startsWith("QNN") && !it.backend.contains("待验证") }
                        }
                    }
                } finally { image.recycle() }
            }
            val longText = "THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG"
            val long = fixture(longText,2200)
            val strip = Bitmap.createBitmap(long,60,100,2090,85)
            try {
                // Exercise the recognizer directly: a wide crop must not be squeezed
                // into the accelerator's static input or lose its ending.
                val models = VisionModels(context)
                PaddleRecognizer(models,false).use { baseline ->
                    PaddleRecognizer(models,false,OcrBackend.AUTO).use { hardware ->
                        val expected = baseline.recognize(strip).text
                        assertEquals(expected,hardware.recognize(strip).text)
                        assertTrue("Wide line: $expected", expected.contains("LAZY DOG"))
                    }
                }
            } finally { strip.recycle(); long.recycle() }
            val image = fixture("HELLO")
            try {
                cpu.recognize("warmup",image,LocalOcrLanguage.ENGLISH)
                accelerated.recognize("warmup",image,LocalOcrLanguage.ENGLISH)
                val cpuTimes = mutableListOf<Long>(); val acceleratedTimes = mutableListOf<Long>()
                repeat(5) {
                    cpuTimes += measureTimeMillis { cpu.recognize("cpu-$it",image,LocalOcrLanguage.ENGLISH) }
                    acceleratedTimes += measureTimeMillis { accelerated.recognize("hardware-$it",image,LocalOcrLanguage.ENGLISH) }
                }
                Log.i("OcrAcceleration", "WARM CPU=$cpuTimes hardware=$acceleratedTimes backends=${accelerated.loadedResources.value}")
                accelerated.releaseModels()
                assertTrue(accelerated.recognize("reload",image,LocalOcrLanguage.ENGLISH).text.contains("HELLO"))
                Log.i("OcrAcceleration", "Notices=${accelerated.accelerationMessages.value}")
            } finally { image.recycle() }
        } finally { cpu.releaseModels(); accelerated.releaseModels() }
    }
}

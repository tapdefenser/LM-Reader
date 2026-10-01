package com.lmreader.ui.settings.vision

import android.graphics.*
import com.lmreader.core.model.LocalOcrLanguage

internal enum class VisionDemo(val label: String,val language: LocalOcrLanguage) {
    ENGLISH("英语横排",LocalOcrLanguage.ENGLISH), JAPANESE("日语横排",LocalOcrLanguage.JAPANESE),
    VERTICAL("日语竖排",LocalOcrLanguage.JAPANESE), CHINESE("简体中文",LocalOcrLanguage.CHINESE_SIMPLIFIED),
    TRADITIONAL("繁体中文",LocalOcrLanguage.CHINESE_TRADITIONAL), KOREAN("韩语",LocalOcrLanguage.KOREAN),
    LONG("长图",LocalOcrLanguage.ENGLISH), BLANK("空白页",LocalOcrLanguage.ENGLISH)
}
internal object VisionDemoImages {
    fun create(sample: VisionDemo): Bitmap {
        val bitmap=Bitmap.createBitmap(800,if (sample==VisionDemo.LONG) 4000 else 1000,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap); canvas.drawColor(Color.WHITE)
        if (sample==VisionDemo.BLANK) return bitmap
        val pen=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; strokeWidth=4f }
        val text=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; textSize=42f; typeface=Typeface.DEFAULT_BOLD; textAlign=Paint.Align.CENTER }
        val panels=if (sample==VisionDemo.LONG) 4 else 1
        repeat(panels) { index ->
            val offset=index*1000f
            canvas.save(); canvas.translate(0f,offset)
            pen.style=Paint.Style.STROKE
            canvas.drawRect(20f,20f,780f,980f,pen)
            canvas.drawOval(110f,100f,690f,460f,pen)
            canvas.drawLine(480f,450f,515f,530f,pen); canvas.drawLine(515f,530f,550f,440f,pen)
            canvas.drawCircle(370f,690f,90f,pen); canvas.drawLine(320f,790f,270f,970f,pen); canvas.drawLine(420f,790f,470f,970f,pen)
            canvas.drawLine(325f,710f,345f,710f,pen); canvas.drawLine(395f,710f,415f,710f,pen)
            canvas.drawArc(345f,735f,395f,770f,0f,180f,false,pen)
            if (sample==VisionDemo.VERTICAL) {
                listOf("こんにちは","世界").forEachIndexed { column,line ->
                    line.forEachIndexed { row,char -> canvas.drawText(char.toString(),460f-column*95f,185f+row*50f,text) }
                }
            } else {
                val lines=when(sample) {
                    VisionDemo.JAPANESE -> listOf("こんにちは","世界")
                    VisionDemo.CHINESE -> listOf("你好世界","本地文字识别")
                    VisionDemo.TRADITIONAL -> listOf("你好世界","本地文字識別")
                    VisionDemo.KOREAN -> listOf("안녕하세요","세계")
                    else -> listOf("HELLO WORLD","LOCAL OCR ${index+1}")
                }
                lines.forEachIndexed { row,line -> canvas.drawText(line,400f,250f+row*80f,text) }
            }
            canvas.restore()
        }
        return bitmap
    }
}

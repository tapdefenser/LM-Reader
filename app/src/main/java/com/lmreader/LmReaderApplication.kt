package com.lmreader

import android.app.Application
import com.lmreader.di.AppContainer
import com.lmreader.di.LmReaderApplicationHolder

/**
 * 应用入口：只持有依赖容器，不执行目录 IO 或翻译请求（开发文档 15.2）。
 */
class LmReaderApplication : Application(), LmReaderApplicationHolder {

    override val container: AppContainer by lazy { AppContainer(this) }
}

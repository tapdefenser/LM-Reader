import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * 带 Room（KSP 代码生成）的 Android 库模块。
 *
 * Room 保存可查询的索引与用户状态；缩略图等大对象按内容寻址存文件，
 * 数据库只保存引用（开发文档 15.3 / 6.5）。
 */
class AndroidLibraryRoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("lmreader.android.library")
        pluginManager.apply("com.google.devtools.ksp")

        // 导出的 schema JSON 入库，供后续迁移测试比对。
        extensions.configure<KspExtension> {
            arg("room.schemaLocation", "$projectDir/schemas")
            arg("room.generateKotlin", "true")
        }

        dependencies {
            add("implementation", libs.findLibrary("androidx-room-runtime").get())
            add("implementation", libs.findLibrary("androidx-room-ktx").get())
            add("ksp", libs.findLibrary("androidx-room-compiler").get())
            add("testImplementation", libs.findLibrary("androidx-room-testing").get())
        }
    }
}

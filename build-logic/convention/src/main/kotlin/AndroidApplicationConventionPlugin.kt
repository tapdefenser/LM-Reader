import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import java.io.StringReader
import java.util.Properties

/**
 * 应用宿主模块配置：导航、依赖装配、主题（开发文档 15.2）。
 *
 * 宿主不直接读写源文件、不发翻译请求；这些由 core/feature 模块承担。
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("org.jetbrains.kotlin.android")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
        pluginManager.apply("com.google.devtools.ksp")

        val releaseVersion = Properties().apply {
            load(StringReader(providers.fileContents(rootProject.layout.projectDirectory.file("gradle/release.properties")).asText.get()))
        }

        extensions.configure<ApplicationExtension> {
            namespace = "com.lmreader"
            compileSdk = COMPILE_SDK
            defaultConfig {
                applicationId = "com.lmreader"
                minSdk = MIN_SDK
                targetSdk = TARGET_SDK
                versionCode = releaseVersion.getProperty("versionCode").toInt()
                versionName = releaseVersion.getProperty("versionName")
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            buildFeatures {
                compose = true
            }
            buildTypes {
                release {
                    isMinifyEnabled = true
                    isShrinkResources = true
                }
                debug {
                    applicationIdSuffix = ".debug"
                }
            }
            packaging {
                resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
            }
            testOptions {
                unitTests.isReturnDefaultValues = true
            }
        }

        extensions.configure<KotlinAndroidProjectExtension> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
                jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
            }
        }

        dependencies {
            val bom = libs.findLibrary("androidx-compose-bom").get()
            add("implementation", platform(bom))
            add("debugImplementation", libs.findLibrary("androidx-compose-ui-tooling").get())
        }
    }
}

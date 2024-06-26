package io.github.takahirom.roborazzi

import com.android.build.api.variant.Variant
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskCollection
import org.gradle.api.tasks.testing.Test
import java.io.File
import javax.inject.Inject


open class AutoPreviewScreenshotsExtension @Inject constructor(objects: ObjectFactory) {
  val enabled: Property<Boolean> = objects.property(Boolean::class.java)
  val scanPackageTrees: ListProperty<String> = objects.listProperty(String::class.java)
}

fun setupAutoPreviewScreenshotTests(
  project: Project,
  variant: Variant,
  extension: AutoPreviewScreenshotsExtension,
  testTaskProvider: TaskCollection<Test>
) {
  val scanPackageTrees = extension.scanPackageTrees.get()
  assert(scanPackageTrees.isNotEmpty()) {
    "Please set scanPackageTrees in the autoPreviewScreenshots extension. Please refer to https://github.com/sergio-sastre/ComposablePreviewScanner?tab=readme-ov-file#how-to-use for more information."
  }
  addPreviewScreenshotLibraryDependencies(variant, project)
  setupTestTask(testTaskProvider, project)
  setupGenerateTestsTask(project, variant, scanPackageTrees)
}

private fun setupGenerateTestsTask(
  project: Project,
  variant: Variant,
  scanPackageTrees: List<String>?
) {
  val generateTestsTask = project.tasks.register(
    "generate${variant.name.capitalize()}PreviewScreenshotTests",
    GeneratePreviewScreenshotTestsTask::class.java
  ) {
    // It seems that this directory path is overridden by addGeneratedSourceDirectory.
    // The generated tests will be located in build/JAVA/generate[VariantName]PreviewScreenshotTests.
    it.outputDir.set(project.layout.buildDirectory.dir("generated/roborazzi/preview-screenshot"))
    it.scanPackageTrees.set(scanPackageTrees)
  }
  // We need to use Java here; otherwise, the generate task will not be executed.
  // https://stackoverflow.com/a/76870110/4339442
  variant.unitTest?.sources?.java?.addGeneratedSourceDirectory(
    generateTestsTask,
    GeneratePreviewScreenshotTestsTask::outputDir
  )
}

private fun setupTestTask(
  testTaskProvider: TaskCollection<Test>,
  project: Project
) {
  testTaskProvider.configureEach { testTask: Test ->
    // see: https://github.com/takahirom/roborazzi?tab=readme-ov-file#roborazzirecordfilepathstrategy
    if (project.properties["roborazzi.record.filePathStrategy"] == null) {
      testTask.systemProperties["roborazzi.record.filePathStrategy"] =
        "relativePathFromRoborazziContextOutputDirectory"
    }
    // see: https://github.com/takahirom/roborazzi?tab=readme-ov-file#robolectricpixelcopyrendermode
    if (testTask.systemProperties["robolectric.pixelCopyRenderMode"] == null) {
      testTask.systemProperties["robolectric.pixelCopyRenderMode"] = "hardware"
    }
  }
}

private fun addPreviewScreenshotLibraryDependencies(
  variant: Variant,
  project: Project
) {
  val configurationName = "test${variant.name.capitalize()}Implementation"

  val roborazziVersion = BuildConfig.libraryVersionsMap["roborazzi"]
  project.dependencies.add(
    configurationName,
    "io.github.takahirom.roborazzi:roborazzi-compose:$roborazziVersion"
  )
  project.dependencies.add(
    configurationName,
    "io.github.takahirom.roborazzi:roborazzi:$roborazziVersion"
  )
  project.dependencies.add(
    configurationName,
    "junit:junit:${BuildConfig.libraryVersionsMap["junit"]}"
  )
  project.dependencies.add(
    configurationName,
    "org.robolectric:robolectric:${BuildConfig.libraryVersionsMap["robolectric"]}"
  )

  // For ComposablePreviewScanner
  project.repositories.add(project.repositories.maven { it.setUrl("https://jitpack.io") })
  project.repositories.add(project.repositories.mavenCentral())
  project.repositories.add(project.repositories.google())
  project.dependencies.add(
    configurationName,
    "com.github.sergio-sastre.ComposablePreviewScanner:android:${BuildConfig.libraryVersionsMap["composable-preview-scanner"]}"
  )
}

abstract class GeneratePreviewScreenshotTestsTask : DefaultTask() {
  @get:OutputDirectory
  abstract val outputDir: DirectoryProperty

  @get:Input
  var scanPackageTrees: ListProperty<String> = project.objects.listProperty(String::class.java)

  @TaskAction
  fun generateTests() {
    val testDir = outputDir.get().asFile
    testDir.mkdirs()

    val packagesExpr = scanPackageTrees.get().joinToString(", ") { "\"$it\"" }
    val scanPackageTreeExpr = ".scanPackageTrees($packagesExpr)"

    File(testDir, "PreviewParameterizedTests.kt").writeText(
      """
            import org.junit.Test
            import org.junit.runner.RunWith
            import org.robolectric.ParameterizedRobolectricTestRunner
            import org.robolectric.annotation.Config
            import org.robolectric.annotation.GraphicsMode
            import com.github.takahirom.roborazzi.captureRoboImage
            import sergio.sastre.composable.preview.scanner.core.preview.ComposablePreview
            import sergio.sastre.composable.preview.scanner.android.AndroidPreviewInfo
            import sergio.sastre.composable.preview.scanner.android.AndroidComposablePreviewScanner
            import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH


            @RunWith(ParameterizedRobolectricTestRunner::class)
            @GraphicsMode(GraphicsMode.Mode.NATIVE)
            class PreviewParameterizedTests(
                private val preview: ComposablePreview<AndroidPreviewInfo>,
            ) {

                companion object {
                    @JvmStatic
                    @ParameterizedRobolectricTestRunner.Parameters
                    fun values(): List<ComposablePreview<AndroidPreviewInfo>> =
                        AndroidComposablePreviewScanner()
                            $scanPackageTreeExpr
                            .getPreviews()
                }
                
                @GraphicsMode(GraphicsMode.Mode.NATIVE)
                @Config(sdk = [30])
                @Test
                fun snapshot() {
                    val filePath = preview.methodName + ".png"
                    captureRoboImage(filePath = filePath) {
                        preview()
                    }
                }

            }
        """.trimIndent()
    )
  }
}
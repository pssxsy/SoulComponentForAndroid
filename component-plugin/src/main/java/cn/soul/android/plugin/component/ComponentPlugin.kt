package cn.soul.android.plugin.component

import cn.soul.android.plugin.component.extesion.ComponentExtension
import cn.soul.android.plugin.component.tasks.transform.RouterCompileTransform
import cn.soul.android.plugin.component.utils.Log
import com.android.build.api.transform.Transform
import com.android.build.gradle.AppPlugin
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.BasePlugin
import com.android.build.gradle.internal.pipeline.TransformManager
import com.android.build.gradle.internal.pipeline.TransformTask
import com.android.build.gradle.internal.scope.GlobalScope
import com.android.build.gradle.options.ProjectOptions
import com.android.builder.profile.Recorder
import com.android.builder.profile.ThreadRecorder
import com.android.utils.StringHelper
import com.google.common.base.CaseFormat
import com.google.wireless.android.sdk.stats.GradleBuildProfileSpan
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.util.stream.Collectors

/**
 * @author panxinghai
 *
 * date : 2019-07-11 11:19
 */
class ComponentPlugin : Plugin<Project> {
    private lateinit var extension: BaseExtension
    private lateinit var pluginExtension: ComponentExtension
    private lateinit var project: Project
    private lateinit var projectOptions: ProjectOptions
    private lateinit var threadRecorder: Recorder
    private lateinit var taskManager: TaskManager
    private var globalScope: GlobalScope? = null

    override fun apply(p: Project) {
        project = p
        projectOptions = ProjectOptions(p)
        threadRecorder = ThreadRecorder.get()
        taskManager = TaskManager(p)
        Log.p(msg = "apply component plugin.")

        project.extensions.findByType(BaseExtension::class.java)?.registerTransform(RouterCompileTransform())
        pluginExtension = project.extensions.create("component", ComponentExtension::class.java)
        project.afterEvaluate {
            pluginExtension.ensureComponentExtension(project)

            threadRecorder.record(
                    GradleBuildProfileSpan.ExecutionType.BASE_PLUGIN_PROJECT_CONFIGURE,
                    project.path,
                    null,
                    this::configureProject
            )
            threadRecorder.record(
                    GradleBuildProfileSpan.ExecutionType.BASE_PLUGIN_PROJECT_BASE_EXTENSION_CREATION,
                    project.path,
                    null,
                    this::configureExtension
            )
            threadRecorder.record(
                    GradleBuildProfileSpan.ExecutionType.BASE_PLUGIN_PROJECT_TASKS_CREATION,
                    project.path,
                    null,
                    this::createTasks
            )
        }
    }

    private fun configureProject() {
        Log.p(msg = "configure project.")
        val gradle = project.gradle
        //根据本次任务执行的task判断是否需要添加动态依赖
//        gradle.taskGraph.whenReady {
//            val lastTask = it.allTasks.last()
//            if (taskManager.isComponentTask(lastTask)) {
//                return@whenReady
//            }
//            val lastTransform = extension.transforms[extension.transforms.lastIndex]
//            val task = it.allTasks.find { task -> task.name.startsWith(getTaskNamePrefix(lastTransform, "")) }
//                    ?: throw RuntimeException("cannot get task of transform named:${lastTask.name}")
//
//            task.doLast {
//
//                Log.e(" add doLast")
//                pluginExtension.dependencies.resolveDependencies(pluginExtension)
//                pluginExtension.dependencies.dependenciesCollection.forEach { file ->
//                    project.dependencies.add("implementation", file)
//                }
//            }
//        }
    }

    private fun configureExtension() {
        Log.p(msg = "configure extension.")
        val appPlugin = project.plugins.getPlugin(AppPlugin::class.java) as BasePlugin<*>
        val variantManager = appPlugin.variantManager
        val scope = variantManager.variantScopes[0]
        val realScope = scope.globalScope
        globalScope = GlobalScope(
                realScope.project,
                realScope.filesProvider,
                realScope.projectOptions,
                realScope.dslScope,
                realScope.androidBuilder,
                realScope.sdkHandler,
                realScope.toolingRegistry,
                realScope.buildCache
        )
        extension = scope.globalScope.extension as BaseExtension
        globalScope?.extension = extension
    }

    private fun createTasks() {
        Log.p(msg = "create tasks.")
        val appPlugin = project.plugins.getPlugin(AppPlugin::class.java) as BasePlugin<*>
        val variantManager = appPlugin.variantManager
        variantManager.variantScopes.forEach {
            val variantType = it.variantData.type
            if (variantType.isTestComponent) {
                //这里是continue,不给test的variant创建task
                return@forEach
            }
            val transformManager = TransformManager(project, globalScope!!.errorHandler, threadRecorder)


            val pluginVariantScope = PluginVariantScopeImpl(it, globalScope!!, extension, transformManager, pluginExtension)

            taskManager.createDependencyStreams(pluginVariantScope, transformManager)

            taskManager.createAnchorTasks(pluginVariantScope)

            taskManager.createCheckManifestTask(pluginVariantScope)
            taskManager.createMergeLibManifestsTask(pluginVariantScope)

            taskManager.createAidlTask(pluginVariantScope)

            taskManager.createMergeResourcesTask(pluginVariantScope)

            val lastTransform = extension.transforms[extension.transforms.lastIndex]
            val task = project.tasks.getByName(getTaskNamePrefix(lastTransform, pluginVariantScope.fullVariantName))

            val transformTask = task as TransformTask
            transformTask.doLast {
                Log.e("do last")
                if (taskManager.isComponentTask(project.gradle.taskGraph.allTasks.last())) {
                    return@doLast
                }
                pluginExtension.dependencies.resolveDependencies(pluginExtension)
                pluginExtension.dependencies.dependenciesCollection.forEach { file ->
                    project.dependencies.add("implementation", project.files(file))
                }
            }

            val javaOutputs = project.files(transformTask.streamOutputFolder).builtBy(transformTask)
            taskManager.addJavacClassesStream(javaOutputs, pluginVariantScope)

            taskManager.transform(pluginVariantScope)

            taskManager.createRefineManifestTask(pluginVariantScope)

            taskManager.createBundleTask(pluginVariantScope)

            taskManager.createUploadTask(pluginVariantScope)
        }
    }

    private fun getTaskNamePrefix(transform: Transform, variant: String): String {
        val sb = StringBuilder(100)
        sb.append("transform")
        sb.append(
                transform
                        .inputTypes
                        .stream()
                        .map { inputType ->
                            CaseFormat.UPPER_UNDERSCORE.to(
                                    CaseFormat.UPPER_CAMEL, inputType.name())
                        }
                        .sorted() // Keep the order stable.
                        .collect(Collectors.joining("And")))
        sb.append("With")
        StringHelper.appendCapitalized(sb, transform.name)
        sb.append("For")
                .append(variant.capitalize())

        return sb.toString()
    }
}
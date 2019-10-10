package cn.soul.android.plugin.component

import cn.soul.android.plugin.component.tasks.*
import com.android.build.gradle.internal.scope.TaskContainer
import org.gradle.api.Task


/**
 * @author panxinghai
 *
 * date : 2019-07-11 15:26
 */
class PluginTaskContainer(container: TaskContainer) : TaskContainer by container {
    var pluginCheckManifestTask: CheckManifest? = null
    var pluginProcessManifest: ProcessManifest? = null
    var pluginRefineManifest: RefineManifest? = null
    var pluginAidlCompile: AidlCompile? = null
    var pluginMergeResourcesTask: MergeResources? = null
    var pluginPrefixResources: PrefixResources? = null
    var pluginBundleAarTask: BundleAar? = null
    var pluginUploadTask: Task? = null
    var pluginGenerateSymbol: GenerateSymbol? = null
    var pluginGenInterface: GenerateInterfaceArtifact? = null

    //anchor task
    var resourceGenTask: Task? = null
    var assetGenTask: Task? = null
    var sourceGenTask: Task? = null
}
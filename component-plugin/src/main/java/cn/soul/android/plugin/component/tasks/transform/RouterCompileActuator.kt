package cn.soul.android.plugin.component.tasks.transform

import cn.soul.android.component.Constants
import cn.soul.android.component.annotation.Inject
import cn.soul.android.component.annotation.Router
import cn.soul.android.component.exception.InjectTypeException
import cn.soul.android.component.node.NodeType
import cn.soul.android.component.template.IRouterLazyLoader
import cn.soul.android.component.template.IRouterNodeProvider
import cn.soul.android.plugin.component.extesion.ComponentExtension
import cn.soul.android.plugin.component.manager.InjectType
import cn.soul.android.plugin.component.utils.InjectHelper
import cn.soul.android.plugin.component.utils.Log
import cn.soul.android.plugin.component.utils.javassist.MethodGen
import com.android.build.api.transform.Format
import com.android.build.api.transform.TransformInvocation
import com.android.build.gradle.LibraryPlugin
import com.android.build.gradle.internal.pipeline.TransformManager
import com.android.build.gradle.internal.publishing.AndroidArtifacts
import javassist.*
import javassist.bytecode.FieldInfo
import javassist.bytecode.SignatureAttribute
import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.File
import java.util.*
import java.util.zip.ZipEntry

/**
 * All router relative code is in here.This class help inject code for Router Jump.
 * This actuator support Activity, Fragment, IComponentService Node now.
 * @author panxinghai
 *
 * date : 2019-11-18 18:44
 */
class RouterCompileActuator(private val project: Project,
                            isComponent: Boolean) : TypeActuator(isComponent) {
    private val nodeMapByGroup = mutableMapOf<String, ArrayList<NodeInfo>>()
    private val groupMap = mutableMapOf<String, ArrayList<String>>()

    override fun preTraversal(transformInvocation: TransformInvocation) {
        InjectHelper.instance.refresh()
        InjectHelper.instance.appendAndroidPlatformPath(project)
        if (!isComponent) {
            return
        }
        val variantName = transformInvocation.context.variantName
        println(variantName)
        val libPlugin = project.plugins.getPlugin(LibraryPlugin::class.java) as LibraryPlugin
        //cannot got jar file input in library Transform, so got them by variantManager
        libPlugin.variantManager.variantScopes.forEach {
            if (it.fullVariantName != variantName) {
                return@forEach
            }
            it.getArtifactCollection(
                    AndroidArtifacts.ConsumedConfigType.RUNTIME_CLASSPATH,
                    AndroidArtifacts.ArtifactScope.EXTERNAL,
                    AndroidArtifacts.ArtifactType.CLASSES)
                    .artifactFiles.files
                    .forEach { file ->
                        InjectHelper.instance.appendClassPath(file.absolutePath)
                    }
        }
    }

    override fun preTransform(transformInvocation: TransformInvocation) {
    }

    override fun onClassVisited(ctClass: CtClass,
                                transformInvocation: TransformInvocation): Boolean {
        //traversal all .class file and find the class which annotate by Router, record router path
        //and Class for RouterNode construction
        if (!ctClass.hasAnnotation(Router::class.java)) {
            return false
        }
        val routerAnnotation = ctClass.getAnnotation(Router::class.java) as Router
        val path = routerAnnotation.path
        val nodeInfo = NodeInfo(path, ctClass)
        nodeMapByGroup.computeIfAbsent(getGroupWithPath(path, ctClass.name)) {
            arrayListOf()
        }.add(nodeInfo)
        return insertInjectImplement(nodeInfo)
    }

    override fun onJarEntryVisited(zipEntry: ZipEntry,
                                   transformInvocation: TransformInvocation) {
        if (isComponent) {
            return
        }
        if (zipEntry.name.startsWith(Constants.ROUTER_GEN_FILE_FOLDER)) {
            groupMap.computeIfAbsent(getGroupWithEntryName(zipEntry.name)) {
                arrayListOf()
            }.add(getNameWithEntryName(zipEntry.name))
            groupMap.forEach {
                Log.d("${it.key} : ${Arrays.toString(it.value.toArray())}")
            }
        }
        return
    }

    override fun postTransform(transformInvocation: TransformInvocation) {
        val dest = transformInvocation.outputProvider.getContentLocation(
                "routerGen",
                TransformManager.CONTENT_CLASS,
                TransformManager.PROJECT_ONLY,
                Format.DIRECTORY)

        if (nodeMapByGroup.isNotEmpty()) {
            nodeMapByGroup.forEach {
                genRouterProviderImpl(dest, it.key, it.value)
            }
        }
        if (!isComponent) {
            nodeMapByGroup.forEach {
                groupMap.computeIfAbsent(it.key) {
                    arrayListOf()
                }.add(genRouterProviderClassName(it.key))
            }
            InjectHelper.instance.getClassPool().appendClassPath(ClassClassPath(IRouterLazyLoader::class.java))
            InjectHelper.instance.getClassPool().appendClassPath(ClassClassPath(IRouterNodeProvider::class.java))
            Log.d("generate lazy loader")
            genRouterLazyLoaderImpl(dest, groupMap)
        }
    }

    /**
     *
     * @return if return true, this class need writeFile()
     */
    private fun insertInjectImplement(nodeInfo: NodeInfo): Boolean {
        val injectInfoList = arrayListOf<InjectInfo>()
        val classPool = InjectHelper.instance.getClassPool()
        if (nodeInfo.type != NodeType.ACTIVITY && nodeInfo.type != NodeType.FRAGMENT) {
            //one inject for activity and fragment
            return false
        }

        val ctClass = nodeInfo.ctClass
        ctClass.declaredFields.forEach {
            var inject = it.getAnnotation(Inject::class.java) ?: return@forEach
            if (Modifier.isFinal(it.modifiers)) {
                Log.e("skip field ${ctClass.simpleName}.${it.name} inject: cannot inject value for final field.")
                return@forEach
            }
            inject = inject as Inject
            val annotationName = if (inject.name != "") {
                inject.name
            } else {
                it.name
            }
            injectInfoList.add(InjectInfo(it, annotationName, ctClass))
        }
        if (injectInfoList.size > 0) {
            val isFragment = nodeInfo.type == NodeType.FRAGMENT
            try {
                val method = ctClass.getDeclaredMethod("autoSynthetic\$FieldInjectSoulComponent")
                method.setBody(produceInjectMethod(isFragment, injectInfoList))
            } catch (e: Exception) {
                ctClass.addInterface(classPool[Constants.INJECTABLE_CLASSNAME])
                ctClass.addMethod(genAutoSyntheticInjectMethod(isFragment, injectInfoList, ctClass))
            }
        }
        return injectInfoList.size > 0
    }

    private fun genAutoSyntheticInjectMethod(isFragment: Boolean, infoList: ArrayList<InjectInfo>, ctClass: CtClass): CtMethod {
        return CtMethod.make("public void autoSynthetic\$FieldInjectSoulComponent()${produceInjectMethod(isFragment, infoList)}", ctClass)
    }

    private fun produceInjectMethod(isFragment: Boolean, infoList: ArrayList<InjectInfo>): String {
        val stringBuilder = StringBuilder("{")
        if (infoList.size > 0) {
            stringBuilder.append("if(!(\$0 instanceof ${Constants.INJECTABLE_CLASSNAME})){return;}")
            stringBuilder.append(
                    if (!isFragment) "android.content.Intent var = getIntent();"
                    else "android.os.Bundle var = getArguments();")
                    .append("if(var == null){return;}")
            infoList.forEach {
                stringBuilder.append("${it.fieldName} = ")
                        .append(getPrefixStatement(it))
                        .append(getExtraStatement(isFragment, it))
            }
        }
        stringBuilder.append("}")
        return stringBuilder.toString()
    }

    private fun getPrefixStatement(injectInfo: InjectInfo): String {
        val type = injectInfo.type
        val classType = injectInfo.classType
        return when (InjectType.values()[type]) {
            InjectType.SERIALIZABLE, InjectType.PARCELABLE_ARRAY, InjectType.PARCELABLE -> "(${classType.name})var."
            else -> "var."
        }
    }

    private fun getExtraStatement(isFragment: Boolean, injectInfo: InjectInfo): String {
        val type = injectInfo.type
        val annotationName = injectInfo.annotationName
        val fieldName = injectInfo.fieldName
        return when (InjectType.values()[type]) {
            InjectType.INTEGER -> {
                if (isFragment) "getInt(\"$annotationName\", $fieldName);"
                else "getIntExtra(\"$annotationName\", $fieldName);"
            }
            InjectType.INT_ARRAY -> {
                if (isFragment) "getIntArray(\"$annotationName\");"
                else "getIntArrayExtra(\"$annotationName\");"
            }
            InjectType.INT_LIST -> {
                if (isFragment) "getIntegerArrayList(\"$annotationName\");"
                else "getIntegerArrayListExtra(\"$annotationName\");"
            }
            InjectType.BOOLEAN -> {
                if (isFragment) "getBoolean(\"$annotationName\", $fieldName);"
                else "getBooleanExtra(\"$annotationName\", $fieldName);"
            }
            InjectType.BOOLEAN_ARRAY -> {
                if (isFragment) "getBooleanArray(\"$annotationName\");"
                else "getBooleanArrayExtra(\"$annotationName\");"
            }
            InjectType.STRING -> {
                if (isFragment) "getString(\"$annotationName\");"
                else "getStringExtra(\"$annotationName\");"
            }
            InjectType.STRING_ARRAY -> {
                if (isFragment) "getStringArray(\"$annotationName\");"
                else "getStringArrayExtra(\"$annotationName\");"
            }
            InjectType.STRING_LIST -> {
                if (isFragment) "getStringArrayList(\"$annotationName\");"
                else "getStringArrayListExtra(\"$annotationName\");"
            }
            InjectType.CHARACTER -> {
                if (isFragment) "getChar(\"$annotationName\", $fieldName);"
                else "getCharExtra(\"$annotationName\", $fieldName);"
            }
            InjectType.CHAR_ARRAY -> {
                if (isFragment) "getCharArray(\"$annotationName\");"
                else "getCharArrayExtra(\"$annotationName\");"
            }
            InjectType.SERIALIZABLE -> {
                if (isFragment) "getSerializable(\"$annotationName\");"
                else "getSerializableExtra(\"$annotationName\");"
            }
            InjectType.PARCELABLE -> {
                if (isFragment) "getParcelable(\"$annotationName\");"
                else "getParcelableExtra(\"$annotationName\");"
            }
            InjectType.PARCELABLE_ARRAY -> {
                if (isFragment) "getParcelableArray(\"$annotationName\");"
                else "getParcelableArrayExtra(\"$annotationName\");"
            }
            InjectType.PARCELABLE_LIST -> {
                if (isFragment) "getParcelableArrayList(\"$annotationName\");"
                else "getParcelableArrayListExtra(\"$annotationName\");"
            }
            InjectType.BYTE -> {
                if (isFragment) "getByte(\"$annotationName\", $fieldName);"
                else "getByteExtra(\"$annotationName\", $fieldName);"
            }
            InjectType.BYTE_ARRAY -> {
                if (isFragment) "getByteArray(\"$annotationName\");"
                else "getByteArrayExtra(\"$annotationName\");"
            }
            InjectType.DOUBLE -> {
                if (isFragment) "getDouble(\"$annotationName\", $fieldName);"
                else "getDoubleExtra(\"$annotationName\", $fieldName);"
            }
            InjectType.DOUBLE_ARRAY -> {
                if (isFragment) "getDoubleArray(\"$annotationName\");"
                else "getDoubleArrayExtra(\"$annotationName\");"
            }
            InjectType.FLOAT -> {
                if (isFragment) "getFloat(\"$annotationName\", $fieldName);"
                else "getFloatExtra(\"$annotationName\", $fieldName);"
            }
            InjectType.FLOAT_ARRAY -> {
                if (isFragment) "getFloatArray(\"$annotationName\");"
                else "getFloatArrayExtra(\"$annotationName\");"
            }
            InjectType.LONG -> {
                if (isFragment) "getLong(\"$annotationName\", $fieldName);"
                else "getLongExtra(\"$annotationName\", $fieldName);"
            }
            InjectType.LONG_ARRAY -> {
                if (isFragment) "getLongArray(\"$annotationName\");"
                else "getLongArrayExtra(\"$annotationName\");"
            }
            InjectType.SHORT -> {
                if (isFragment) "getShort(\"$annotationName\", $fieldName);"
                else "getShortExtra(\"$annotationName\", $fieldName);"
            }
            InjectType.SHORT_ARRAY -> {
                if (isFragment) "getShortArray(\"$annotationName\");"
                else "getShortArrayExtra(\"$annotationName\");"
            }
            InjectType.CHAR_SEQUENCE -> {
                if (isFragment) "getCharSequence(\"$annotationName\");"
                else "getCharSequenceExtra(\"$annotationName\");"
            }
            InjectType.CHAR_SEQUENCE_ARRAY -> {
                if (isFragment) "getCharSequenceArray(\"$annotationName\");"
                else "getCharSequenceArrayExtra(\"$annotationName\");"
            }
            InjectType.CHAR_SEQUENCE_LIST -> {
                if (isFragment) "getCharSequenceArrayList(\"$annotationName\");"
                else "getCharSequenceArrayListExtra(\"$annotationName\");"
            }
        }
    }

    private fun genRouterProviderImpl(dir: File, group: String, nodeList: List<NodeInfo>) {
        MethodGen(Constants.ROUTER_GEN_FILE_PACKAGE + genRouterProviderClassName(group))
                .interfaces(IRouterNodeProvider::class.java)
                .signature(returnStatement = "public java.util.List",
                        name = "getRouterNodes")
                .body { produceNodesMethodBodySrc(nodeList) }
                .gen()?.writeFile(dir.absolutePath)
    }

    private fun produceNodesMethodBodySrc(nodeList: List<NodeInfo>): String {
        val builder = StringBuilder("{")
                .append("java.util.ArrayList list = new java.util.ArrayList();")
        nodeList.forEach {
            val typeStr = "${Constants.NODE_TYPE_CLASSNAME}.${it.type}"
            builder.append("list.add(${Constants.NODE_FACTORY_CLASSNAME}.produceRouterNode($typeStr, \"${it.path}\", ${it.ctClass.name}.class));")
        }
        builder.append("return list;")
                .append("}")
        return builder.toString()
    }

    private fun genRouterLazyLoaderImpl(dir: File, groupMap: MutableMap<String, ArrayList<String>>) {
        val name = Constants.ROUTER_GEN_FILE_PACKAGE + Constants.LAZY_LOADER_IMPL_NAME
        MethodGen(name)
                .interfaces(IRouterLazyLoader::class.java)
                .signature("public java.util.List",
                        "lazyLoadFactoryByGroup",
                        "(String group)")
                .body { produceLazyLoadFactoryBodySrc(groupMap) }
                .gen()?.writeFile(dir.absolutePath)
    }

    private fun produceLazyLoadFactoryBodySrc(groupMap: MutableMap<String, ArrayList<String>>): String {
        val sb = StringBuilder("{")
                .append("java.util.ArrayList result =")
                .appendln("new java.util.ArrayList();")
        if (groupMap.isNotEmpty()) {
            sb.append("switch(\$1.hashCode()) {")
            groupMap.forEach {
                val group = it.key
                sb.append("case ${group.hashCode()}:{")
                it.value.forEach { name ->
                    sb.append("result.add(new ${Constants.ROUTER_GEN_FILE_PACKAGE}$name());")
                }
                sb.append("return result;}")
            }
            sb.append("default:break;}")
        }
        sb.append("return result;}")
        return sb.toString()
    }

    private fun genRouterProviderClassName(group: String): String {
        val componentName = getComponentExtension().componentName
        return "$group\$$componentName\$NodeProvider"
    }

    private fun getComponentExtension(): ComponentExtension {
        return project.extensions.findByType(ComponentExtension::class.java)
                ?: throw GradleException("can not find ComponentExtension, please check your build.gradle file")
    }

    private fun getGroupWithPath(path: String, clazz: String): String {
        val strings = path.split('/')
        if (strings.size < 3) {
            throw GradleException("invalid router path: \"$path\" in $clazz. Router Path must starts with '/' and has a group segment")
        }
        return strings[1]
    }

    private fun getGroupWithEntryName(name: String): String {
        return name.split('/').last().split('$')[0]
    }

    private fun getNameWithEntryName(name: String): String {
        return name.split('/').last().split('.')[0]
    }

    data class NodeInfo(val path: String,
                        val ctClass: CtClass) {
        val type = getNodeType(ctClass)

        private fun getNodeType(ctClass: CtClass): NodeType {
            val classPool = InjectHelper.instance.getClassPool()
            NodeType.values().forEach { nodeType ->
                nodeType.supportClasses().forEach support@{
                    if (it == "") {
                        return@support
                    }
                    try {
                        val supportClass = classPool[it]
                        if (ctClass.subtypeOf(supportClass)) {
                            return nodeType
                        }
                    } catch (e: Exception) {
                        Log.e("cannot got $it in ctClass when check node type. " + e.message)
                    }
                }
            }
            return NodeType.UNSPECIFIED
        }
    }

    data class InjectInfo(val ctField: CtField,
                          val annotationName: String,
                          private val ctClass: CtClass) {
        companion object {
            private val classPool = InjectHelper.instance.getClassPool()
            private val serializableType = classPool["java.io.Serializable"]
            private val parcelableType = classPool["android.os.Parcelable"]
            private val charSequenceType = classPool["java.lang.CharSequence"]
        }

        var type: Int = 0
        val fieldName: String = ctField.name
        val classType: CtClass = ctField.type

        init {
            type = initType()
        }

        /**
         * initType() method will decide and check the type of the field which transport by bundle
         */
        private fun initType(): Int {
            return when (classType.name) {
                "int", "java.lang.Integer" -> InjectType.INTEGER.ordinal
                "boolean", "java.lang.Boolean" -> InjectType.BOOLEAN.ordinal
                "java.lang.String" -> InjectType.STRING.ordinal
                "char", "java.lang.Character" -> InjectType.CHARACTER.ordinal
                "float", "java.lang.Float" -> InjectType.FLOAT.ordinal
                "double", "java.lang.Double" -> InjectType.DOUBLE.ordinal
                "byte", "java.lang.Byte" -> InjectType.BYTE.ordinal
                "long", "java.lang.Long" -> InjectType.LONG.ordinal
                "short", "java.lang.Short" -> InjectType.SHORT.ordinal

                //keep these for abstract type field
                "java.io.Serializable" -> InjectType.SERIALIZABLE.ordinal
                "android.os.Parcelable" -> InjectType.PARCELABLE.ordinal
                "java.lang.CharSequence" -> InjectType.CHAR_SEQUENCE.ordinal

                "int[]" -> InjectType.INT_ARRAY.ordinal
                "boolean[]" -> InjectType.BOOLEAN_ARRAY.ordinal
                "java.lang.String[]" -> InjectType.STRING_ARRAY.ordinal
                "char[]" -> InjectType.CHAR_ARRAY.ordinal
                "float[]" -> InjectType.FLOAT_ARRAY.ordinal
                "double[]" -> InjectType.DOUBLE_ARRAY.ordinal
                "byte[]" -> InjectType.BYTE_ARRAY.ordinal
                "long[]" -> InjectType.LONG_ARRAY.ordinal
                "short[]" -> InjectType.SHORT_ARRAY.ordinal
                "java.lang.CharSequence[]" -> InjectType.CHAR_SEQUENCE_ARRAY.ordinal
                "android.os.Parcelable[]" -> InjectType.PARCELABLE_ARRAY.ordinal

                //process put[...]ArrayList type
                "java.util.ArrayList" -> {
                    val fieldInfo = ctClass.classFile.fields.find { it is FieldInfo && it.name == fieldName } as FieldInfo
                    val sa = fieldInfo.getAttribute(SignatureAttribute.tag) as SignatureAttribute
                    //do this for get generic type int ArrayList,
                    //check this type whether fit for bundle support type
                    val type = SignatureAttribute.toFieldSignature(sa.signature).jvmTypeName()
                    return when (val genericType = extractGeneric(type)) {
                        "java.lang.String" -> InjectType.STRING_LIST.ordinal
                        "java.lang.Integer" -> InjectType.INT_LIST.ordinal
                        //keep this for abstract
                        "java.lang.CharSequence" -> InjectType.CHAR_SEQUENCE_LIST.ordinal
                        else -> {
                            val ctClass = classPool[genericType]
                            when {
                                ctClass.subtypeOf(parcelableType) -> InjectType.PARCELABLE_LIST.ordinal
                                ctClass.subtypeOf(charSequenceType) -> InjectType.CHAR_SEQUENCE_LIST.ordinal
                                else -> throw InjectTypeException(ctClass.name, fieldName, type)
                            }
                        }
                    }
                }
                else -> {
                    when {
                        //process put[...]Array type
                        classType.isArray && classType.componentType.subtypeOf(parcelableType) -> InjectType.PARCELABLE_ARRAY.ordinal
                        classType.isArray && classType.componentType.subtypeOf(charSequenceType) -> InjectType.CHAR_SEQUENCE_ARRAY.ordinal
                        //process putParcelable/putSerializable type
                        classType.subtypeOf(parcelableType) -> InjectType.PARCELABLE.ordinal
                        classType.subtypeOf(serializableType) -> InjectType.SERIALIZABLE.ordinal
                        else -> throw InjectTypeException(ctClass.name, fieldName, classType.name)
                    }
                }
            }
        }

        private fun extractGeneric(s: String): String {
            val startIndex = s.indexOf('<') + 1
            val endIndex = s.lastIndexOf('>')
            return s.substring(startIndex, endIndex).trim()
        }
    }
}
package cn.soul.android.plugin.component.tasks.transform

import cn.soul.android.plugin.component.resolve.PrefixHelper
import cn.soul.android.plugin.component.utils.InjectHelper
import cn.soul.android.plugin.component.utils.Log
import com.android.build.api.transform.TransformInvocation
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.LibraryPlugin
import javassist.CtClass
import javassist.expr.ExprEditor
import javassist.expr.FieldAccess
import org.gradle.api.Project
import java.util.zip.ZipEntry

/**
 * @author panxinghai
 *
 * date : 2019-11-19 11:05
 */
class PrefixRActuator(private val project: Project,
                      isComponent: Boolean) : TypeActuator(isComponent) {
    private var applicationId = ""
    private var prefix: String? = null

    override fun preTraversal(transformInvocation: TransformInvocation) {
        prefix = PrefixHelper.instance.prefix
    }

    override fun preTransform(transformInvocation: TransformInvocation) {
        val p = project
        val variantName = transformInvocation.context.variantName
        val libPlugin = p.plugins.getPlugin(LibraryPlugin::class.java) as LibraryPlugin
        (libPlugin.extension as LibraryExtension).libraryVariants.all {
            if (it.name == variantName) {
                applicationId = it.applicationId
                Log.d("applicationId:$applicationId")
            }
        }
        val rCtClass = InjectHelper.instance.getClassPool()["$applicationId.R"]
        prefixCustomCtClassField(rCtClass)
    }

    override fun onClassVisited(ctClass: CtClass, transformInvocation: TransformInvocation): Boolean {
        prefixRClassFieldAccess(ctClass, applicationId)
        return true
    }

    override fun onJarEntryVisited(zipEntry: ZipEntry, transformInvocation: TransformInvocation) {
    }

    override fun postTransform(transformInvocation: TransformInvocation) {
    }

    private fun prefixCustomCtClassField(ctClass: CtClass) {
        Log.d("prefix R.class field access. which class is: ${ctClass.name}")
        ctClass.nestedClasses.forEach {
            it.fields.forEach { ctField ->
                if (it.isFrozen) {
                    it.defrost()
                }
                //eg:it.simpleName = "R$id"
                if (PrefixHelper.instance.isRefNeedPrefix(it.simpleName.substring(2), ctField.name)) {
                    ctField.name = "$prefix${ctField.name}"
                }
            }
        }
        ctClass.nestedClasses.forEach {
            it.fields.forEach { ctField ->
                if (it.isFrozen) {
                    it.defrost()
                }
                if (it.simpleName != "R\$layout") {
                    return@forEach
                }
                //eg:it.simpleName = "R$id"
                println(ctField.fieldInfo.name + ":" + ctField.constantValue)
            }
        }
    }

    private fun prefixRClassFieldAccess(ctClass: CtClass, applicationId: String) {
        if (prefix == null) {
            return
        }
        if (isRFile(ctClass.simpleName)) {
            //skip R.class's field access prefix
            return
        }
        if (ctClass.isFrozen) {
            ctClass.defrost()
        }
        println("traversal start")
        ctClass.instrument(object : ExprEditor() {
            override fun edit(f: FieldAccess?) {
                if (f == null) {
                    return
                }
                println(f.isReader.toString() + ":" + f.className + ":" + f.fieldName)
                if (f.isReader && needPrefix(f.className, f.fieldName, applicationId)) {
                    println(":\$_ = ${f.className}.$prefix${f.fieldName};")
                    f.replace("\${f.className}.$prefix${f.fieldName} ;")
                }
            }
        })
        println("traversal again")
        ctClass.instrument(object : ExprEditor() {
            override fun edit(f: FieldAccess?) {
                if (f == null) {
                    return
                }
                println(f.isReader.toString() + ":" + f.className + ":" + f.fieldName)
            }
        })
    }

    private fun isRFile(name: String): Boolean = name == "R" || name.startsWith("R$")

    private fun needPrefix(fullName: String, ref: String, applicationId: String): Boolean {
        if (!isCustomRFile(fullName, applicationId)) {
            return false
        }
        val strings = fullName.split('$')
        if (strings.size <= 1) {
            return false
        }
        val rName = strings[1]
        return PrefixHelper.instance.isRefNeedPrefix(rName, ref)
    }

    private fun isCustomRFile(name: String, applicationId: String) = name.startsWith("$applicationId.R")

}
package cn.soul.android.plugin.component.tasks

import cn.soul.android.plugin.component.PluginVariantScope
import cn.soul.android.plugin.component.custom.AdaptiveIconPrefix
import cn.soul.android.plugin.component.custom.BitmapPrefix
import cn.soul.android.plugin.component.custom.IElementPrefix
import cn.soul.android.plugin.component.custom.SelectorPrefix
import cn.soul.android.plugin.component.utils.Log
import com.android.build.gradle.internal.scope.TaskConfigAction
import com.android.build.gradle.internal.tasks.AndroidVariantTask
import org.dom4j.Attribute
import org.dom4j.Element
import org.dom4j.io.SAXReader
import org.dom4j.io.XMLWriter
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.io.FileWriter
import java.util.*

/**
 * Created by nebula on 2019-08-15
 */
open class PrefixResources : AndroidVariantTask() {
    var packagedResFolder: File? = null
    var prefix: String = ""
    private val reader: SAXReader = SAXReader()
    private val prefixHandleMap: MutableMap<String, IElementPrefix> = mutableMapOf()
    private var valuesCount = 0
    private val typeList = listOf(
//            "style",
//            "styleable",
//            "bool",
//            "attr",
            "string",
            "mipmap",
            "layout",
            "integer",
            "id",
            "+id",
            "drawable",
            "dimen",
            "color",
            "array",
            "anim")

    @TaskAction
    fun taskAction() {
        val startTime = System.currentTimeMillis()
        val folder = packagedResFolder ?: return
        folder.listFiles()?.forEach {
            if (it.isDirectory) {
                it.listFiles()?.forEach { subFile ->
                    if (subFile.name.endsWith(".xml")) {
                        refineXmlFile(subFile)
                    }
                }
            } else if (it.name.endsWith(".xml")) {
                refineXmlFile(it)
            }
        }
        Log.i("prefix resources cost: ${System.currentTimeMillis() - startTime}ms")
    }

    fun putNodePrefix(elementPrefix: IElementPrefix) {
        prefixHandleMap[elementPrefix.elementName()] = elementPrefix
    }

    private fun refineXmlFile(xmlFile: File) {
        val document = reader.read(xmlFile)
        val root = document.rootElement

        if (xmlFile.parentFile.name.startsWith("layout")) {
            prefixLayouts(root)
            writeFile(xmlFile, root)
            return
        }
        if (root.name == "resources") {
            //process values.xml
            prefixResources(root)
            writeFile(xmlFile, root)
            return
        }
        val rootElementPrefix = prefixHandleMap[root.name] ?: return
        Log.e("${xmlFile.name}   :   $root")
        prefixElement(root, rootElementPrefix)
        traversalElementByElementPrefix(root, rootElementPrefix.childElementPrefixes())
        writeFile(xmlFile, root)
    }

    private fun writeFile(xmlFile: File, root: Element) {
        FileWriter(xmlFile).use {
            XMLWriter(it).apply {
                write(root)
            }
        }
    }

    private fun prefixLayouts(root: Element) {
        Log.e(root.name)
        elementTraversal(root) {
            it.attributes().forEach { attr ->
                if (attr.text.startsWith('@')) {
                    attr.text = prefixElementText(attr.text)
                    println(attr.text)
                }
            }
            return@elementTraversal true
        }
    }

    private fun traversalElementByElementPrefix(element: Element, elementPrefixes: List<IElementPrefix>) {
        if (elementPrefixes.isEmpty()) {
            return
        }
        elementPrefixes.forEach {
            val elements = element.elements(it.elementName())
            prefixElements(elements, it)
            elements.forEach { element ->
                Log.e(element.name + ":" + it.elementName())
                traversalElementByElementPrefix(element, it.childElementPrefixes())
            }
        }
    }

    private fun prefixElement(element: Element, elementPrefix: IElementPrefix) {
        element.attributeIterator().forEach { attr ->
            if (attrNeedPrefix(attr, elementPrefix)) {
                attr.value = prefixReferenceText(attr.value, elementPrefix)
            }
        }
    }

    private fun prefixElements(elements: List<Element>, elementPrefix: IElementPrefix) {
        elements.forEach {
            prefixElement(it, elementPrefix)
        }
    }

    private fun attrNeedPrefix(attr: Attribute, elementPrefix: IElementPrefix): Boolean {
        elementPrefix.targetAttrQNameList().forEach {
            if (attr.qName == it) {
                return true
            }
        }
        return false
    }

    private fun elementTraversal(root: Element, callback: (Element) -> Boolean) {
        if (!callback.invoke(root)) {
            return
        }
        root.elementIterator().forEach {
            elementTraversal(it, callback)
        }
    }

    private fun prefixResources(root: Element) {
        root.elementIterator().forEach {
            valuesCount++
            if (it.name == "declare-styleable") {
                return@forEach
            }
            val attribute = it.attribute("name")
            attribute.value = prefix + attribute.value
            if (it.text.startsWith('@')) {
                it.text = prefixElementText(it.text)
                println(it.text)
            }
        }
    }

    private fun prefixElementText(text: String): String {
        typeList.forEach {
            if (text.startsWith("@$it/")) {
                return "@$it/$prefix${text.substring(it.length + 2)}"
            }
        }
        return text
    }

    private fun prefixReferenceText(text: String, elementPrefix: IElementPrefix): String {
        typeList.forEach {
            if (text.startsWith("@$it/")) {
                return elementPrefix.prefix("@$it/", text.substring(it.length + 2), prefix)
            }
        }
        return text
    }


    class ConfigAction(private val scope: PluginVariantScope,
                       private val packagedResFolder: File,
                       private val prefix: String) : TaskConfigAction<PrefixResources> {
        override fun getType(): Class<PrefixResources> {
            return PrefixResources::class.java
        }

        override fun getName(): String {
            return scope.getTaskName("prefix", "Resources")
        }

        override fun execute(task: PrefixResources) {
            task.variantName = scope.fullVariantName
            task.packagedResFolder = packagedResFolder
            task.prefix = prefix
            //add custom prefix strategy
            val list = mutableListOf<IElementPrefix>()
            list.add(BitmapPrefix())
            list.add(SelectorPrefix())
            list.add(AdaptiveIconPrefix())
            list.forEach {
                task.putNodePrefix(it)
            }
        }
    }
}
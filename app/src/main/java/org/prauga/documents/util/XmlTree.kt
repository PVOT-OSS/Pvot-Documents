package org.prauga.documents.util

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/**
 * A small in-memory XML element, for parts that are read by looking things up rather than in one
 * pass: a slide inherits positions, text styles and colors from its layout and master, which is
 * far simpler to resolve over trees than over streams. Text keeps its place among the children
 * as [TEXT] nodes, so mixed content (ODF paragraphs with spans) reads in order.
 */
internal class XmlNode(val name: String, private val attributes: Map<String, String>) {
    val children = ArrayList<XmlNode>(2)
    var text: String = ""
        private set

    fun attr(name: String): String? = attributes[name]

    fun child(name: String): XmlNode? = children.firstOrNull { it.name == name }

    fun children(name: String): List<XmlNode> = children.filter { it.name == name }

    /** The first element down the [path] of child names, or null. */
    fun at(vararg path: String): XmlNode? {
        var node: XmlNode = this
        for (name in path) node = node.child(name) ?: return null
        return node
    }

    /** The first descendant named [name], searching depth-first. */
    fun find(name: String): XmlNode? {
        for (c in children) {
            if (c.name == name) return c
            c.find(name)?.let { return it }
        }
        return null
    }

    /** All the text inside, in order. */
    fun allText(): String = if (name == TEXT) text else children.joinToString("") { it.allText() }

    companion object {
        const val TEXT = "#text"

        fun text(value: String) = XmlNode(TEXT, emptyMap()).apply { text = value }
    }
}

/** [input] parsed into a tree. Namespace prefixes are kept as written ("a:p", "draw:frame"). */
internal fun parseXmlTree(input: InputStream): XmlNode {
    val p = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(input, null)
    }
    val root = XmlNode("#document", emptyMap())
    val stack = ArrayList<XmlNode>().apply { add(root) }
    while (true) {
        when (p.next()) {
            XmlPullParser.START_TAG -> {
                val attrs = HashMap<String, String>(p.attributeCount * 2)
                for (i in 0 until p.attributeCount) attrs[p.getAttributeName(i)] = p.getAttributeValue(i)
                val node = XmlNode(p.name, attrs)
                stack.last().children.add(node)
                stack.add(node)
            }
            XmlPullParser.END_TAG -> stack.removeAt(stack.lastIndex)
            XmlPullParser.TEXT -> {
                val text = p.text
                if (text.isNotEmpty()) stack.last().children.add(XmlNode.text(text))
            }
            XmlPullParser.END_DOCUMENT -> break
        }
    }
    return root.children.firstOrNull { it.name != XmlNode.TEXT } ?: root
}

package com.nila.assistant

import org.json.JSONObject

/**
 * Loads the real shipped corpus for JVM tests.
 *
 * The build copies `assets/knowledge.json` into test resources, so these tests
 * exercise the file that actually ships rather than a fixture that can drift
 * away from it.
 */
object Corpus {

    val docs: List<KnowledgeDoc> by lazy {
        val json = javaClass.classLoader!!.getResourceAsStream("knowledge.json")!!
            .bufferedReader().use { it.readText() }
        val array = JSONObject(json).getJSONArray("documents")
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            fun list(name: String) = o.optJSONArray(name)?.let { a ->
                (0 until a.length()).map { a.getString(it) }
            } ?: emptyList()
            KnowledgeDoc(
                id = o.getString("id"),
                collection = o.getString("collection"),
                topic = o.getString("topic"),
                title = o.getString("title"),
                text = o.getString("text"),
                source = o.getString("source"),
                caution = o.optString("caution", ""),
                keywords = o.optString("keywords", ""),
                risk = o.optString("risk").takeIf { it.isNotEmpty() },
                generic = o.optString("generic"),
                aliases = list("aliases"),
                avoidIf = list("avoid_if"),
                interactsWith = list("interacts_with"),
                contextNote = o.optString("context_note"),
                detail = o.optString("detail"),
                audience = o.optString("audience", "baby"),
                minAgeMonths = if (o.isNull("min_age")) null else o.optInt("min_age"),
                maxAgeMonths = if (o.isNull("max_age")) null else o.optInt("max_age"),
            )
        }
    }

    val index: KnowledgeIndex by lazy { KnowledgeIndex.build(docs) }
}

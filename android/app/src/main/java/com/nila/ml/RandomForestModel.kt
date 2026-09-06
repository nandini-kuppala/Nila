package com.nila.ml

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * The RandomForest from Why-is-my-Baby-Crying, running on the phone.
 *
 * The forest is trained by that project's own pipeline -- its augmentation, its
 * SMOTE balancing, its 500 trees at depth 20 with balanced class weights -- and
 * exported as flat arrays rather than converted into something else. Walking
 * them here reproduces `predict_proba` exactly; it is not an approximation of
 * the model, it is the model.
 *
 * A forest is an odd thing to ship to a phone in 2026, and it is the right
 * thing here for two reasons. It is what was trained, so the numbers measured
 * in Python are the numbers that run. And 170,000 nodes of integer comparisons
 * is roughly a millisecond, which is cheaper than the network it replaced.
 *
 * ### Layout
 *
 * Per tree, four parallel arrays plus a leaf distribution. A node whose
 * `feature` is negative is a leaf; otherwise take the left child when the
 * feature is at or below the threshold. That is sklearn's own convention, and
 * departing from it by even the direction of the comparison silently produces
 * a model that is wrong in a way no crash reveals.
 */
class RandomForestModel private constructor(
    private val labels: Array<String>,
    private val featureCount: Int,
    private val feature: Array<IntArray>,
    private val threshold: Array<DoubleArray>,
    private val left: Array<IntArray>,
    private val right: Array<IntArray>,
    private val value: Array<Array<FloatArray>>,
) {
    companion object {
        private const val TAG = "RandomForestModel"

        fun fromAsset(context: Context, asset: String): RandomForestModel? = try {
            val started = System.currentTimeMillis()
            val json = JSONObject(
                context.assets.open(asset).bufferedReader().use { it.readText() }
            )
            val classes = json.getJSONArray("classes")
            val trees = json.getJSONArray("trees")

            val n = trees.length()
            val model = RandomForestModel(
                labels = Array(classes.length()) { classes.getString(it) },
                featureCount = json.getInt("n_features"),
                feature = Array(n) {
                    trees.getJSONObject(it).getJSONArray("feature").toIntArray()
                },
                threshold = Array(n) {
                    trees.getJSONObject(it).getJSONArray("threshold").toDoubleArray()
                },
                left = Array(n) {
                    trees.getJSONObject(it).getJSONArray("left").toIntArray()
                },
                right = Array(n) {
                    trees.getJSONObject(it).getJSONArray("right").toIntArray()
                },
                value = Array(n) { t ->
                    val v = trees.getJSONObject(t).getJSONArray("value")
                    Array(v.length()) { i -> v.getJSONArray(i).toFloatArray() }
                },
            )
            Log.i(TAG, "loaded $n trees, ${model.featureCount} features, " +
                "in ${System.currentTimeMillis() - started}ms")
            model
        } catch (t: Throwable) {
            Log.e(TAG, "could not load $asset", t)
            null
        }

        private fun JSONArray.toIntArray() = IntArray(length()) { getInt(it) }
        private fun JSONArray.toDoubleArray() = DoubleArray(length()) { getDouble(it) }
        private fun JSONArray.toFloatArray() =
            FloatArray(length()) { getDouble(it).toFloat() }
    }

    val classes: Array<String> get() = labels
    val expectedFeatures: Int get() = featureCount
    val treeCount: Int get() = feature.size

    /**
     * Mean of the per-tree class distributions, which is sklearn's
     * `predict_proba` for a forest -- not a majority vote over `predict`.
     */
    fun predict(features: FloatArray, out: FloatArray) {
        require(out.size >= labels.size)
        out.fill(0f)
        if (features.size < featureCount) return

        for (t in feature.indices) {
            val f = feature[t]
            val th = threshold[t]
            val l = left[t]
            val r = right[t]

            var node = 0
            while (f[node] >= 0) {
                node = if (features[f[node]] <= th[node]) l[node] else r[node]
            }
            val leaf = value[t][node]
            for (c in labels.indices) out[c] += leaf[c]
        }

        val scale = 1f / feature.size
        for (c in labels.indices) out[c] *= scale
    }
}

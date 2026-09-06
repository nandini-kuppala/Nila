package com.nila

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nila.audio.ReasonFeatures
import com.nila.audio.WavReader
import com.nila.ml.RandomForestModel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * The cause classifier, pinned to the Python that trained it.
 *
 * The forest is exported from the Why-is-my-Baby-Crying pipeline and walked
 * here rather than converted, so a disagreement between the two languages can
 * only come from the features. That is exactly what this checks: the same
 * window of the same recording, put through both feature extractors, has to
 * produce the same 456 numbers -- and then the same class probabilities.
 *
 * This is the second time a frontend has been written twice in this project.
 * The first cost real hours to a 0.0022 discrepancy that turned out to be the
 * fixture generator rounding its inputs, so the fixture here stores the exact
 * window offset and the features to five decimals.
 */
@RunWith(AndroidJUnit4::class)
class ReasonModelTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext =
        InstrumentationRegistry.getInstrumentation().context

    private fun golden(): JSONObject = JSONObject(
        testContext.assets.open("reason_golden.json")
            .bufferedReader().use { it.readText() }
    )

    @Test
    fun featuresMatchThePythonThatTrainedTheModel() {
        val fixture = golden()
        val expected = fixture.getJSONArray("features")
        val offset = fixture.getInt("offset_samples")
        val length = fixture.getInt("length_samples")

        val wav = WavReader.fromAsset(context, "demo_cry.wav")
        val out = FloatArray(ReasonFeatures.SIZE)
        ReasonFeatures.extract(wav.samples, offset, length, out)

        assertEquals("feature count", expected.length(), ReasonFeatures.SIZE)

        var worst = 0.0
        var worstAt = -1
        for (i in 0 until expected.length()) {
            val want = expected.getDouble(i)
            val got = out[i].toDouble()
            // Relative where the value is large, absolute where it is near zero.
            val error = abs(want - got) / maxOf(1.0, abs(want))
            if (error > worst) { worst = error; worstAt = i }
        }
        assertTrue(
            "feature $worstAt differs by $worst between Python and Kotlin",
            worst < 2e-3,
        )
    }

    @Test
    fun theForestReproducesSklearnProbabilities() {
        val fixture = golden()
        val model = RandomForestModel.fromAsset(context, "reason_forest.json")
        assertNotNull("forest did not load", model)
        model!!

        val classes = fixture.getJSONArray("classes")
        assertEquals(classes.length(), model.classes.size)
        for (i in 0 until classes.length()) {
            assertEquals("class order", classes.getString(i), model.classes[i])
        }

        val wav = WavReader.fromAsset(context, "demo_cry.wav")
        val features = FloatArray(ReasonFeatures.SIZE)
        ReasonFeatures.extract(
            wav.samples,
            fixture.getInt("offset_samples"),
            fixture.getInt("length_samples"),
            features,
        )

        val out = FloatArray(model.classes.size)
        model.predict(features, out)

        val expected = fixture.getJSONArray("proba")
        for (i in 0 until expected.length()) {
            assertEquals(
                "probability for ${model.classes[i]}",
                expected.getDouble(i), out[i].toDouble(), 0.02,
            )
        }
        assertEquals("probabilities should sum to one",
                     1.0, out.sum().toDouble(), 1e-3)
    }

    @Test
    fun theForestIsFastEnoughToRunPerWindow() {
        val model = RandomForestModel.fromAsset(context, "reason_forest.json")!!
        val features = FloatArray(ReasonFeatures.SIZE) { 0.1f * it }
        val out = FloatArray(model.classes.size)
        repeat(5) { model.predict(features, out) }

        val started = System.nanoTime()
        repeat(20) { model.predict(features, out) }
        val perCall = (System.nanoTime() - started) / 20 / 1_000_000.0
        assertTrue("forest took ${perCall}ms per call", perCall < 60.0)
    }
}

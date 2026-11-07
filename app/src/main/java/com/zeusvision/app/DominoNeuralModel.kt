package com.zeusvision.app

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream
import kotlin.math.exp
import kotlin.math.max

/**
 * ZEUS AI neural inference runtime.
 *
 * File format: big-endian binary exported by training/zeus_train.py.
 * This class has no TensorFlow/PyTorch dependency and runs fully offline.
 */
class DominoNeuralModel private constructor(
    private val inputSize: Int,
    private val hiddenSize: Int,
    private val actionSize: Int,
    private val valueSize: Int,
    private val w1: FloatArray,
    private val b1: FloatArray,
    private val w2: FloatArray,
    private val b2: FloatArray,
    private val wp: FloatArray,
    private val bp: FloatArray,
    private val wv: FloatArray,
    private val bv: FloatArray
) {
    data class Prediction(
        val policyLogits: FloatArray,
        val roundWinProbability: Float,
        val roundMargin: Float,
        val blockedProbability: Float,
        val matchWinProbability: Float
    )

    fun predict(features: FloatArray): Prediction? {
        if (features.size != inputSize || features.any { !it.isFinite() }) return null
        val h1 = FloatArray(hiddenSize)
        for (o in 0 until hiddenSize) {
            var sum = b1[o]
            val row = o * inputSize
            for (i in 0 until inputSize) sum += w1[row + i] * features[i]
            h1[o] = max(0f, sum)
        }

        val h2 = FloatArray(hiddenSize)
        for (o in 0 until hiddenSize) {
            var sum = b2[o]
            val row = o * hiddenSize
            for (i in 0 until hiddenSize) sum += w2[row + i] * h1[i]
            h2[o] = max(0f, sum)
        }

        val policy = FloatArray(actionSize)
        for (o in 0 until actionSize) {
            var sum = bp[o]
            val row = o * hiddenSize
            for (i in 0 until hiddenSize) sum += wp[row + i] * h2[i]
            policy[o] = sum
        }

        val values = FloatArray(valueSize)
        for (o in 0 until valueSize) {
            var sum = bv[o]
            val row = o * hiddenSize
            for (i in 0 until hiddenSize) sum += wv[row + i] * h2[i]
            values[o] = sum
        }
        if (policy.any { !it.isFinite() } || values.any { !it.isFinite() }) return null
        return Prediction(
            policy,
            sigmoid(values[0]),
            values[1].coerceIn(-1f, 1f),
            sigmoid(values[2]),
            sigmoid(values[3])
        )
    }

    companion object {
        const val ASSET_FILE = "zeus_domino_policy_value_v1.bin"
        private val MAGIC = byteArrayOf('Z'.code.toByte(), 'E'.code.toByte(), 'U'.code.toByte(), 'S'.code.toByte(),
            'N'.code.toByte(), 'N'.code.toByte(), '0'.code.toByte(), '1'.code.toByte())
        const val INPUT_SIZE = 89
        const val ACTION_SIZE = 84
        const val VALUE_SIZE = 4
        const val DEFAULT_HIDDEN_SIZE = 128

        fun load(input: InputStream): DominoNeuralModel {
            val data = DataInputStream(BufferedInputStream(input))
            val magic = ByteArray(MAGIC.size)
            data.readFully(magic)
            require(magic.contentEquals(MAGIC)) { "Formato de modelo ZEUS desconocido" }
            val inputSize = data.readInt()
            val hiddenSize = data.readInt()
            val actionSize = data.readInt()
            val valueSize = data.readInt()
            require(inputSize == INPUT_SIZE) { "El modelo espera $inputSize entradas; se requieren $INPUT_SIZE" }
            require(actionSize == ACTION_SIZE) { "Dimensión de política incompatible: $actionSize" }
            require(valueSize == VALUE_SIZE) { "Dimensión de valor incompatible: $valueSize" }
            require(hiddenSize in 16..1024) { "Tamaño oculto no válido: $hiddenSize" }

            fun readFloats(count: Int): FloatArray {
                require(count > 0 && count <= 2_000_000) { "Bloque de pesos fuera de rango" }
                return FloatArray(count) { data.readFloat().also { value ->
                    require(value.isFinite()) { "El modelo contiene pesos no finitos" }
                } }
            }

            val w1 = readFloats(hiddenSize * inputSize)
            val b1 = readFloats(hiddenSize)
            val w2 = readFloats(hiddenSize * hiddenSize)
            val b2 = readFloats(hiddenSize)
            val wp = readFloats(actionSize * hiddenSize)
            val bp = readFloats(actionSize)
            val wv = readFloats(valueSize * hiddenSize)
            val bv = readFloats(valueSize)
            return DominoNeuralModel(inputSize, hiddenSize, actionSize, valueSize,
                w1, b1, w2, b2, wp, bp, wv, bv)
        }

        fun maskedProbabilities(logits: FloatArray, actionKeys: List<Int>): DoubleArray {
            if (actionKeys.isEmpty()) return DoubleArray(0)
            if (actionKeys.any { it !in logits.indices || !logits[it].isFinite() }) {
                return DoubleArray(actionKeys.size) { 1.0 / actionKeys.size }
            }
            val maxLogit = actionKeys.maxOf { logits[it] }
            val weights = DoubleArray(actionKeys.size) { index ->
                exp((logits[actionKeys[index]] - maxLogit).toDouble().coerceIn(-40.0, 40.0))
            }
            val total = weights.sum().coerceAtLeast(1e-12)
            for (i in weights.indices) weights[i] /= total
            return weights
        }

        private fun sigmoid(value: Float): Float {
            val x = value.coerceIn(-20f, 20f)
            return (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()
        }
    }
}

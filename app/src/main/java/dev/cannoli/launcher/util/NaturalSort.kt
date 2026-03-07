package dev.cannoli.launcher.util

object NaturalSort : Comparator<String> {

    private val chunkPattern = Regex("(\\d+|\\D+)")

    override fun compare(a: String, b: String): Int {
        val chunksA = chunkPattern.findAll(a.lowercase()).map { it.value }.toList()
        val chunksB = chunkPattern.findAll(b.lowercase()).map { it.value }.toList()

        for (i in 0 until minOf(chunksA.size, chunksB.size)) {
            val ca = chunksA[i]
            val cb = chunksB[i]

            val result = if (ca[0].isDigit() && cb[0].isDigit()) {
                val na = ca.toBigInteger()
                val nb = cb.toBigInteger()
                na.compareTo(nb)
            } else {
                ca.compareTo(cb)
            }

            if (result != 0) return result
        }

        return chunksA.size - chunksB.size
    }
}

fun <T> List<T>.sortedNatural(selector: (T) -> String): List<T> =
    sortedWith(compareBy(NaturalSort, selector))

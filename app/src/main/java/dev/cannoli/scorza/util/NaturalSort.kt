package dev.cannoli.scorza.util

object NaturalSort : Comparator<String> {

    private val chunkPattern = Regex("(\\d+|\\D+)")

    override fun compare(a: String, b: String): Int {
        val iterA = chunkPattern.findAll(a.lowercase()).iterator()
        val iterB = chunkPattern.findAll(b.lowercase()).iterator()

        while (iterA.hasNext() && iterB.hasNext()) {
            val ca = iterA.next().value
            val cb = iterB.next().value

            val result = if (ca[0].isDigit() && cb[0].isDigit()) {
                val na = ca.toLongOrNull()
                val nb = cb.toLongOrNull()
                if (na != null && nb != null) na.compareTo(nb)
                else ca.toBigInteger().compareTo(cb.toBigInteger())
            } else {
                ca.compareTo(cb)
            }

            if (result != 0) return result
        }

        return iterA.hasNext().compareTo(iterB.hasNext())
    }
}

fun <T> List<T>.sortedNatural(selector: (T) -> String): List<T> =
    sortedWith(compareBy(NaturalSort, selector))

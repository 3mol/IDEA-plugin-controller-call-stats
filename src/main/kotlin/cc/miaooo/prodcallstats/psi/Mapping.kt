package cc.miaooo.prodcallstats.psi

data class Mapping(
    val httpMethod: String?,
    val paths: List<String>,
) {
    companion object {
        val EMPTY = Mapping(null, emptyList())
    }
}

package cc.miaooo.prodcallstats.psi

/**
 * Represents a Spring MVC handler method resolved from PSI.
 *
 * The [sign] property is the key used to query the gateway; it has the
 * format `<className>#<methodName>` per the design contract §6.5.
 */
data class HandlerMethod(
    val className: String,
    val methodName: String,
    val httpMethod: String?,
    val urlTemplate: String?,
) {
    val sign: String get() = "$className#$methodName"
}

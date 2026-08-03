package cc.miaooo.prodcallstats.psi

/**
 * Represents a Spring MVC handler method resolved from PSI.
 *
 * The [sign] property is the key used to query the gateway; it has the
 * format `<className>#<methodName>` per the design contract §6.5.
 *
 * [description] is the human-readable summary extracted from
 * `@ApiOperation(value)` (Swagger 2) or `@Operation(summary)` (OpenAPI 3);
 * null when neither annotation is present or the value is blank.
 */
data class HandlerMethod(
    val className: String,
    val methodName: String,
    val httpMethod: String?,
    val urlTemplate: String?,
    val description: String? = null,
) {
    val sign: String get() = "$className#$methodName"
}

package cc.miaooo.prodcallstats.gateway

sealed class GatewayException(message: String) : RuntimeException(message) {
    object TokenExpired : GatewayException("API token is invalid or expired")
    object NotFound : GatewayException("No stats available for this method")
    class Unreachable(cause: Throwable) : GatewayException("Gateway unreachable: ${cause.message}")
    class HttpStatus(val status: Int, message: String) : GatewayException("HTTP $status: $message")
}

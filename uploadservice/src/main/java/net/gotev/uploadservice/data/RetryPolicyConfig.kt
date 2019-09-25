package net.gotev.uploadservice.data

data class RetryPolicyConfig(
        /**
         * Sets the time to wait in seconds before the next attempt when an upload fails
         * for the first time. From the second time onwards, this value will be multiplied by
         * [multiplier] to get the time to wait before the next attempt.
         */
        val initialWaitTimeSeconds: Int = 1,

        /**
         * Sets the maximum time to wait in seconds between two upload attempts.
         * This is useful because every time an upload fails, the wait time gets multiplied by
         * [multiplier] and it's not convenient that the value grows
         * indefinitely.
         */
        val maxWaitTimeSeconds: Int = 100,

        /**
         * Sets the backoff timer multiplier. By default is set to 2, so every time that an upload
         * fails, the time to wait between retries will be multiplied by 2.
         * E.g. if the first time the wait time is 1s, the second time it will be 2s and the third
         * time it will be 4s.
         */
        val multiplier: Int = 2,

        /**
         * Sets the default number of retries for each request.
         */
        val defaultMaxRetries: Int = 3
) {
    override fun toString(): String {
        return """{"initialWaitTimeSeconds": $initialWaitTimeSeconds, "maxWaitTimeSeconds": $maxWaitTimeSeconds, "multiplier": $multiplier, "defaultMaxRetries": $defaultMaxRetries}"""
    }
}

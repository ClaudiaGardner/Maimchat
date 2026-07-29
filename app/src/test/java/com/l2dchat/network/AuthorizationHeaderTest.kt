package com.l2dchat.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthorizationHeaderTest {
    @Test
    fun prefixesPlainToken() {
        assertEquals("Bearer secret", bearerAuthorizationValue(" secret "))
    }

    @Test
    fun preservesExistingBearerValue() {
        assertEquals("Bearer secret", bearerAuthorizationValue("Bearer secret"))
    }

    @Test
    fun ignoresBlankToken() {
        assertNull(bearerAuthorizationValue("  "))
    }
}

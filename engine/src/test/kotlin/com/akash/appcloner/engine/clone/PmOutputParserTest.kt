package com.akash.appcloner.engine.clone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PmOutputParserTest {

    @Test
    fun `created user id is lifted from pm output`() {
        assertEquals(11, PmOutputParser.parseCreatedUserId("Success: created user id 11"))
    }

    @Test
    fun `an unrecognised create response yields null rather than a wrong user`() {
        assertNull(PmOutputParser.parseCreatedUserId("Error: couldn't create User."))
        assertNull(PmOutputParser.parseCreatedUserId(""))
    }

    @Test
    fun `user list is parsed with running state`() {
        val output = """
            Users:
            	UserInfo{0:Owner:c13} running
            	UserInfo{11:clone_freefiremax:410}
        """.trimIndent()
        val users = PmOutputParser.parseUserList(output)
        assertEquals(2, users.size)
        assertEquals(0, users[0].id)
        assertTrue(users[0].running)
        assertEquals("clone_freefiremax", users[1].name)
        assertTrue(!users[1].running)
    }

    @Test
    fun `max users is read from the trailing count`() {
        assertEquals(4, PmOutputParser.parseMaxUsers("Maximum supported users: 4"))
        assertNull(PmOutputParser.parseMaxUsers("unexpected"))
    }

    @Test
    fun `install session id is read from brackets`() {
        assertEquals(
            1234567,
            PmOutputParser.parseSessionId("Success: created install session [1234567]"),
        )
        assertNull(PmOutputParser.parseSessionId("Failure"))
    }

    @Test
    fun `success detection is case insensitive`() {
        assertTrue(PmOutputParser.isSuccess("Success"))
        assertTrue(!PmOutputParser.isSuccess("Failure [INSTALL_FAILED_NO_MATCHING_ABIS]"))
    }
}

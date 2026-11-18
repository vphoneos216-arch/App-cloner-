package com.akash.appcloner.engine.clone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloneProfileParsersTest {

    // Shape of `dumpsys user`: a UserInfo header with an indented Type line beneath it.
    private val dumpsys = """
        Users:
          UserInfo{0:Owner:c13} serialNo=0 isPrimary=true
            Type: android.os.usertype.full.SYSTEM
            Flags: ADMIN|FULL|PRIMARY|SYSTEM (0x00000c13)
            State: RUNNING_UNLOCKED
          UserInfo{11:AppCloneFreeFire:1010} serialNo=11
            Type: android.os.usertype.profile.CLONE
            Flags: PROFILE (0x00001010)
            State: RUNNING_UNLOCKED
          UserInfo{12:Work:1030} serialNo=12
            Type: android.os.usertype.profile.MANAGED
            State: RUNNING_LOCKED
    """.trimIndent()

    @Test
    fun `clone profile is identified by its type, not its name`() {
        val users = DumpsysUserParser.parse(dumpsys)
        assertEquals(3, users.size)
        val clone = users.single { it.isCloneProfile }
        assertEquals(11, clone.id)
        assertEquals(UserTypes.CLONE_PROFILE, clone.type)
    }

    @Test
    fun `a managed profile is never mistaken for a clone profile`() {
        val managed = DumpsysUserParser.parse(dumpsys).single { it.id == 12 }
        assertFalse(managed.isCloneProfile)
        assertEquals(UserTypes.MANAGED_PROFILE, managed.type)
    }

    @Test
    fun `a user with no type line gets a null type rather than a guess`() {
        val users = DumpsysUserParser.parse("  UserInfo{999:Parallel Apps:4000030} serialNo=999")
        assertEquals(1, users.size)
        assertNull(users[0].type)
        assertFalse(users[0].isCloneProfile)
    }

    @Test
    fun `oem dual app users are flagged as reuse candidates, not as clone profiles`() {
        val oneplus = DumpsysUserParser.parse("UserInfo{999:Parallel Apps:4000030}").single()
        val samsung = DumpsysUserParser.parse("UserInfo{95:DUAL_APP:20000030}").single()
        assertTrue(oneplus.isOemDualAppCandidate)
        assertTrue(samsung.isOemDualAppCandidate)
        assertFalse(oneplus.isCloneProfile)
    }

    @Test
    fun `user 0 is never a dual app candidate`() {
        val owner = DumpsysUserParser.parse("UserInfo{0:Clone Owner:c13}").single()
        assertFalse(owner.isOemDualAppCandidate)
    }

    @Test
    fun `merge unions both sources and takes types from dumpsys`() {
        val listed = PmOutputParser.parseUserList(
            "Users:\n\tUserInfo{0:Owner:c13} running\n\tUserInfo{11:AppCloneX:1010} running",
        )
        val merged = DumpsysUserParser.merge(listed, DumpsysUserParser.parse(dumpsys))

        // The union, not the intersection: user 12 appears only in dumpsys here, and dropping a
        // user that exists would be worse than carrying one the other source missed.
        assertEquals(3, merged.size)
        assertEquals(UserTypes.CLONE_PROFILE, merged.single { it.id == 11 }.type)
        assertTrue(merged.single { it.id == 11 }.running)
        assertEquals(UserTypes.MANAGED_PROFILE, merged.single { it.id == 12 }.type)
    }

    @Test
    fun `merge keeps a user that dumpsys could not type`() {
        val listed = PmOutputParser.parseUserList(
            "Users:\n\tUserInfo{0:Owner:c13} running\n\tUserInfo{999:Parallel Apps:4000030}",
        )
        val merged = DumpsysUserParser.merge(listed, emptyList())
        assertEquals(2, merged.size)
        assertNull(merged.single { it.id == 999 }.type)
        assertTrue(merged.single { it.id == 999 }.isOemDualAppCandidate)
    }

    @Test
    fun `hitting the per-parent limit is classified as a limit, not a generic failure`() {
        val output = "Error: android.os.ServiceSpecificException: Cannot add more profiles of " +
            "type android.os.usertype.profile.CLONE for user 0 (code 6)\n" +
            "Error: couldn't create User."
        assertEquals(CloneProfileFailure.LIMIT_REACHED, CloneProfileErrorParser.classify(output))
        assertTrue(
            CloneProfileErrorParser.remedyFor(CloneProfileFailure.LIMIT_REACHED)
                .contains("max-allowed-per-parent"),
        )
    }

    @Test
    fun `an OEM build that moved the subcommand is classified separately`() {
        assertEquals(
            CloneProfileFailure.COMMAND_UNSUPPORTED,
            CloneProfileErrorParser.classify("Unknown command: create-user"),
        )
    }

    @Test
    fun `a disabled user type is distinguished from a limit`() {
        assertEquals(
            CloneProfileFailure.TYPE_UNSUPPORTED,
            CloneProfileErrorParser.classify(
                "Error: user type android.os.usertype.profile.CLONE is not enabled",
            ),
        )
    }

    @Test
    fun `an unrecognised failure stays unknown`() {
        assertEquals(
            CloneProfileFailure.UNKNOWN,
            CloneProfileErrorParser.classify("Error: something nobody has seen before"),
        )
    }

    @Test
    fun `resolve-activity yields the component for the requested package only`() {
        val output = """
            priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false
            com.dts.freefiremax/com.unity3d.player.UnityPlayerActivity
        """.trimIndent()
        assertEquals(
            "com.dts.freefiremax/com.unity3d.player.UnityPlayerActivity",
            ResolveActivityParser.parse(output, "com.dts.freefiremax"),
        )
        assertNull(ResolveActivityParser.parse(output, "com.other.app"))
    }

    @Test
    fun `package uid is read and split into user and app id`() {
        val uid = PackageUidParser.parse(
            "package:com.dts.freefiremax uid:1110234",
            "com.dts.freefiremax",
        )
        assertEquals(1110234, uid)
        assertEquals(10234, PackageUidParser.appIdOf(uid!!))
        assertEquals(1110234, PackageUidParser.uidFor(11, 10234))
    }

    @Test
    fun `a uid line for a different package is not picked up`() {
        assertNull(PackageUidParser.parse("package:com.other uid:1110234", "com.dts.freefiremax"))
    }
}

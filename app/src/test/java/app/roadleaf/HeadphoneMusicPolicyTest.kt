package app.roadleaf

import app.roadleaf.playback.*
import org.junit.Assert.*
import org.junit.Test

class HeadphoneMusicPolicyTest {
    private val headset = OutputDevice("headphones", "Headphones", "Bluetooth media", 7)
    private val selected = OutputSelection(headset.key, headset.name)
    @Test fun connectedExplicitHeadphonesAndPermissionAllowEnabling() {
        assertNull(HeadphoneMusicPolicy.requirement(31, true, selected, listOf(headset)))
    }
    @Test fun androidEightToElevenKeepTheModeWithLegacyCallDetection() {
        for (sdk in 26..30) assertNull("$sdk", HeadphoneMusicPolicy.requirement(sdk, true, selected, listOf(headset)))
        assertNotNull(HeadphoneMusicPolicy.requirement(25, true, selected, listOf(headset)))
    }
    @Test fun deniedPhonePermissionNeverAllowsUnprotectedMixing() {
        assertNotNull(HeadphoneMusicPolicy.requirement(37, false, selected, listOf(headset)))
    }
    @Test fun systemDefaultCannotBeMistakenForSelectedHeadphones() {
        assertNotNull(HeadphoneMusicPolicy.requirement(37, true, OutputSelection(), listOf(headset)))
    }
    @Test fun missingOrAmbiguousHeadphonesBlockStarting() {
        assertFalse(HeadphoneMusicPolicy.headphones(selected, emptyList()))
        assertFalse(HeadphoneMusicPolicy.headphones(selected, listOf(headset, headset.copy(id = 8))))
    }
    @Test fun phoneSpeakerIsNotAllowedForTheSeparateOutputMode() {
        assertFalse(HeadphoneMusicPolicy.headphones(selected, listOf(headset.copy(kind = "Phone speaker"))))
    }
    @Test fun wiredUsbAndLeHeadphonesAlsoSupportAnExplicitChoice() {
        for (kind in listOf("Wired headphones", "USB audio", "Bluetooth LE headphones", "Hearing aid"))
            assertTrue(HeadphoneMusicPolicy.headphones(selected, listOf(headset.copy(kind = kind))))
    }
    @Test fun carControlsAreBlockedOnlyWhenTheOwnerEnablesThisMode() {
        val car = "com.google.android.projection.gearhead"
        assertTrue(HeadphoneMusicPolicy.blocksCarController(true, car))
        assertTrue(HeadphoneMusicPolicy.blocksCarController(true, "com.google.android.gms"))
        assertFalse(HeadphoneMusicPolicy.blocksCarController(false, "com.google.android.gms"))
        assertFalse(HeadphoneMusicPolicy.blocksCarController(false, car))
        assertFalse(HeadphoneMusicPolicy.blocksCarController(true, "app.roadleaf.debug"))
        assertFalse(HeadphoneMusicPolicy.blocksCarController(true, "android"))
        assertFalse(HeadphoneMusicPolicy.blocksCarController(true, null))
    }
    @Test fun pendingInitialCallStateBlocksPlay() { assertFalse(HeadphoneMusicPolicy.canPlay(CallProtection())) }
    @Test fun activeCallOrMonitoringFailureBlocksPlay() {
        assertFalse(HeadphoneMusicPolicy.canPlay(CallProtection(true, true)))
        assertFalse(HeadphoneMusicPolicy.canPlay(CallProtection(true, false, "unavailable")))
    }
    @Test fun knownIdleCallStateAllowsManualPlay() { assertTrue(HeadphoneMusicPolicy.canPlay(CallProtection(true))) }
    @Test fun modeStartsOffAndReportContainsOnlyItsOwnControlState() {
        val state = HeadphoneMusicState()
        assertFalse(state.enabled)
        assertTrue(state.report().contains("Headphones with car music: false"))
        assertFalse(state.report().contains(headset.key))
    }
}

package app.roadleaf

import app.roadleaf.playback.*
import org.junit.Assert.*
import org.junit.Test

class AudioOutputPolicyTest {
    private val key = AudioOutputPolicy.key(8, "private-headset-address", "My headphones")
    private val selected = OutputSelection(key, "My headphones")
    private val headphones = OutputDevice(key, "My headphones", "Bluetooth media", 20)
    private val car = OutputDevice(AudioOutputPolicy.key(8, "private-car-address", "Car"), "Car", "Bluetooth media", 21)
    @Test fun defaultAllowsNormalAndroidAutoOrPhoneRoutingWithoutADeviceChoice() {
        assertTrue(AudioOutputPolicy.canStart(OutputSelection(), emptyList()))
        assertNull(AudioOutputPolicy.resolve(OutputSelection(), listOf(headphones, car)))
    }
    @Test fun missingHeadphonesNeverResolveToTheAvailableCar() {
        assertFalse(AudioOutputPolicy.canStart(selected, listOf(car)))
        assertNull(AudioOutputPolicy.resolve(selected, listOf(car)))
    }
    @Test fun reconnectCanHaveANewAndroidIdWithoutChangingTheSavedChoice() {
        val reconnected = headphones.copy(id = 99)
        assertEquals(reconnected, AudioOutputPolicy.resolve(selected, listOf(reconnected, car)))
        assertTrue(AudioOutputPolicy.matches(selected, listOf(reconnected, car), 99))
        assertFalse(AudioOutputPolicy.matches(selected, listOf(reconnected, car), 20))
    }
    @Test fun duplicateIdentityIsAmbiguousAndDoesNotGuess() {
        assertFalse(AudioOutputPolicy.canStart(selected, listOf(headphones, headphones.copy(id = 98))))
    }
    @Test fun anotherBluetoothDeviceWithTheSameNameIsNotTheSelectedHeadphones() {
        val otherKey = AudioOutputPolicy.key(8, "different-address", "My headphones")
        assertNotEquals(key, otherKey)
        assertFalse(AudioOutputPolicy.canStart(selected, listOf(headphones.copy(key = otherKey))))
    }
    @Test fun aPreferenceAloneIsNotProofOfActualRouting() {
        val devices = listOf(headphones, car)
        assertFalse(AudioOutputPolicy.matches(selected, devices, null))
        assertFalse(AudioOutputPolicy.matches(selected, devices, car.id))
        assertTrue(AudioOutputPolicy.matches(selected, devices, headphones.id))
    }
    @Test fun fingerprintContainsNoRawAddressAndSurvivesStableEnumeration() {
        assertEquals(64, key.length)
        assertFalse(key.contains("private-headset-address"))
        assertEquals(key, AudioOutputPolicy.key(8, "private-headset-address", "My headphones"))
        assertNotEquals(key, AudioOutputPolicy.key(3, "private-headset-address", "My headphones"))
    }
    @Test fun shareableReportContainsObservedResultWithoutDeviceIdentityTokensOrBookText() {
        val state = OutputState(selected, listOf(headphones, car), reportedDevice = "My headphones", confirmed = true,
            lastResult = "Android reported My headphones; requested My headphones; matched=true.")
        val report = state.report("test-version")
        assertTrue(report.contains("matched=true")); assertTrue(report.contains("requires a parked listening check"))
        assertFalse(report.contains(key)); assertFalse(report.contains("private-headset-address")); assertFalse(report.contains("private-car-address"))
    }
}

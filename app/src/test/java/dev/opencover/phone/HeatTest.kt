package dev.opencover.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class HeatTest {
    @Test fun coolPhoneIsOk() = assertEquals(Heat.OK, heatLevel(0, 350, Heat.OK))

    @Test fun warmTemperatureWarns() = assertEquals(Heat.WARN, heatLevel(0, WARN_TENTHS, Heat.OK))

    @Test fun moderateThermalStatusWarnsEvenWhenCool() = assertEquals(Heat.WARN, heatLevel(2, 300, Heat.OK))

    @Test fun hotTemperatureCuts() = assertEquals(Heat.CUT, heatLevel(0, CUT_TENTHS, Heat.OK))

    @Test fun severeThermalStatusCuts() = assertEquals(Heat.CUT, heatLevel(3, 300, Heat.OK))

    @Test fun cutDoesNotDropUntilClearlyCooler() {
        assertEquals(Heat.CUT, heatLevel(0, CUT_TENTHS - 10, Heat.CUT))
        assertEquals(Heat.WARN, heatLevel(0, CUT_TENTHS - 30, Heat.CUT))
    }

    @Test fun warnDoesNotDropUntilClearlyCooler() {
        assertEquals(Heat.WARN, heatLevel(0, WARN_TENTHS - 10, Heat.WARN))
        assertEquals(Heat.OK, heatLevel(0, WARN_TENTHS - 30, Heat.WARN))
    }

    @Test fun hotspotStopCallsTheTetheringServiceForWifi() =
        assertEquals(listOf("service", "call", "tethering", "5", "i32", "0"), STOP_HOTSPOT.take(6))

    @Test fun okStaysOkJustBelowTheWarnLine() = assertEquals(Heat.OK, heatLevel(0, WARN_TENTHS - 10, Heat.OK))

    @Test fun inactiveShowsNothing() = assertEquals(Heat.OK, shown(Heat.CUT, active = false))

    // Turning the hotspot on while already hot must still notify: the shown level rises from OK.
    @Test fun activationWhileHotNotifies() = assertEquals(Heat.CUT, shown(Heat.CUT, active = true))

    // A failed hotspot stop or a new mirror session while already hot must still be cut, not skipped.
    @Test fun cutRepeatsWhileStillHot() = assertEquals(Step(cut = true, notify = false), heatStep(Heat.CUT, told = Heat.CUT))

    @Test fun notifiesOnlyWhenWorse() {
        assertEquals(Step(cut = false, notify = true), heatStep(Heat.WARN, told = Heat.OK))
        assertEquals(Step(cut = false, notify = false), heatStep(Heat.WARN, told = Heat.CUT))
    }
}

package com.example.mconkmap.engine

import org.junit.Assert.*
import org.junit.Test

class DeadReckoningEngineTest {
    @Test fun validGpsIsAcceptedAsOrigin() {
        val engine = DeadReckoningEngine()
        val now = 10_000_000_000L
        val state = engine.update(EngineInput(now, GpsFix(37.5, 127.0, 36.0, 90.0, 5f, now)))
        assertEquals(TrackingMode.GPS, state.mode)
        assertEquals(37.5, state.latitude, 1e-6)
        assertEquals(127.0, state.longitude, 1e-6)
    }

    @Test fun obdSpeedMovesPositionWhenGpsDisappears() {
        val engine = DeadReckoningEngine()
        val t0 = 10_000_000_000L
        engine.update(EngineInput(t0, GpsFix(37.5, 127.0, 36.0, 90.0, 5f, t0),
            obdSpeedKmh = 36.0, obdLastUpdateNanos = t0, obdConnected = true))
        val state = engine.update(EngineInput(t0 + 1_000_000_000L,
            obdSpeedKmh = 36.0, obdLastUpdateNanos = t0 + 1_000_000_000L,
            obdConnected = true))
        assertEquals(TrackingMode.DEAD_RECKONING, state.mode)
        assertTrue(state.deadReckoningDistanceMeters > 0.0)
        assertTrue(state.longitude > 127.0)
    }

    @Test fun inaccurateGpsIsIgnored() {
        val engine = DeadReckoningEngine()
        val t = 10_000_000_000L
        engine.update(EngineInput(t, GpsFix(37.5, 127.0, 20.0, 90.0, 5f, t)))
        val state = engine.update(EngineInput(t + 1_000_000_000L,
            GpsFix(38.0, 128.0, 20.0, 90.0, 80f, t + 1_000_000_000L),
            obdSpeedKmh = 20.0, obdLastUpdateNanos = t + 1_000_000_000L, obdConnected = true))
        assertEquals(TrackingMode.DEAD_RECKONING, state.mode)
        assertNotEquals(38.0, state.latitude, 0.001)
    }

    @Test fun gpsRecoveryInterpolatesTowardGps() {
        val engine = DeadReckoningEngine()
        val t = 10_000_000_000L
        engine.update(EngineInput(t, GpsFix(37.5, 127.0, 36.0, 90.0, 5f, t),
            obdSpeedKmh = 36.0, obdLastUpdateNanos = t, obdConnected = true))
        engine.update(EngineInput(t + 1_000_000_000L,
            obdSpeedKmh = 36.0, obdLastUpdateNanos = t + 1_000_000_000L, obdConnected = true))
        val recovered = engine.update(EngineInput(t + 2_000_000_000L,
            GpsFix(37.5001, 127.0002, 30.0, 90.0, 5f, t + 2_000_000_000L),
            obdSpeedKmh = 30.0, obdLastUpdateNanos = t + 2_000_000_000L, obdConnected = true))
        assertEquals(TrackingMode.RECOVERING, recovered.mode)
        assertTrue(recovered.latitude < 37.5001)
    }
}

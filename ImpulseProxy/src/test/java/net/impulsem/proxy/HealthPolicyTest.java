package net.impulsem.proxy;

import static org.junit.Assert.assertEquals;

import org.junit.Test;


public class HealthPolicyTest {

    @Test
    public void healthyKeepsCheckingAndResetsTheRetry() {
        assertEquals(HealthPolicy.Action.RECHECK, HealthPolicy.decide(true, false, true));
        assertEquals(HealthPolicy.Action.RECHECK, HealthPolicy.decide(true, true, true));
    }


    @Test
    public void firstFailureRestartsOnce() {
        assertEquals(HealthPolicy.Action.RESTART, HealthPolicy.decide(true, false, false));
    }


    @Test
    public void secondFailureFails() {
        assertEquals(HealthPolicy.Action.FAIL, HealthPolicy.decide(true, true, false));
    }


    @Test
    public void staleGenerationStops() {
        assertEquals(HealthPolicy.Action.STOP, HealthPolicy.decide(false, false, true));
        assertEquals(HealthPolicy.Action.STOP, HealthPolicy.decide(false, false, false));
    }
}

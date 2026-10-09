package net.impulsem.transport.logging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;


public class LogRedactorTest {

    @Test
    public void booleansAndFiniteNumbersAreAdmittedUnderAnyName() {
        assertEquals(Boolean.TRUE, LogRedactor.admit(Boolean.TRUE));
        assertEquals(5000000001L, LogRedactor.admit(5000000001L));
        assertEquals(-3, LogRedactor.admit(-3));
        assertEquals(2.5d, LogRedactor.admit(2.5d));
    }


    @Test
    public void theRealtimeCauseTokensAreKnown() {
        assertEquals("silent_socket", LogRedactor.admit("silent_socket"));
        assertEquals("backoff_skipped", LogRedactor.admit("backoff_skipped"));
        assertEquals("connect_failed", LogRedactor.admit("connect_failed"));
    }


    @Test
    public void nonFiniteNumbersAreDropped() {
        assertNull(LogRedactor.admit(Double.NaN));
        assertNull(LogRedactor.admit(Double.POSITIVE_INFINITY));
    }


    @Test
    public void aStringIsAdmittedOnlyWhenItIsAKnownTokenOrAnErrorCode() {
        assertEquals("token_fetch", LogRedactor.admit("token_fetch"));
        assertEquals("PHONE_CALL_REQUEST", LogRedactor.admit("PHONE_CALL_REQUEST"));
        assertEquals("FLOOD_WAIT_5", LogRedactor.admit("FLOOD_WAIT_5"));
        assertNull(LogRedactor.admit("Alice Smith"));
        assertNull(LogRedactor.admit("alice_99"));
        assertNull(LogRedactor.admit("token_fetch "));
        assertNull(LogRedactor.admit(""));
    }


    @Test
    public void anEnumConstantShipsItsName() {
        assertEquals("CALL_REQUEST", LogRedactor.admit(Sample.CALL_REQUEST));
    }


    @Test
    public void aClassShipsItsSimpleNameBecauseATypeCarriesNoData() {
        assertEquals("Sample", LogRedactor.admit(Sample.class));
    }


    @Test
    public void anObjectWithNoAllowedShapeIsDropped() {
        assertNull(LogRedactor.admit(new StringBuilder("Alice")));
        assertNull(LogRedactor.admit(new Object()));
    }


    @Test
    public void tokensAreMatchedExactlyNotByShape() {
        assertTrue(LogRedactor.isEnumToken("getDifference"));
        assertFalse(LogRedactor.isEnumToken("getDifference2"));
        assertFalse(LogRedactor.isEnumToken("Getdifference"));
        assertFalse(LogRedactor.isEnumToken(upperCaseRun(65)));
        assertTrue(LogRedactor.isEnumToken(upperCaseRun(64)));
    }


    private static String upperCaseRun(int length) {
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < length; i++) {
            run.append('A');
        }
        return run.toString();
    }


    private enum Sample {
        CALL_REQUEST
    }
}

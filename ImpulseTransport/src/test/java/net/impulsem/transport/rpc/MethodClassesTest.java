package net.impulsem.transport.rpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;


public class MethodClassesTest {

    @Test
    public void eachSetHoldsItsMembers() {
        assertEquals(15, MethodClasses.LoginOnlyMethods.size());
        assertEquals(3, MethodClasses.DualUseMethods.size());
        assertEquals(5, MethodClasses.PendingMethods.size());
        assertEquals(11, MethodClasses.PublicMethods.size());
        assertEquals(38, MethodClasses.IdentitySocketMethods.size());
        assertTrue(MethodClasses.LoginOnlyMethods.contains("auth.sendCode"));
        assertTrue(MethodClasses.DualUseMethods.contains("account.verifyEmail"));
        assertTrue(MethodClasses.PendingMethods.contains("account.getPassword"));
        assertTrue(MethodClasses.PublicMethods.contains("help.getConfig"));
        assertTrue(MethodClasses.IdentitySocketMethods.contains("initConnection"));
        assertTrue(MethodClasses.IdentitySocketMethods.contains("connectionIdentity"));
        assertTrue(MethodClasses.IdentitySocketMethods.contains("refreshSession"));
        assertTrue(MethodClasses.IdentitySocketMethods.contains("account.deleteAccount"));
    }


    @Test
    public void setsAreUnmodifiable() {
        try {
            MethodClasses.LoginOnlyMethods.add("messages.sendMessage");
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            assertEquals(15, MethodClasses.LoginOnlyMethods.size());
        }
    }


    @Test
    public void identitySocketSendsOnlyItsOwnMethods() {
        assertFalse(MethodClasses.allowed(false, "messages.sendMessage"));
        assertTrue(MethodClasses.allowed(false, "auth.sendCode"));
        assertTrue(MethodClasses.allowed(false, "account.deleteAccount"));
    }


    @Test
    public void sessionSocketSendsEverythingButLoginOnly() {
        assertFalse(MethodClasses.allowed(true, "auth.sendCode"));
        assertFalse(MethodClasses.allowed(true, "auth.signIn"));
        assertTrue(MethodClasses.allowed(true, "messages.sendMessage"));
        assertTrue(MethodClasses.allowed(true, "account.deleteAccount"));
    }
}

package net.impulsem.transport.rpc;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;


/** Which methods each kind of socket may send; Web K's methodClasses.ts. */
public final class MethodClasses {

    public static final Set<String> LoginOnlyMethods = setOf(
        "auth.sendCode",
        "auth.cancelCode",
        "auth.signIn",
        "auth.signUp",
        "auth.requestFirebaseSms",
        "auth.resetLoginEmail",
        "auth.reportMissingCode",
        "auth.checkPaidAuth",
        "auth.importAuthorization",
        "auth.importBotAuthorization",
        "auth.importWebTokenAuthorization",
        "auth.exportLoginToken",
        "auth.importLoginToken",
        "auth.initPasskeyLogin",
        "auth.finishPasskeyLogin"
    );

    public static final Set<String> DualUseMethods = setOf(
        "auth.resendCode",
        "account.sendVerifyEmailCode",
        "account.verifyEmail"
    );

    public static final Set<String> PendingMethods = setOf(
        "auth.checkPassword",
        "auth.requestPasswordRecovery",
        "auth.recoverPassword",
        "auth.checkRecoveryPassword",
        "account.getPassword"
    );

    public static final Set<String> PublicMethods = setOf(
        "help.getConfig",
        "help.getAppConfig",
        "help.getNearestDc",
        "help.getCountriesList",
        "help.getDeepLinkInfo",
        "help.saveAppLog",
        "langpack.getLangPack",
        "langpack.getStrings",
        "langpack.getDifference",
        "langpack.getLanguage",
        "langpack.getLanguages"
    );

    public static final Set<String> IdentitySocketMethods = identitySocketSet();


    private MethodClasses() {
    }


    /** An identity socket sends only IdentitySocketMethods; a session socket sends anything but LoginOnlyMethods. */
    public static boolean allowed(
        boolean signedIn,
        String method
    ) {
        if (signedIn) {
            return !LoginOnlyMethods.contains(method);
        }
        return IdentitySocketMethods.contains(method);
    }


    private static Set<String> identitySocketSet() {
        Set<String> all = new HashSet<String>();
        all.addAll(LoginOnlyMethods);
        all.addAll(DualUseMethods);
        all.addAll(PendingMethods);
        all.addAll(PublicMethods);
        all.addAll(Arrays.asList(
            "initConnection",
            "connectionIdentity",
            "refreshSession",
            "account.deleteAccount"
        ));
        return Collections.unmodifiableSet(all);
    }


    private static Set<String> setOf(String... methods) {
        return Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(methods)));
    }
}

package net.impulsem.transport.auth;


/** What upstream Telegram puts in initConnection: the app, the device and the languages. Immutable. */
public final class ConnectionDeclaration {

    public final int apiId;
    public final String deviceModel;
    public final String systemVersion;
    public final String appVersion;
    public final String systemLangCode;
    public final String langPack;
    public final String langCode;


    public ConnectionDeclaration(
        int apiId,
        String deviceModel,
        String systemVersion,
        String appVersion,
        String systemLangCode,
        String langPack,
        String langCode
    ) {
        this.apiId = apiId;
        this.deviceModel = deviceModel == null ? "" : deviceModel;
        this.systemVersion = systemVersion == null ? "" : systemVersion;
        this.appVersion = appVersion == null ? "" : appVersion;
        this.systemLangCode = systemLangCode == null ? "" : systemLangCode;
        this.langPack = langPack == null ? "" : langPack;
        this.langCode = langCode == null ? "" : langCode;
    }


    public ConnectionDeclaration withLangCode(String value) {
        return new ConnectionDeclaration(apiId, deviceModel, systemVersion, appVersion, systemLangCode, langPack, value);
    }


    public ConnectionDeclaration withSystemLangCode(String value) {
        return new ConnectionDeclaration(apiId, deviceModel, systemVersion, appVersion, value, langPack, langCode);
    }


    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ConnectionDeclaration)) {
            return false;
        }
        ConnectionDeclaration value = (ConnectionDeclaration) other;
        return apiId == value.apiId
            && deviceModel.equals(value.deviceModel)
            && systemVersion.equals(value.systemVersion)
            && appVersion.equals(value.appVersion)
            && systemLangCode.equals(value.systemLangCode)
            && langPack.equals(value.langPack)
            && langCode.equals(value.langCode);
    }


    @Override
    public int hashCode() {
        int result = apiId;
        result = 31 * result + deviceModel.hashCode();
        result = 31 * result + systemVersion.hashCode();
        result = 31 * result + appVersion.hashCode();
        result = 31 * result + systemLangCode.hashCode();
        result = 31 * result + langPack.hashCode();
        result = 31 * result + langCode.hashCode();
        return result;
    }
}

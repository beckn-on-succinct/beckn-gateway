package in.succinct.beckn.gateway.util;

import com.venky.swf.routing.Config;

public class GWConfig {
    public static String getSubscriberId() {
        return Config.instance().getProperty("in.succinct.beckn.gateway.subscriber_id");
    }
    public static String getPublicKeyId() {
        return Config.instance().getProperty("in.succinct.beckn.gateway.public_key_id");
    }
    public static String getRegistryUrl() {
        return Config.instance().getProperty("in.succinct.beckn.registry.url");
    }
    public static String getRegistrySigningPublicKey() {
        return Config.instance().getProperty("in.succinct.beckn.registry.signing_public_key");
    }
    public static String getRegistryEncryptionPublicKey() {
        return Config.instance().getProperty("in.succinct.beckn.registry.encryption_public_key");
    }
    public static boolean isAuthorizationHeaderEnabled(){
        return Config.instance().getBooleanProperty("beckn.auth.enabled", false);
    }
}

package in.succinct.beckn.gateway.util;

import com.venky.swf.routing.Config;

/**
 * These are utility functions used to access properties defined across all swf.properties files.... These will be defined in the overrideProperties/config/swf.properties
 * in the application folder normally.
 */
public class GWConfig {
    /**
     * Subscriber Id of the gateway
     */
    public static String getSubscriberId() {
        return Config.instance().getProperty("in.succinct.beckn.gateway.subscriber_id");
    }
    /**
     * Public key id of the gateway. ITis usually generated as subscriber_id.k1. in AppInstaller by a registrar my give a different id.
     * In which case you will need to update the key ID in CRYPTO_KEYS table also via /crypto_keys on the gw admin ui.
     *
     */
    public static String getPublicKeyId() {
        return Config.instance().getProperty("in.succinct.beckn.gateway.public_key_id");
    }

    /**
     * get registry url from properties file
     * @return registry url
     */
    public static String getRegistryUrl() {
        return Config.instance().getProperty("in.succinct.beckn.registry.url");
    }

    /**
     * get beckn registry's public key for signing
     */
    public static String getRegistrySigningPublicKey() {
        return Config.instance().getProperty("in.succinct.beckn.registry.signing_public_key");
    }


    /**
     * get beckn registry's public encryption key
     */
    public static String getRegistryEncryptionPublicKey() {
        return Config.instance().getProperty("in.succinct.beckn.registry.encryption_public_key");
    }

    /**
     *
     * Used to enable/disable Auth Header validations in this gateway. Note it doesnot affect how other participants validate these headers.
     */
    public static boolean isAuthorizationHeaderEnabled(){
        return Config.instance().getBooleanProperty("beckn.auth.enabled", false);
    }

    public static boolean isAuthorizationHeaderMandatory(){
        return Config.instance().getBooleanProperty("beckn.auth.header.mandatory", false);
    }

    public static boolean disableSlowBpp(){
        return Config.instance().getBooleanProperty("beckn.disable.slow.bpp", true);
    }

    public static long getTimeOut(){
        return Config.instance().getLongProperty("beckn.network.timeout", 5 * 1000L);
    }

}

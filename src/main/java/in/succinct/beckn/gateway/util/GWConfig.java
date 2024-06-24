package in.succinct.beckn.gateway.util;

import com.venky.swf.routing.Config;
import in.succinct.beckn.Subscriber;
import in.succinct.onet.core.adaptor.NetworkAdaptorFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

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

    public static String getCountry(){
        return Config.instance().getProperty("in.succinct.onet.country.iso.3","IND");
    }
    public static String getNetworkId(){
        return Config.instance().getProperty("in.succinct.onet.name","beckn_open");
    }

    public static Subscriber getSubscriber(){
        return getSubscriber(Subscriber.SUBSCRIBER_TYPE_BG);
    }
    public static Subscriber getSubscriber(String type){
        return new Subscriber(){{
            setSubscriberId(GWConfig.getSubscriberId());
            setNonce(Base64.getEncoder().encodeToString(String.valueOf(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8)));
            setUniqueKeyId(GWConfig.getPublicKeyId());
            setCountry(GWConfig.getCountry());
            setSubscriberUrl(Config.instance().getServerBaseUrl()+"/network");
            setType(type);
            NetworkAdaptorFactory.getInstance().getAdaptor(getNetworkId()).getSubscriptionJson(this);
        }};
    }
}

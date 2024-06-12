package in.succinct.beckn.gateway.extensions;

import com.venky.core.util.ObjectHolder;
import com.venky.extension.Extension;
import com.venky.extension.Registry;
import in.succinct.beckn.Subscriber;
import in.succinct.beckn.gateway.util.GWConfig;
import in.succinct.onet.core.adaptor.NetworkAdaptorFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Succinct provides an Extension registry that application programmers can register to . These are hooks called at specific places to
 * implement some externalized logic.
 * This is a hook used by {@link in.succinct.beckn.Request#getPublicKey(String subscriber_id, String public_key_id)} (String, String)}
 * to while verifying signature of a subscriber identified from auth header.
 * The reason it is a hook is because, the {@link in.succinct.beckn.Request} class doesnot know of your registry or caches to get the public key infromation.
 *
 */
public class BecknPublicKeyFinder implements Extension {
    static {
        Registry.instance().registerExtension("beckn.public.key.get",new BecknPublicKeyFinder());
    }

    @Override
    @SuppressWarnings("unchecked")
    public void invoke(Object... context) {
        String subscriber_id = (String)context[0];
        String uniqueKeyId = (String)context[1];
        ObjectHolder<String> publicKeyHolder = (ObjectHolder<String>) context[2];
        if (publicKeyHolder.get() != null){
            return;
        }

        Subscriber subscriber = new Subscriber();
        subscriber.setSubscriberId(subscriber_id);
        subscriber.setUniqueKeyId(uniqueKeyId);

        List<Subscriber> subscribers = lookup(subscriber);
        if (!subscribers.isEmpty()){
            publicKeyHolder.set(subscribers.get(0).getSigningPublicKey());
        }
    }

    private static Map<String,CacheEntry> cache = new HashMap<>();
    private static class CacheEntry {
        List<Subscriber> subscribers;
        long expiry;
    }
    private static final long TTL = 2L * 60L * 1000L ;// 2 minutes in millis


    public static List<Subscriber> lookup(Subscriber subscriber){
        String key = subscriber.toString();
        CacheEntry entry = cache.get(key);
        if (entry != null){
            if (System.currentTimeMillis() > entry.expiry){
                entry = null;
                cache.remove(key);
            }
        }
        if (entry != null){
            return entry.subscribers;
        }


        entry = new CacheEntry();
        entry.expiry = System.currentTimeMillis() + TTL; // This TTL is to force refresh to get updated cache!.
        entry.subscribers = NetworkAdaptorFactory.getInstance().getAdaptor(GWConfig.getNetworkId()).lookup(subscriber,true);

        cache.put(key,entry);
        return entry.subscribers;
    }
}

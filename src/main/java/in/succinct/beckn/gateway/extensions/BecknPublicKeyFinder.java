package in.succinct.beckn.gateway.extensions;

import com.venky.cache.Cache;
import com.venky.core.util.ObjectHolder;
import com.venky.core.util.ObjectUtil;
import com.venky.extension.Extension;
import com.venky.extension.Registry;
import com.venky.swf.db.annotations.column.ui.mimes.MimeType;
import com.venky.swf.integration.api.Call;
import com.venky.swf.integration.api.HttpMethod;
import com.venky.swf.integration.api.InputFormat;
import com.venky.swf.routing.Config;
import in.succinct.beckn.Subscriber;
import in.succinct.beckn.gateway.util.GWConfig;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
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


        JSONArray responses = new Call<JSONObject>().method(HttpMethod.POST).url(GWConfig.getRegistryUrl() +"/lookup").
                input(subscriber.getInner()).inputFormat(InputFormat.JSON)
                .header("content-type", MimeType.APPLICATION_JSON.toString())
                .header("accept",MimeType.APPLICATION_JSON.toString()).getResponseAsJson();

        if (responses == null) {
            responses = new JSONArray();
        }
        for (Iterator<?> i = responses.iterator(); i.hasNext(); ) {
            JSONObject object1 = (JSONObject) i.next();
            if (!ObjectUtil.equals(object1.get("status"),"SUBSCRIBED")){
                i.remove();
            }
        }


        entry = new CacheEntry();
        entry.expiry = System.currentTimeMillis() + TTL; // This TTL is to force refresh to get updated cache!.
        entry.subscribers = new ArrayList<>();
        for (Object o : responses){
            entry.subscribers.add(new Subscriber((JSONObject) o));
        }
        cache.put(key,entry);
        return entry.subscribers;
    }
}

package in.succinct.beckn.gateway.extensions;

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
import java.util.Iterator;
import java.util.List;

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
    public static List<Subscriber> lookup(Subscriber subscriber){

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
        List<Subscriber> subscribers = new ArrayList<>();
        for (Object o : responses){
            subscribers.add(new Subscriber((JSONObject) o));
        }
        return subscribers;
    }
}

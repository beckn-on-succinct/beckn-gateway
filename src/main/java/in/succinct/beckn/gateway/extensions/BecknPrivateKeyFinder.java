package in.succinct.beckn.gateway.extensions;

import com.venky.core.util.ObjectHolder;
import com.venky.extension.Extension;
import com.venky.extension.Registry;
import com.venky.swf.db.model.CryptoKey;

/**
 * Succinct provides an Extension registry that application programmers can register to . These are hooks called at specific places to
 * implement some externalized logic.
 * This is a hook used by {@link in.succinct.beckn.Request#getPrivateKey(String subscriber_id, String public_key_id)} (String, String)}
 * to sign your request.
 */
public class BecknPrivateKeyFinder implements Extension {
    static {
        Registry.instance().registerExtension("beckn.private.key.get",new BecknPrivateKeyFinder());
    }


    @Override
    public void invoke(Object... context) {
        String subscriber_id = (String)context[0];
        String uniqueKeyId = (String)context[1];
        ObjectHolder<String> privateKeyHolder = (ObjectHolder<String>) context[2];
        CryptoKey key = CryptoKey.find(uniqueKeyId,CryptoKey.PURPOSE_SIGNING);
        if (!key.getRawRecord().isNewRecord()){
            privateKeyHolder.set(key.getPrivateKey());
        }
    }
}

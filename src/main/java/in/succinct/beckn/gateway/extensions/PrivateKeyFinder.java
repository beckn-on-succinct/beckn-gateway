package in.succinct.beckn.gateway.extensions;

import com.venky.core.util.ObjectHolder;
import com.venky.extension.Extension;
import com.venky.extension.Registry;
import com.venky.swf.db.model.CryptoKey;
import in.succinct.beckn.Request;
import in.succinct.beckn.Subscriber;
import in.succinct.beckn.gateway.util.GWConfig;

public class PrivateKeyFinder implements Extension {
    static {
        Registry.instance().registerExtension("private.key.get.Ed25519",new PrivateKeyFinder());
    }

    @SuppressWarnings("unchecked")
    @Override
    public void invoke(Object... context) {
        
        
        ObjectHolder<String> holder = (ObjectHolder<String>) context[0];
        if (holder.get() != null){
            return;
        }
        
        CryptoKey key = CryptoKey.find(GWConfig.getPublicKeyId(),CryptoKey.PURPOSE_SIGNING);
        String privateKey = key.getPrivateKey();
        holder.set(String.format("%s|%s:%s",GWConfig.getSubscriberId() , GWConfig.getPublicKeyId(),privateKey));
        
    }
}

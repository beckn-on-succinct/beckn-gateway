package in.succinct.beckn.gateway.extensions;

import com.venky.core.util.ObjectHolder;
import com.venky.extension.Extension;
import com.venky.extension.Registry;
import com.venky.swf.db.model.reflection.ModelReflector;
import com.venky.swf.plugins.collab.db.model.CryptoKey;
import com.venky.swf.sql.Conjunction;
import com.venky.swf.sql.Expression;
import com.venky.swf.sql.Operator;
import com.venky.swf.sql.Select;

import java.util.List;

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

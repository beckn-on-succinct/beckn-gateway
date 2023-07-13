package in.succinct.beckn.gateway.util;

import com.venky.swf.plugins.background.messaging.EventEmitter;
import in.succinct.beckn.Context;
import in.succinct.beckn.Request;
import in.succinct.beckn.Response;

public class BGEventEmitter {
    private static volatile BGEventEmitter sSoleInstance;

    //private constructor.
    private BGEventEmitter() {
        //Prevent form the reflection api.
        if (sSoleInstance != null) {
            throw new RuntimeException("Use getInstance() method to get the single instance of this class.");
        }
    }

    public static BGEventEmitter getInstance() {
        if (sSoleInstance == null) { //if there is no instance available... create new one
            synchronized (BGEventEmitter.class) {
                if (sSoleInstance == null) sSoleInstance = new BGEventEmitter();
            }
        }

        return sSoleInstance;
    }

    //Make singleton from serialize and deserialize operation.
    protected BGEventEmitter readResolve() {
        return getInstance();
    }

    public void log_request_processed(Context context, int transmitted_counterparty_count) {
        Request request = new Request();
        request.setContext(context);
        if (transmitted_counterparty_count > 0) {
            request.set("transmitted_counterparty_count", transmitted_counterparty_count);
        }
        EventEmitter.getInstance().emit("bg_request", request.getInner());
    }
}


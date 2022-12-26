package in.succinct.beckn.gateway.util;

import com.venky.swf.db.model.application.Event;
import in.succinct.beckn.Request;
import in.succinct.beckn.Subscriber;
import org.json.simple.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ECEventEmitter {
    private static Map<String, JSONObject> eventMeta = new HashMap<String,JSONObject>(){{
        put(ACTION_SEARCH, new JSONObject(){{
            put("eventCode",ACTION_SEARCH);
            put("eventTitle","Broadcasting search");
            put("eventMessage","Waiting for catalog");
            put("eventSource", new JSONObject(){{
                put("eventSourceId",GWConfig.getSubscriberId());
                put("eventSourceType", subscriberTypeMap.get(Subscriber.SUBSCRIBER_TYPE_BG));
            }});
        }});
        put(ACTION_ON_SEARCH, new JSONObject(){{
            put("eventCode",ACTION_ON_SEARCH);
            put("eventTitle","Sending catalog");
            put("eventMessage","Received a catalog");
            put("eventSource", new JSONObject(){{
                put("eventSourceId",GWConfig.getSubscriberId());
                put("eventSourceType", subscriberTypeMap.get(Subscriber.SUBSCRIBER_TYPE_BG));
            }});
        }});

    }};
    public static Map<String,String> subscriberTypeMap = new HashMap<String,String>(){{
        put(Subscriber.SUBSCRIBER_TYPE_BPP,"bpp");
        put(Subscriber.SUBSCRIBER_TYPE_BAP,"bap");
        put(Subscriber.SUBSCRIBER_TYPE_BG, "gateway");
    }};

    public static final String ACTION_SEARCH = "search";
    public static final String ACTION_ON_SEARCH = "on_search";


    public void emit(Subscriber target, Request request) {
        String txn = request.getContext().getTransactionId();
        if (!txn.contains(".")){
            return;
        }
        String eId = txn.substring(txn.lastIndexOf('.')+1);

        String action = request.getContext().getAction();


        JSONObject eventJSON = new JSONObject();
        eventJSON.put("experienceId",eId);
        eventJSON.put("eventId",UUID.randomUUID().toString());
        eventJSON.putAll(eventMeta.get(action));
        eventJSON.put("context",new JSONObject(){{
            put("message_id",request.getContext().getMessageId());
            put("transaction_id",request.getContext().getTransactionId());
        }});

        eventJSON.put("eventDestination",new JSONObject(){{
            put("eventDestinationId",target.getSubscriberId());
            put("eventDestinationType", subscriberTypeMap.get(target.getType()));
        }});
        eventJSON.put("eventStart_ts",request.getContext().getTimestamp());
        eventJSON.put("created_ts",request.getContext().getTimestamp());

        Event.find("ec_publish").raise(eventJSON);
    }
}

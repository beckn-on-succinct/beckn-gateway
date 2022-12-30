package in.succinct.beckn.gateway.util;

import com.venky.swf.db.model.application.Event;
import com.venky.swf.plugins.background.core.DbTask;
import com.venky.swf.plugins.background.core.TaskManager;
import in.succinct.beckn.Request;
import in.succinct.beckn.Subscriber;
import org.json.simple.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ECEventEmitter {
    public static Map<String,String> subscriberTypeMap = new HashMap<String,String>(){{
        put(Subscriber.SUBSCRIBER_TYPE_BPP,"bpp");
        put(Subscriber.SUBSCRIBER_TYPE_BAP,"bap");
        put(Subscriber.SUBSCRIBER_TYPE_BG, "gateway");
    }};
    private static Map<String, JSONObject> eventMeta = new HashMap<String,JSONObject>(){{
        put(ACTION_SEARCH, new JSONObject(){{
            put("eventCode","mbgw_srch_brdcst");
            put("eventSourceId", GWConfig.getSubscriberId());
            put("eventAction",ACTION_SEARCH);
        }});
        put(ACTION_ON_SEARCH, new JSONObject(){{
            put("eventCode","mbgw_sent_ctlg_bap");
            put("eventSourceId",GWConfig.getSubscriberId());
            put("eventAction",ACTION_ON_SEARCH);
        }});

    }};

    public static final String ACTION_SEARCH = "search";
    public static final String ACTION_ON_SEARCH = "on_search";


    public boolean isEventPublishingRequired(Request request){
        if (request.getContext().getTransactionId().contains(".")){
            return true;
        }
        return false;
    }
    public void emit(Subscriber target, Request request) {
        boolean publishingRequired = isEventPublishingRequired(request);
        if (!publishingRequired){
            return;
        }
        String txn = request.getContext().getTransactionId(); //uuid.exid
        String eId = txn.substring(txn.lastIndexOf('.')+1);  //exid

        String action = request.getContext().getAction();


        JSONObject eventJSON = new JSONObject();
        eventJSON.put("experienceId",eId);
        eventJSON.putAll(eventMeta.get(action));
        eventJSON.put("eventDestinationId",target.getSubscriberId());
        eventJSON.put("eventStart_ts",Request.TIMESTAMP_FORMAT.format(request.getContext().getTimestamp()));
        //eventJSON.put("payload",request.getInner());
        TaskManager.instance().executeAsync((DbTask)()-> Event.find("ec_publish").raise(eventJSON),false);
    }
}

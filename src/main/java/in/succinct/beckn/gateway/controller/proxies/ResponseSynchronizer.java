package in.succinct.beckn.gateway.controller.proxies;

import com.venky.core.string.StringUtil;
import com.venky.core.util.Bucket;
import com.venky.core.util.ObjectUtil;
import com.venky.swf.db.Database;
import com.venky.swf.plugins.background.core.AsyncTaskManagerFactory;
import com.venky.swf.plugins.background.eventloop.CoreEvent;
import com.venky.swf.routing.Config;
import in.succinct.beckn.Request;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("unused")
public class ResponseSynchronizer {
    private static volatile ResponseSynchronizer sSoleInstance;

    //private constructor.
    private ResponseSynchronizer() {
        //Prevent form the reflection api.
        if (sSoleInstance != null) {
            throw new RuntimeException("Use getInstance() method to get the single instance of this class.");
        }
    }

    public static ResponseSynchronizer getInstance() {
        if (sSoleInstance == null) { //if there is no instance available... create new one
            synchronized (ResponseSynchronizer.class) {
                if (sSoleInstance == null) sSoleInstance = new ResponseSynchronizer();
            }
        }

        return sSoleInstance;
    }

    //Make singleton from serialize and deserialize operation.
    protected ResponseSynchronizer readResolve() {
        return getInstance();
    }

    final HashMap<String,Tracker> responseMessages = new HashMap<>(){
        @Override
        public Tracker get(Object key) {
            Tracker tracker = super.get(key);
            if (tracker == null){
                synchronized (this){
                    tracker = super.get(key);
                    if (tracker == null){
                        tracker = new Tracker();
                        put((String)key,tracker);
                    }
                }
            }
            return tracker;
        }

    };
    public Tracker createTracker(Request request){
        return responseMessages.get( request.getContext().getMessageId());
    }

    public Tracker getTracker(String messageId, boolean returnNewIfNone){
        synchronized (responseMessages) {
            if (responseMessages.containsKey(messageId)) {
                return responseMessages.get(messageId);
            }else if (returnNewIfNone){
                return new Tracker();
            }
        }
        return null;
    }
    private static final ScheduledExecutorService service = Executors.newScheduledThreadPool(1);

    public void addResponse(Request response){
        String messageId = response.getContext().getMessageId();
        Tracker tracker = getTracker(messageId,true);
        tracker.addResponse(response);
    }
    public void closeTracker(String messageId){
        synchronized (responseMessages) {
            Tracker tracker = responseMessages.remove(messageId);
        }
    }

    public static class Tracker {
        long start;
        long end ;
        Bucket pendingResponses;
        boolean shutdown = false;
        Request request = null;
        CoreEvent listener = null;
        //ScheduledFuture<?> keepAliveTrigger = null;
        // this is an over kill, we notify on message receipt and on shutdown thats enough.
        ScheduledFuture<?> shutDownTrigger = null;
        String searchTransactionId = null;

        public Tracker(){

        }
        public void start(Request request,int maxResponses,String searchTransactionId){
            synchronized (this) {
                if (this.start <= 0) {
                    this.start = request.getContext().getTimestamp().getTime();
                    this.end = this.start + request.getContext().getTtl() * 1000L;
                    this.pendingResponses = new Bucket(maxResponses);
                    this.request = request;
                    /*this.keepAliveTrigger = service.scheduleWithFixedDelay(()->{
                        notifyListener();
                    },5000L,10000L ,TimeUnit.MILLISECONDS);*/
                    this.shutDownTrigger = service.schedule(()->{
                        shutdown();
                    },request.getContext().getTtl() *1000L,TimeUnit.MILLISECONDS);
                    this.searchTransactionId = searchTransactionId;
                }
            }
        }

        private final LinkedList<Request> responses = new LinkedList<>();

        @SuppressWarnings("unchecked")
        public void addResponse(Request response){
            Config.instance().getLogger(getClass().getName()).info(String.format("Received Response|%s|\n" , StringUtil.valueOf(response)));
            synchronized (this) {
                Config.instance().getLogger(getClass().getName()).info(String.format("Acquired Lock|%s|\n" , StringUtil.valueOf(response)));
                boolean unsolicited = !isStarted();
                if (this.pendingResponses != null){
                    this.pendingResponses.decrement();
                }
                if (!unsolicited) {
                    if (response != null) {
                        responses.add(response);
                    }
                    notifyListener();
                }
            }
        }

        @SuppressWarnings("BooleanMethodIsAlwaysInverted")
        private boolean isStarted(){
            synchronized (this) {
                return start > 0;
            }
        }

        private boolean isResponsesCollected(){
            synchronized (this) {
                return shutdown || (start > 0 && (end < System.currentTimeMillis())) || (pendingResponses != null && pendingResponses.intValue() <= 0);
            }
        }

        public boolean isComplete(){
            synchronized (this) {
                return isResponsesCollected() && responses.isEmpty();
            }
        }

        public Request nextResponse(){
            Config.instance().getLogger(getClass().getName()).info("Checking next Response");
            synchronized (this) {
                Config.instance().getLogger(getClass().getName()).info("Acquired Lock before checking response");
                if (!responses.isEmpty()) {
                    Config.instance().getLogger(getClass().getName()).info("Returning locked Response");
                    return responses.removeFirst();
                }
            }

            return null;
        }

        public void shutdown(){
            synchronized (this) {
                this.shutdown = true;
                /*
                if (this.keepAliveTrigger != null && !this.keepAliveTrigger.isCancelled()) {
                    this.keepAliveTrigger.cancel(false);
                }*/
                if (this.shutDownTrigger != null && !this.shutDownTrigger.isCancelled()) {
                    this.shutDownTrigger.cancel(false);
                }
                if (this.listener == null){
                    if (request != null) {
                        ResponseSynchronizer.getInstance().closeTracker(request.getContext().getMessageId());
                    }
                }else {
                    notifyListener();
                }
            }
        }

        public void notifyListener() {
            synchronized (this) {
                if (listener != null) {
                    Config.instance().getLogger(getClass().getName()).info("Notifying Listener");
                    AsyncTaskManagerFactory.getInstance().addAll(Collections.singleton(listener));
                    listener = null;
                }else {
                    Config.instance().getLogger(getClass().getName()).info("No Listener");
                }
            }
        }

        public void registerListener(CoreEvent listener) {
            synchronized (this) {
                if (this.listener == null) {
                   this.listener = listener;
                }

                if (this.listener != listener) {
                    throw new RuntimeException("Some other watcher is watching!");
                }
            }
        }

        public boolean isBeingObserved(){
            synchronized (this) {
                return listener != null;
            }
        }



    }

}

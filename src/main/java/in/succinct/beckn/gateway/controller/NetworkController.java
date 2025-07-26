package in.succinct.beckn.gateway.controller;

import com.venky.core.collections.IgnoreCaseMap;
import com.venky.core.security.Crypt;
import com.venky.core.string.StringUtil;
import com.venky.core.util.Bucket;
import com.venky.core.util.ObjectUtil;
import com.venky.swf.controller.Controller;
import com.venky.swf.controller.annotations.RequireLogin;
import com.venky.swf.db.Database;
import com.venky.swf.db.annotations.column.ui.mimes.MimeType;
import com.venky.swf.db.model.CryptoKey;
import com.venky.swf.db.model.application.Event;
import com.venky.swf.db.model.reflection.ModelReflector;
import com.venky.swf.integration.api.Call;
import com.venky.swf.integration.api.HttpMethod;
import com.venky.swf.integration.api.InputFormat;
import com.venky.swf.path.Path;
import com.venky.swf.plugins.background.core.AsyncTaskManagerFactory;
import com.venky.swf.plugins.background.core.IOTask;
import com.venky.swf.plugins.background.core.Task;
import com.venky.swf.plugins.background.core.TaskManager;
import com.venky.swf.plugins.background.eventloop.CoreEvent;
import com.venky.swf.plugins.beckn.tasks.BecknApiCall;
import com.venky.swf.routing.Config;
import com.venky.swf.sql.Expression;
import com.venky.swf.sql.Operator;
import com.venky.swf.sql.Select;
import com.venky.swf.views.BytesView;
import com.venky.swf.views.EventView;
import com.venky.swf.views.NoContentView;
import com.venky.swf.views.View;
import in.succinct.beckn.Acknowledgement;
import in.succinct.beckn.Acknowledgement.Status;
import in.succinct.beckn.BecknException;
import in.succinct.beckn.BecknObjects;
import in.succinct.beckn.City;
import in.succinct.beckn.Context;
import in.succinct.beckn.Country;
import in.succinct.beckn.Error;
import in.succinct.beckn.Error.Type;
import in.succinct.beckn.Location;
import in.succinct.beckn.Request;
import in.succinct.beckn.Response;
import in.succinct.beckn.SellerException.GenericBusinessError;
import in.succinct.beckn.SellerException.InvalidRequestError;
import in.succinct.beckn.SellerException.InvalidSignature;
import in.succinct.beckn.Subscriber;
import in.succinct.beckn.gateway.controller.proxies.BapController;
import in.succinct.beckn.gateway.controller.proxies.BppController;
import in.succinct.beckn.gateway.controller.proxies.ResponseSynchronizer;
import in.succinct.beckn.gateway.controller.proxies.ResponseSynchronizer.Tracker;
import in.succinct.beckn.gateway.util.GWConfig;
import in.succinct.catalog.indexer.db.model.Provider;
import in.succinct.catalog.indexer.ingest.CatalogDigester;
import in.succinct.catalog.indexer.ingest.CatalogSearchEngine;
import in.succinct.onet.core.adaptor.NetworkAdaptor;
import in.succinct.onet.core.adaptor.NetworkAdaptor.Domain;
import in.succinct.onet.core.adaptor.NetworkAdaptorFactory;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

@SuppressWarnings("unused")
public class NetworkController extends Controller implements BapController, BppController {
    public NetworkController(Path path) {
        super(path);
    }
    protected View ack(Request request){
        Response response = new Response(new Acknowledgement(Status.ACK));
        return new BytesView(getPath(),response.toString().getBytes(StandardCharsets.UTF_8), MimeType.APPLICATION_JSON);
    }

    protected Response nack(Throwable th){
        Response response = new Response(new Acknowledgement(Status.NACK));
        if (th != null){
            Error error = new Error();
            response.setError(error);
            if (th.getClass().getName().startsWith("org.openapi4j")){
                InvalidRequestError sellerException = new InvalidRequestError();
                error.setType(Type.JSON_SCHEMA_ERROR);
                error.setCode(sellerException.getErrorCode());
                error.setMessage(sellerException.getMessage());
            }else if (th instanceof BecknException){
                BecknException bex = (BecknException) th;
                error.setType(Type.DOMAIN_ERROR);
                error.setCode(bex.getErrorCode());
                error.setMessage(bex.getMessage());
            }else {
                error.setMessage(th.toString());
                error.setCode(new GenericBusinessError().getErrorCode());
                error.setType(Type.DOMAIN_ERROR);
            }
        }
        return response;
    }
    protected View nack(Throwable th, String realm){

        Response response = nack(th);

        return new BytesView(getPath(),
                response.getInner().toString().getBytes(StandardCharsets.UTF_8),
                MimeType.APPLICATION_JSON,"WWW-Authenticate","Signature realm=\""+realm+"\"",
                "headers=\"(created) (expires) digest\""){
            @Override
            public void write() throws IOException {
                if (th instanceof InvalidSignature){
                    super.write(HttpServletResponse.SC_UNAUTHORIZED);
                }else {
                    super.write(HttpServletResponse.SC_BAD_REQUEST);
                }
            }
        };
    }



    public NetworkAdaptor getNetworkAdaptor() {
        return NetworkAdaptorFactory.getInstance().getAdaptor();
    }
    
    public View clear(){
        getNetworkAdaptor().clearLookupCache();
        return no_content();
    }



    @RequireLogin(false)
    public View read_events(String messageId){
        final EventView eventView = new EventView(getPath());
        Tracker tracker = ResponseSynchronizer.getInstance().getTracker(messageId,false);

        if (tracker != null) {
            CoreEvent.spawnOff(false, new CoreEvent() {
                {
                    synchronized (tracker) {
                        tracker.registerListener(this);
                    }
                }

                @Override
                public void execute() {
                    super.execute();
                    Request response;
                    synchronized (tracker) {
                        Bucket numResponsesReceived = new Bucket();
                        while ((response = tracker.nextResponse()) != null) {
                            try {
                                numResponsesReceived.increment();
                                eventView.write(response.toString());
                            } catch (IOException ex) {
                                ResponseSynchronizer.getInstance().closeTracker(messageId);
                            }
                        }
                        try {
                            if (tracker.isComplete()) {
                                ResponseSynchronizer.getInstance().closeTracker(messageId);
                                eventView.write(String.format("{\"done\" : true , \"message_id\" : \"%s\"}\n\n",messageId));
                            } else if (numResponsesReceived.intValue() == 0){
                                tracker.registerListener(this);
                            }
                        }catch (Exception ex){
                            ResponseSynchronizer.getInstance().closeTracker(messageId);
                        }
                    }
                }

                @Override
                public boolean isReady() {
                    return super.isReady() && ( !tracker.isBeingObserved() || tracker.isComplete()); //Clients will auto reconnect.
                }
            });
        }else {
            try {
                eventView.write("{\"done\" : true}");
            }catch (Exception ex){
                Config.instance().getLogger(getClass().getName()).log(Level.WARNING,"Inactive message could not be sent" ,ex);
            }
        }
        return eventView;
    }


    public boolean isBapEndPoint(){
        return Subscriber.BAP_ACTION_SET.contains(getPath().action());
    }
    public boolean isBppEndPoint(){
        return Subscriber.BPP_ACTION_SET.contains(getPath().action());
    }
    public Subscriber getSubscriber(Context context, String type){
        List<Subscriber> subscribers = getSubscribers(context,type);
        if (subscribers.isEmpty()){
            return null;
        }else {
            return subscribers.get(0);
        }
    }
    public List<Subscriber> getSubscribers(Context context, String type){
        String subscriberId =  ObjectUtil.equals(type,Subscriber.SUBSCRIBER_TYPE_BAP) ? context.getBapId() :
                ObjectUtil.equals(type,Subscriber.SUBSCRIBER_TYPE_BPP)? context.getBppId() :
                ObjectUtil.equals(type,Subscriber.SUBSCRIBER_TYPE_BG) ? GWConfig.getSubscriberId() :
                ObjectUtil.equals(type,Subscriber.SUBSCRIBER_TYPE_LOCAL_REGISTRY)? getNetworkAdaptor().getRegistryId() : null;

        return getNetworkAdaptor().lookup(new Subscriber(){{
            setType(type);
            if (!ObjectUtil.isVoid(subscriberId)) {
                setSubscriberId(subscriberId);
            }
            setDomain(context.getDomain());
            if (!ObjectUtil.isVoid(context.getVersion())) {
                //1.x
                setLocation(new Location());
            }
            if (!ObjectUtil.isVoid(context.getCountry())) {
                Location location = getLocation();
                if (location == null){
                    setCountry(context.getCountry());
                }else {
                    location.setCountry(new Country(){{
                        setCode(context.getCountry());
                    }});
                }
            }
            if (!ObjectUtil.isVoid(context.getCity())) {
                Location location = getLocation();
                if (location != null) {
                    location.setCity(new City(){{
                        setCode(context.getCity());
                    }});
                } else {
                    setCity(context.getCity());
                }
            }
        }},true);
    }
    public Subscriber getSource(Context context){
        if (isBppEndPoint()){
            if (!ObjectUtil.isVoid(context.getBapId())) {
                return getSubscriber(context,Subscriber.SUBSCRIBER_TYPE_BAP);
            }else {
                //return getSubscriber(context,Subscriber.SUBSCRIBER_TYPE_BG);
                throw new RuntimeException("BAP not specified!");
            }
        }else if (isBapEndPoint()){
            if (!ObjectUtil.isVoid(context.getBppId())) {
                return getSubscriber(context, Subscriber.SUBSCRIBER_TYPE_BPP);
            }else {
                throw new RuntimeException("BPP not specified!");
            }
        }
        return null;
    }
    public List<Subscriber> getTargets(Context context){
        if (isBppEndPoint()){ // /search,select,...
            return getSubscribers(context,Subscriber.SUBSCRIBER_TYPE_BPP);
        }else if (isBapEndPoint()){
            if (!ObjectUtil.isVoid(context.getBapId())){
                return getSubscribers(context,Subscriber.SUBSCRIBER_TYPE_BAP);
            }else {
                return new ArrayList<>() {{
                    add(GWConfig.getSubscriber());
                }};
                /*
                return getNetworkAdaptor().lookup(new Subscriber(){{
                    setType(Subscriber.SUBSCRIBER_TYPE_BG);
                    setSubscriberId(GWConfig.getSubscriberId());
                }},true);

                 */
            }
        }
        return null;
    }

    public Map<String,Subscriber> removeSubscribersWithInternalCatalog(Map<String,Subscriber> subscriberMap){
        Map<String,Subscriber>  subscribersWithInternalCatalog = new HashMap<>();


        ModelReflector<Provider> ref = ModelReflector.instance(Provider.class);
        List<Provider> tmpProviders = new Select("MAX(ID) AS ID","SUBSCRIBER_ID").from(Provider.class).
                where(new Expression(ref.getPool(),"SUBSCRIBER_ID", Operator.IN,subscriberMap.keySet().toArray(new String[]{}))).
                groupBy("SUBSCRIBER_ID").execute();

        for (Provider tmpProvider : tmpProviders) {
            subscribersWithInternalCatalog.put(tmpProvider.getSubscriberId(),subscriberMap.remove(tmpProvider.getSubscriberId()));
        }
        Set<String> remainingSubscriberIds = new HashSet<>(subscriberMap.keySet());
        for (String subscriberId : remainingSubscriberIds) {
            Subscriber subscriber = subscriberMap.get(subscriberId);
            if (ObjectUtil.isVoid(subscriber.getSubscriberUrl())){
                subscribersWithInternalCatalog.put(subscriberId,subscriberMap.remove(subscriberId));
            }
        }

        return subscribersWithInternalCatalog;
    }
    public View act(){
        try {
            String action = getPath().action();
            boolean isSearch = ObjectUtil.equals(action,"search");
            Request request = new Request(StringUtil.read(getPath().getInputStream()));

            NetworkAdaptor networkAdaptor = getNetworkAdaptor();
            request.setObjectCreator(networkAdaptor.getObjectCreator(request.getContext().getDomain()));
            Map<String,String> headers = getPath().getHeaders();

            Subscriber self = GWConfig.getSubscriber();
            if (!headers.containsKey("Authorization") && ObjectUtil.isVoid(request.getContext().getBapId())){
                initializeRequest(self,request); //BG Pretends to be BAP!!
            }else if (GWConfig.isAuthorizationHeaderEnabled() &&
                      !request.verifySignature("Authorization",headers, GWConfig.isAuthorizationHeaderMandatory())){
                throw new InvalidSignature();
            }

            Subscriber source = getSource(request.getContext());


            Map<String,Subscriber> subscriberMap = new HashMap<>(){{
                for (Subscriber s : getTargets(request.getContext())){
                    put(s.getSubscriberId(),s);
                }
            }};
            Map<String,Subscriber> subscribersWithInternalCatalog = isSearch ? removeSubscribersWithInternalCatalog(subscriberMap) : new HashMap<>();

            Tracker tracker = ResponseSynchronizer.getInstance().createTracker(request);

            tracker.start(request, isSearch? (ObjectUtil.isVoid(request.getContext().getBppId())?
                    subscriberMap.size() + subscribersWithInternalCatalog.size():1) : 1,
                    getPath().getHeader("SearchTransactionId"));


            //AsyncTaskManagerFactory.getInstance().addAll(
            TaskManager.instance().executeAsync(new ArrayList<>(){{
                if (!subscriberMap.isEmpty()) {
                    add(new BppRequestTask(tracker, source, subscriberMap, networkAdaptor, request, headers, false));
                }
                if (!subscribersWithInternalCatalog.isEmpty()) {
                    add(new BppRequestTask(tracker, source, subscribersWithInternalCatalog, networkAdaptor, request, headers, true));
                }
            }},false);


            boolean callBackToBeSynchronized = Database.getJdbcTypeHelper("").getTypeRef(boolean.class).getTypeConverter().valueOf(getPath().getHeader("X-CallBackToBeSynchronized"));
            if (!callBackToBeSynchronized) {
                return ack(request);
            }else {

                CoreEvent.spawnOff(new CoreEvent(){
                    {
                        tracker.registerListener(this);
                    }
                    final Requests requests = new Requests();
                    @Override
                    public void execute() {
                        super.execute();
                        Request response ;
                        synchronized (tracker) {
                            while ((response = tracker.nextResponse()) != null) {
                                requests.add(response);
                            }
                            if (tracker.isComplete()) {
                                ResponseSynchronizer.getInstance().closeTracker(request.getContext().getMessageId());
                                try {
                                    //Request connection is committed after this response is committed in HttpCoreEvent
                                    new BytesView(getPath(), requests.getInner().toString().getBytes(StandardCharsets.UTF_8), MimeType.APPLICATION_JSON).write();
                                }catch (IOException ex){
                                    throw new RuntimeException(ex);
                                }
                            }else {
                                tracker.registerListener(this);
                            }
                        }
                    }

                    @Override
                    public boolean isReady() {
                        return super.isReady() && ( !tracker.isBeingObserved() || tracker.isComplete()); //Clients will auto reconnect.
                    }
                });
                return new NoContentView(getPath()); //Request is kept open
            }



        }catch (Exception ex){
            throw new RuntimeException(ex);
        }
    }

    public static class Requests extends BecknObjects<Request> {
        public Requests() {
        }

        public Requests(JSONArray value) {
            super(value);
        }

        public Requests(String payload) {
            super(payload);
        }
    }
    public static class BppRequestTask implements Task {
        Map<String,Subscriber> targetSubscriberMap;
        Subscriber from;
        NetworkAdaptor networkAdaptor;
        Request request;
        Map<String,String> headers ;
        boolean internalCatalog ;
        Tracker tracker;
        public BppRequestTask(Tracker tracker , Subscriber from, Map<String,Subscriber> targetSubscriberMap, NetworkAdaptor networkAdaptor , Request request , Map<String,String> headers ,boolean internalCatalog){
            this.tracker =tracker;
            this.from = from;
            this.targetSubscriberMap = targetSubscriberMap;
            this.networkAdaptor = networkAdaptor;
            this.request = networkAdaptor.getObjectCreator(request.getContext().getDomain()).create(Request.class);
            this.request.update(request);
            this.headers = headers == null ? new IgnoreCaseMap<>() : headers;
            this.internalCatalog = internalCatalog;
        }
        @Override
        public void execute() {
            if (internalCatalog){
                executeInternal();
            }else {
                executeExternal();
            }
        }
        private void executeInternal(){
            if (from == null){
                return;
            }
            if (!request.getContext().getAction().equals("search")){
                return; // Shouldnot happen
            }
            CatalogSearchEngine searchEngine = new CatalogSearchEngine(targetSubscriberMap);
            Context context = request.getContext();


            Request internalFormatRequest = new Request();
            internalFormatRequest.update(request);

            List<Request> internalFormatResponses = new ArrayList<>();
            searchEngine.search(internalFormatRequest,internalFormatResponses);
            Collections.shuffle(internalFormatResponses); // Fairness!! ha ha


            for (Request internalFormatResponse : internalFormatResponses) {
                if (internalFormatResponse.isSuppressed()){
                    continue;
                }
                Request on_search = networkAdaptor.getObjectCreator(request.getContext().getDomain()).create(Request.class);
                on_search.update(internalFormatResponse);
                if (!ObjectUtil.equals(from.getSubscriberId(),GWConfig.getSubscriberId())){
                    String auth = on_search.generateAuthorizationHeader(GWConfig.getSubscriberId(), GWConfig.getPublicKeyId());

                    AsyncTaskManagerFactory.getInstance().addAll(new ArrayList<>() {{
                        add((IOTask) () -> {
                            BecknApiCall call = BecknApiCall.build().url(from.getSubscriberUrl(),
                                            on_search.getContext().getAction()).schema(networkAdaptor.getDomains().get(request.getContext().getDomain()).getSchemaURL()).
                                    headers(new HashMap<>() {{
                                        put("Content-Type", "application/json");
                                        put("Accept", "application/json");
                                        put("X-Gateway-Authorization", auth);
                                        put("Authorization", auth);
                                    }}).path("/" + on_search.getContext().getAction()).request(on_search).call();
                        });

                    }});
                }else {
                    tracker.addResponse(on_search); //Shorting sending on_search to self.
                }
            }
        }
        private void executeExternal() {
            String auth = request.generateAuthorizationHeader(GWConfig.getSubscriberId(), GWConfig.getPublicKeyId());

            List<String> subscriberIds  = new ArrayList<>(targetSubscriberMap.keySet());
            Collections.shuffle(subscriberIds);
            List<IOTask> tasks = new ArrayList<>();
            for (String subscriberId : subscriberIds){
                Subscriber to = targetSubscriberMap.get(subscriberId);
                tasks.add((IOTask)()->{
                    BecknApiCall call = BecknApiCall.build().url(to.getSubscriberUrl(),
                                    request.getContext().getAction());
                    Domain domain = null;
                    if (!ObjectUtil.isVoid(request.getContext().getDomain())){
                        domain = networkAdaptor.getDomains().get(request.getContext().getDomain());
                    }else if (!networkAdaptor.getDomains().isEmpty()){
                        domain = networkAdaptor.getDomains().get(0);
                    }
                    if (domain != null) {
                        call.schema(domain.getSchemaURL());
                    }
                    call.headers(new HashMap<>() {{
                                put("Content-Type", "application/json");
                                put("Accept", "application/json");
                                put("X-Gateway-Authorization", auth);
                                put("Authorization",headers.getOrDefault("Authorization",auth));
                                if (headers.containsKey("ApiKey") || headers.containsKey("X-ApiKey")){
                                    put("ApiKey",headers.getOrDefault("ApiKey",headers.get("X-ApiKey")));
                                }
                            }}).path("/" + request.getContext().getAction()).request(request).call();

                    if (call.getStatus() >= 500 && GWConfig.disableSlowBpp()){
                        tracker.addResponse(null);
                        disableBpp(to);
                    }else if (call.hasErrors()) {
                        tracker.addResponse(null);
                    }else if (call.getResponse().getAcknowledgement().getStatus() == Status.NACK){
                        tracker.addResponse(null);
                    }else {
                        if (ObjectUtil.equals(request.getContext().getAction(),"confirm")) {
                            Event.find("/" + request.getContext().getAction()).raise(request);//Request sent to bpp successfully.
                        }
                    }
                    
                });
            }
            AsyncTaskManagerFactory.getInstance().addAll(tasks);

        }

        public void disableBpp(Subscriber bpp){
            Subscriber registry = NetworkAdaptorFactory.getInstance().getAdaptor().getRegistry();

            Request payload = new Request(bpp.toString());
            String auth = GWConfig.isAuthorizationHeaderEnabled() ?payload.generateAuthorizationHeader(GWConfig.getSubscriberId(),GWConfig.getPublicKeyId()) : null;

            new Call<InputStream>().method(HttpMethod.POST).url(registry.getSubscriberUrl() ,"/disable").
                    headers(new IgnoreCaseMap<>(){{
                        put("Content-Type",MimeType.APPLICATION_JSON.toString());
                        if (auth != null) {
                            put("Authorization", auth);
                            put("Proxy-Authorization", auth);
                            put("X-Gateway-Authorization", auth);
                        }
                    }}).input(new ByteArrayInputStream(payload.toString().getBytes(StandardCharsets.UTF_8))).inputFormat(InputFormat.INPUT_STREAM).hasErrors();
        }
    }

    private void initializeRequest(Subscriber self,Request request) {
        NetworkAdaptor networkAdaptor = getNetworkAdaptor();
        Context context = request.getContext();
        if (context == null) {
            context = new Context();
            request.setContext(context);
        }
        context.setBapId(self.getSubscriberId());
        context.setBapUri(self.getSubscriberUrl());
        context.setNetworkId(networkAdaptor.getId());
        context.setCoreVersion(networkAdaptor.getCoreVersion());
        if (context.getCity() == null) {
            context.setCity(self.getCity());
        }
        if (context.getCountry() == null) {
            context.setCountry(self.getCountry());
        }
        context.setAction(getPath().action());
        if (context.get("ttl") == null){
            context.setTtl(10L);
        }
        context.setTimestamp(new Date());
        if (ObjectUtil.isVoid(context.getDomain())) {
            context.setDomain(self.getDomain());
        }
        if (ObjectUtil.isVoid(context.getTransactionId())) {
            context.setTransactionId(UUID.randomUUID().toString());
        }
        if (ObjectUtil.isVoid(context.getMessageId())) {
            context.setMessageId(UUID.randomUUID().toString());
        }

    }

    public View on_act(){

        try {
            Request request = new Request((JSONObject) Request.parse(StringUtil.read(getPath().getInputStream())));
            request.setObjectCreator(getNetworkAdaptor().getObjectCreator(request.getContext().getDomain()));
            boolean isOnSearch = request.getContext().getAction().equals("on_search");

            if ( !GWConfig.isAuthorizationHeaderEnabled()  ||
                    request.verifySignature("Authorization",getPath().getHeaders(),GWConfig.isAuthorizationHeaderMandatory())){

                IgnoreCaseMap<String> headers = new IgnoreCaseMap<>();
                headers.putAll(getPath().getHeaders());


                List<Subscriber> targetSubscribers = getTargets(request.getContext());

                List<Task> tasks= new ArrayList<>();
                String auth = request.generateAuthorizationHeader(GWConfig.getSubscriberId(),GWConfig.getPublicKeyId());

                for (Subscriber subscriber: targetSubscribers) {
                    if (ObjectUtil.equals(subscriber.getSubscriberId(), GWConfig.getSubscriberId())) {
                        if (ObjectUtil.isVoid(request.getContext().getBapId())) {
                            //unsolicited
                            Request internal = new Request();
                            internal.update(request);
                            if (isOnSearch) {
                                tasks.add(new CatalogDigester(internal.getContext(), internal.getMessage().getCatalog()));
                            } else {
                                throw new RuntimeException("Bap id is missing!");
                            }
                        } else {
                            tasks.add((Task) () -> ResponseSynchronizer.getInstance().addResponse(request));
                        }
                    } else {
                        tasks.add((Task) () -> {
                            BecknApiCall call = BecknApiCall.build().url(subscriber.getSubscriberUrl(),
                                            request.getContext().getAction()).schema(getNetworkAdaptor().getDomains().get(request.getContext().getDomain()).getSchemaURL()).
                                    headers(new HashMap<>() {{
                                        putAll(headers);
                                        put("Content-Type", "application/json");
                                        put("Accept", "application/json");
                                        put("X-Gateway-Authorization", auth);
                                        put("Authorization", auth);
                                    }}).path("/" + request.getContext().getAction()).request(request).call();
                        });
                    }
                }
                AsyncTaskManagerFactory.getInstance().addAll(tasks);
            }
            return ack(request);


        }catch (Exception ex){
            return nack(ex,GWConfig.getSubscriber().getSubscriberId());
        }
    }


    public View subscribe() {
        getNetworkAdaptor().subscribe(GWConfig.getSubscriber());
        return new BytesView(getPath(),"Subscription initiated!".getBytes(StandardCharsets.UTF_8),MimeType.APPLICATION_JSON);
    }


    @RequireLogin(false)
    public View subscriber_json(){
        return new BytesView(getPath(),GWConfig.getSubscriber().toString().getBytes(StandardCharsets.UTF_8),MimeType.APPLICATION_JSON);
    }

    @RequireLogin(false)
    @SuppressWarnings("unchecked")
    public View on_subscribe() throws Exception{
        String payload = StringUtil.read(getPath().getInputStream());
        JSONObject object = (JSONObject) JSONValue.parse(payload);
        Subscriber registry = getNetworkAdaptor().getRegistry();
        if (registry == null){
            throw new RuntimeException("Cannot verify Signature, Could not find registry keys for " + getNetworkAdaptor().getId());
        }

        if (!Request.verifySignature(getPath().getHeader("Signature"), payload, registry.getSigningPublicKey())){
            throw new RuntimeException("Cannot verify Signature");
        }

        JSONObject output = new JSONObject();
        output.put("answer", solveChallenge(getNetworkAdaptor(),GWConfig.getSubscriber(),(String)object.get("challenge")));
        return new BytesView(getPath(),output.toString().getBytes(),MimeType.APPLICATION_JSON);
    }
    public String solveChallenge(NetworkAdaptor networkAdaptor, Subscriber participant, String challenge) throws NoSuchAlgorithmException, InvalidKeyException {
        return solveChallenge(networkAdaptor.getRegistry(),participant,challenge);
    }
    public String solveChallenge(Subscriber registry, Subscriber participant, String challenge) throws NoSuchAlgorithmException, InvalidKeyException {
        if (registry == null || registry.getEncrPublicKey() == null){
            throw new RuntimeException("Could not find registry keys for " + registry);
        }



        PrivateKey privateKey = Crypt.getInstance().getPrivateKey(Request.ENCRYPTION_ALGO,
                CryptoKey.find(participant.getUniqueKeyId(),CryptoKey.PURPOSE_ENCRYPTION).getPrivateKey());

        PublicKey registryPublicKey = Request.getEncryptionPublicKey(registry.getEncrPublicKey());

        KeyAgreement agreement = KeyAgreement.getInstance(Request.ENCRYPTION_ALGO);
        agreement.init(privateKey);
        agreement.doPhase(registryPublicKey,true);

        SecretKey key = agreement.generateSecret("TlsPremasterSecret");
        return Crypt.getInstance().decrypt(challenge,"AES",key);
    }
    
    @Override
    public View status() {
        return BppController.super.status();
    }
}

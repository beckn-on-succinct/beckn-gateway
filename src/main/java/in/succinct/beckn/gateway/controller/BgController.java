package in.succinct.beckn.gateway.controller;

import com.venky.core.collections.IgnoreCaseMap;
import com.venky.core.security.Crypt;
import com.venky.core.string.StringUtil;
import com.venky.core.util.ExceptionUtil;
import com.venky.core.util.ObjectUtil;
import com.venky.swf.controller.Controller;
import com.venky.swf.controller.annotations.RequireLogin;
import com.venky.swf.db.annotations.column.ui.mimes.MimeType;
import com.venky.swf.db.model.CryptoKey;
import com.venky.swf.db.model.SWFHttpResponse;
import com.venky.swf.db.model.reflection.ModelReflector;
import com.venky.swf.integration.FormatHelper;
import com.venky.swf.integration.IntegrationAdaptor;
import com.venky.swf.integration.api.Call;
import com.venky.swf.integration.api.HttpMethod;
import com.venky.swf.integration.api.InputFormat;
import com.venky.swf.path.Path;
import com.venky.swf.plugins.background.core.CoreTask;
import com.venky.swf.plugins.background.core.IOTask;
import com.venky.swf.plugins.background.core.Task;
import com.venky.swf.plugins.background.core.TaskManager;
import com.venky.swf.routing.Config;
import com.venky.swf.sql.Expression;
import com.venky.swf.sql.Operator;
import com.venky.swf.sql.Select;
import com.venky.swf.views.BytesView;
import com.venky.swf.views.View;
import in.succinct.beckn.Acknowledgement;
import in.succinct.beckn.Acknowledgement.Status;
import in.succinct.beckn.City;
import in.succinct.beckn.Context;
import in.succinct.beckn.Error;
import in.succinct.beckn.Location;
import in.succinct.beckn.Request;
import in.succinct.beckn.Response;
import in.succinct.beckn.Subscriber;
import in.succinct.beckn.gateway.configuration.AppInstaller;
import in.succinct.beckn.gateway.extensions.BecknPublicKeyFinder;
import in.succinct.beckn.gateway.util.BGEventEmitter;
import in.succinct.beckn.gateway.util.ECEventEmitter;
import in.succinct.beckn.gateway.util.GWConfig;
import in.succinct.catalog.indexer.db.model.Provider;
import in.succinct.catalog.indexer.ingest.CatalogDigester;
import in.succinct.catalog.indexer.ingest.CatalogSearchEngine;
import in.succinct.onet.core.adaptor.NetworkAdaptor;
import in.succinct.onet.core.adaptor.NetworkAdaptorFactory;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Controllers in Succinct return Views that is serialized and sent as response.
 */
@SuppressWarnings("unused")
public class BgController extends Controller {
    public BgController(Path path) {
        super(path);
    }

    /**
     * Give a NACK response
     * @param request - incoming request
     * @param realm - caller
     * @return a nack response
     */
    public View nack(Request request, String realm){
        Acknowledgement nack = new Acknowledgement(Status.NACK);
        Response response = new Response(new Acknowledgement(Status.NACK));
        String sResponse = response.toString();

        Config.instance().getLogger(BgController.class.getName()).log(Level.WARNING,sResponse);
        BGEventEmitter.getInstance().log_request_processed(request.getContext(),0);

        return new BytesView(getPath(),
                sResponse.getBytes(StandardCharsets.UTF_8),
                MimeType.APPLICATION_JSON,"WWW-Authenticate","Signature realm=\""+realm+"\"",
                "headers=\"(created) (expires) digest\""){
            @Override
            public void write() throws IOException {
                super.write(HttpServletResponse.SC_UNAUTHORIZED);
            }
        };
    }

    @RequireLogin(value = false)
    public View log(long id) throws IOException{
        return super.info(id);
    }

    /**
     * Returns an ACK Response.
     * @param request - Incoming  Request
     * @return - ACK Response
     */
    public View ack(Request request){
        Acknowledgement ack = new Acknowledgement(Status.ACK);
        String responseString = new Response(ack).toString();
        Config.instance().getLogger(BgController.class.getName()).log(Level.INFO,responseString);

        return new BytesView(getPath(),responseString.getBytes(StandardCharsets.UTF_8) , MimeType.APPLICATION_JSON);
    }


    private static Subscriber getCriteria(Context context) {
        Subscriber criteria = new Subscriber();
        String countryCode = context.getCountry();
        String cityCode = context.getCity();
        if (countryCode != null){
            criteria.setCountry(countryCode);
        }
        if (!ObjectUtil.isVoid(cityCode)){
            if (!ObjectUtil.isVoid(context.getVersion())){
                //1.x
                criteria.setLocation(new Location());
                criteria.getLocation().setCity(new City());
                criteria.getLocation().getCity().setCode(cityCode);
            }else {
                criteria.setCity(cityCode);
            }
        }
        criteria.setDomain(context.getDomain());
        criteria.setStatus(Subscriber.SUBSCRIBER_STATUS_SUBSCRIBED);

        return criteria;
    }
    private View act(){
        Request request = null;
        try {
            request = new Request(StringUtil.read(getPath().getInputStream()));
            request.setObjectCreator(NetworkAdaptorFactory.getInstance().getAdaptor().getObjectCreator(StringUtil.valueOf(request.getContext().getDomain())));

            List<CoreTask> tasks = new ArrayList<>();
            Config.instance().getLogger(getClass().getName()).info("Headers:" + getPath().getHeaders());
            Config.instance().getLogger(getClass().getName()).info("Payload:" + request);

            /*
            Validate signature if authorization is enabled
             */
            if (!GWConfig.isAuthorizationHeaderEnabled() ||
                    request.verifySignature("Authorization",getPath().getHeaders() , GWConfig.isAuthorizationHeaderMandatory())){
                Context context = request.getContext();
                if ("search".equals(request.getContext().getAction())){
                    /* Get basic meta from context */
                    Subscriber criteria = getCriteria(request.getContext());

                    criteria.setType(Subscriber.SUBSCRIBER_TYPE_BPP);

                    if (!ObjectUtil.isVoid(context.getBppId())){
                        criteria.setSubscriberId(context.getBppId());
                    }

                    /* lookup for bpps in the domain and city/country */
                    Map<String,Subscriber> subscriberMap = new HashMap<>(){{
                        for (Subscriber s : BecknPublicKeyFinder.lookup(criteria)){
                            put(s.getSubscriberId(),s);
                        }
                    }};

                    Map<String,Subscriber> subscribersWithInternalCatalog = new HashMap<>();


                    ModelReflector<Provider> ref = ModelReflector.instance(Provider.class);
                    List<Provider> tmpProviders = new Select("MAX(ID) AS ID","SUBSCRIBER_ID").from(Provider.class).
                            where(new Expression(ref.getPool(),"SUBSCRIBER_ID",Operator.IN,subscriberMap.keySet().toArray(new String[]{}))).
                            groupBy("SUBSCRIBER_ID").execute();

                    for (Provider tmpProvider : tmpProviders) {
                        subscribersWithInternalCatalog.put(tmpProvider.getSubscriberId(),subscriberMap.remove(tmpProvider.getSubscriberId()));
                    }

                   tasks.add(new Search(request,subscriberMap,getPath().getHeaders(),false));
                   tasks.add(new Search(request,subscribersWithInternalCatalog,getPath().getHeaders(),true));
                }else if ("on_search".equals(request.getContext().getAction())){
                    Subscriber criteria = getCriteria(request.getContext());
                    IgnoreCaseMap<String> headers = new IgnoreCaseMap<>();
                    headers.putAll(getPath().getHeaders());

                    if (!ObjectUtil.isVoid(context.getBapId())) {
                        criteria.setType(Subscriber.SUBSCRIBER_TYPE_BAP);
                        criteria.setSubscriberId(context.getBapId());
                    }else {
                        criteria.setSubscriberId(GWConfig.getSubscriberId());
                        criteria.setType(Subscriber.SUBSCRIBER_TYPE_BG);
                    }
                        /* For each subscriber submit an async task Will be only one, the BAP who fired the search*/
                    List<Subscriber> subscriberList = BecknPublicKeyFinder.lookup(criteria);

                    for (Subscriber subscriber : subscriberList){
                        if (!ObjectUtil.equals(subscriber.getSubscriberId(),GWConfig.getSubscriberId())) {
                            tasks.add(new OnSearch(request, subscriber, headers));
                        }else {
                            Request internal = new Request();
                            internal.update(request);
                            tasks.add(new CatalogDigester(internal.getContext(), internal.getMessage().getCatalog()));
                        }
                    }
                }
                //* As the tasks are not critical, these are not persisted. Non persistence also gives speed. And Persistence requires tasks to be serializable.
                Collections.shuffle(tasks);
                TaskManager.instance().executeAsync(tasks,false); //Submit all async tasks.
                BGEventEmitter.getInstance().log_request_processed(context,tasks.size());
                return ack(request);
            }else {
                return nack(request,request.getContext().getBapId());
            }
        }catch (Exception ex){
            if (request == null){
                throw new RuntimeException();
            }
            Request response  = new Request();
            Error error = new Error();
            response.setContext(request.getContext());
            response.setError(error);
            error.setCode(ex.getMessage());
            StringWriter message = new StringWriter();
            ex.printStackTrace(new PrintWriter(message));
            error.setMessage(message.toString());
            Config.instance().getLogger(BgController.class.getName()).log(Level.WARNING,message.toString());
            return new BytesView(getPath(),response.toString().getBytes(StandardCharsets.UTF_8),MimeType.APPLICATION_JSON);
        }
    }


    /**
     * /search call is delegated to act
     * @return the ACK/NACK response
     */
    @RequireLogin(false)

    public View search() {
        return act();
    }

    /**
     * /on_search call is delegated to act
     * @return the ACK/NACK response
     */
    @RequireLogin(false)
    public View on_search() {
        return act();
    }

  protected static Map<String, String> getHeaders(Request request) {
        Map<String,String> headers  = new HashMap<>();
        if (GWConfig.isAuthorizationHeaderEnabled()) {

            String subscriberId = GWConfig.getSubscriberId();
            String pub_key_id = GWConfig.getPublicKeyId();
            String authHeader = request.generateAuthorizationHeader(subscriberId,pub_key_id);
            headers.put("X-Gateway-Authorization", authHeader);
            headers.put("Proxy-Authorization", authHeader);
            headers.put("Authorization",authHeader);

        }
        headers.put("Content-Type", MimeType.APPLICATION_JSON.toString());
        headers.put("Accept", MimeType.APPLICATION_JSON.toString());

        return headers;
    }



    public static class Search implements Task {
        Request originalRequest;
        Map<String,Subscriber> subscriberMap;
        Map<String,String> headers;
        NetworkAdaptor networkAdaptor;
        boolean internalCatalog = false;

        public Search(Request request, Subscriber bpp, Map<String, String> headers){
            this(request,new HashMap<>(){{
                put(bpp.getSubscriberId(),bpp);
            }},headers,false);
        }
        public Search(Request request, Map<String, Subscriber> subscriberMap, Map<String, String> headers,boolean internalCatalog){
            this.networkAdaptor = NetworkAdaptorFactory.getInstance().getAdaptor();
            this.originalRequest = request;
            this.subscriberMap = subscriberMap;
            this.headers = headers;
            this.internalCatalog = internalCatalog;
        }

        @Override
        public void execute() {
            ECEventEmitter ecEventEmitter = new ECEventEmitter();
            if (ecEventEmitter.isEventPublishingRequired(originalRequest)){
                try{
                    long maxSleepTime = 2*1000L; //2 seconds
                    Thread.sleep(Math.max(Config.instance().getLongProperty("ec.sleep.time.millis",maxSleepTime),maxSleepTime));
                }catch (Exception ex){
                    //
                }
            }
            for (Subscriber bpp : subscriberMap.values()) {
                ecEventEmitter.emit(bpp, originalRequest);
            }





            String bgPublicKey = Request.getPublicKey(GWConfig.getSubscriberId(),GWConfig.getPublicKeyId());
            if (internalCatalog){
                CatalogSearchEngine searchEngine = new CatalogSearchEngine(subscriberMap);
                Context context = originalRequest.getContext();
                Subscriber criteria = getCriteria(context);
                criteria.setType(Subscriber.SUBSCRIBER_TYPE_BAP);
                List<Subscriber> bapList ;
                if (!ObjectUtil.isVoid(context.getBapId())) {
                    criteria.setSubscriberId(context.getBapId());
                    bapList = BecknPublicKeyFinder.lookup(criteria);
                }else {
                    bapList = new ArrayList<>();
                }
                if (bapList.isEmpty()){
                    return;
                }

                originalRequest.setObjectCreator(NetworkAdaptorFactory.getInstance().getAdaptor().
                        getObjectCreator(originalRequest.getContext().getDomain()));
                Request internalFormatRequest = new Request();
                internalFormatRequest.update(originalRequest);

                List<Request> internalFormatResponses = new ArrayList<>();
                searchEngine.search(internalFormatRequest,internalFormatResponses);
                Collections.shuffle(internalFormatResponses); // Fairness!! ha ha


                for (Request internalFormatResponse : internalFormatResponses) {
                    if (internalFormatResponse.isSuppressed()){
                        continue;
                    }
                    Request on_search = networkAdaptor.getObjectCreator(originalRequest.getContext().getDomain()).create(Request.class);
                    on_search.update(internalFormatResponse);

                    for (Subscriber bap : bapList) {
                        OnSearch onSearch = new OnSearch(on_search, bap, null);
                        TaskManager.instance().executeAsync(onSearch,false);
                    }
                }

            }else {
                List<Subscriber> bpps = new ArrayList<>(subscriberMap.values());
                Collections.shuffle(bpps);
                for (Subscriber bpp : bpps) {
                    TaskManager.instance().executeAsync((IOTask) () -> {
                        Call < InputStream > call = new Call<InputStream>().url(bpp.getSubscriberUrl() + "/" + originalRequest.getContext().getAction()).
                                method(HttpMethod.POST).inputFormat(InputFormat.INPUT_STREAM).timeOut(GWConfig.getTimeOut()).
                                input(new ByteArrayInputStream(originalRequest.toString().getBytes(StandardCharsets.UTF_8))).headers(getHeaders(originalRequest));

                        if (headers != null && headers.containsKey("Authorization")) {
                            call.header("Authorization", headers.get("Authorization"));
                        }
                        try {
                            if (call.hasErrors() && call.getStatus() > 500 && GWConfig.disableSlowBpp()) {
                                disableBpp(bpp);
                            }
                        } catch (RuntimeException ex) {
                            if (ExceptionUtil.getEmbeddedException(ex, HttpTimeoutException.class) != null && GWConfig.disableSlowBpp()) {
                                disableBpp(bpp);
                            }
                        }
                    },false);
                }
            }
        }

        public void disableBpp(Subscriber bpp){
            Subscriber registry = NetworkAdaptorFactory.getInstance().getAdaptor().getRegistry();

            Request payload = new Request(bpp.toString());
            Map<String,String> headers = getHeaders(payload);
            new Call<InputStream>().method(HttpMethod.POST).url(registry.getSubscriberUrl() ,"/disable").
                    input(new ByteArrayInputStream(payload.toString().getBytes(StandardCharsets.UTF_8))).inputFormat(InputFormat.INPUT_STREAM).headers(headers).hasErrors();
        }


    }

    public static class OnSearch implements IOTask {
        Request originalRequest;
        Subscriber bap ;
        Map<String,String> headers;
        public OnSearch(Request request, Subscriber bap, Map<String, String> headers){
            this.originalRequest = request;
            this.bap = bap;
            this.headers = headers;
        }
        @Override
        public void execute() {
            ECEventEmitter ecEventEmitter = new ECEventEmitter();
            if (ecEventEmitter.isEventPublishingRequired(originalRequest)){
                try{
                    long maxSleepTime = 2*1000L; //2 seconds
                    Thread.sleep(Math.max(Config.instance().getLongProperty("bg.ec.sleep.time.millis",maxSleepTime),maxSleepTime));
                }catch (Exception ex){
                    //
                }
            }

            Call<InputStream> call = new Call<InputStream>().url(bap.getSubscriberUrl()+ "/"+originalRequest.getContext().getAction()).
                    method(HttpMethod.POST).inputFormat(InputFormat.INPUT_STREAM).timeOut(GWConfig.getTimeOut()). //5 Seconds
                    input(new ByteArrayInputStream(originalRequest.toString().getBytes(StandardCharsets.UTF_8))).headers(getHeaders(originalRequest));
            if (this.headers != null && headers.containsKey("Authorization")){
                call.header("Authorization",headers.get("Authorization"));
            }
            new ECEventEmitter().emit(bap,originalRequest);
            call.getResponseAsJson();
        }
    }


    @RequireLogin(value = false)
    @SuppressWarnings("unchecked")
    public View on_subscribe() throws Exception{
        NetworkAdaptor networkAdaptor = NetworkAdaptorFactory.getInstance().getAdaptor();
        String payload = StringUtil.read(getPath().getInputStream());
        JSONObject object = (JSONObject) JSONValue.parse(payload);


        if (!Request.verifySignature(getPath().getHeader("Signature"), payload, networkAdaptor.getRegistry().getSigningPublicKey())){
            throw new RuntimeException("Cannot verify Signature");
        }

        PrivateKey privateKey = Crypt.getInstance().getPrivateKey(Request.ENCRYPTION_ALGO,
                CryptoKey.find(GWConfig.getPublicKeyId(),CryptoKey.PURPOSE_ENCRYPTION).getPrivateKey());

        PublicKey registryPublicKey = Request.getEncryptionPublicKey(networkAdaptor.getRegistry().getEncrPublicKey());

        KeyAgreement agreement = KeyAgreement.getInstance(Request.ENCRYPTION_ALGO);
        agreement.init(privateKey);
        agreement.doPhase(registryPublicKey,true);

        SecretKey key = agreement.generateSecret("TlsPremasterSecret");

        JSONObject output = new JSONObject();
        output.put("answer", Crypt.getInstance().decrypt((String)object.get("challenge"),"AES",key));

        return new BytesView(getPath(),output.toString().getBytes(),MimeType.APPLICATION_JSON);
    }

    public View subscribe() {
        AppInstaller.registerBecknKeys();
        return IntegrationAdaptor.instance(SWFHttpResponse.class, FormatHelper.getFormatClass(MimeType.APPLICATION_JSON)).createStatusResponse(getPath(),null);
    }
}
